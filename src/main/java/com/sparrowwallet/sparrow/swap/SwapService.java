package com.sparrowwallet.sparrow.swap;

import com.google.common.eventbus.Subscribe;
import com.google.common.net.HostAndPort;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.TorReadyStatusEvent;
import com.sparrowwallet.sparrow.gui.AshigaruGui;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ServerType;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The app's one swap engine. It runs whenever swaps are on and Tor is up, whichever wallets are open,
 * and resumes unfinished swaps when it starts. Swaps go one at a time, each spending one Badbank coin;
 * the wallet is only involved until the lock is signed, after which the engine finishes or refunds
 * the swap with keys of its own.
 *
 * <p>The engine's identity is renewed whenever no swap is unfinished, so makers cannot link swaps.
 *
 * <p>Everything here runs on the JavaFX thread; engine calls go to background services.
 */
public class SwapService implements SwapHost {
    private static final Logger log = LoggerFactory.getLogger(SwapService.class);
    private static final int MAX_LOG_CHARS = 200_000;
    private static final int MAX_EVENT_CHARS = 600;

    private static SwapService instance;

    /** A Badbank coin chosen in the wallet view, waiting for an offer to be taken */
    public record Coin(String walletName, Wallet badbank, BlockTransactionHashIndex outpoint) {}

    private Boolean available;
    //The loaded library; it runs one engine at a time
    private SwapEngine engine;
    private boolean starting;
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Off.");
    private final ReadOnlyObjectWrapper<Coin> coin = new ReadOnlyObjectWrapper<>();
    //The swap being set up: the only one Ashigaru answers wallet requests for
    private BadbankSwapHost activeHost;
    private final Set<String> runningSwaps = ConcurrentHashMap.newKeySet();
    //Each swap's latest step, as reported by the engine; the swap being set up is the latest started
    private final Map<String, SwapProgress.Status> progress = new ConcurrentHashMap<>();
    private String currentSwapId;
    private final StringBuilder logText = new StringBuilder();
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> swapListeners = new CopyOnWriteArrayList<>();

    public static synchronized SwapService get() {
        if(instance == null) {
            instance = new SwapService();
            EventManager.get().register(instance);
        }
        return instance;
    }

    /** Whether this build can swap at all: the engine is bundled (or configured) and this is mainnet */
    public boolean isAvailable() {
        if(available == null) {
            available = Network.get() == Network.MAINNET && loadEngine() != null;
        }
        return available;
    }

    public boolean isEnabled() {
        return Config.get().isSwapsEnabled() && isAvailable();
    }

    public boolean isSwapActive() {
        return !runningSwaps.isEmpty() || activeHost != null;
    }

    /** The latest step of a swap, or null when nothing is known about it yet */
    public SwapProgress.Status getProgress(String swapId) {
        return swapId == null ? null : progress.get(swapId);
    }

    /** The swap started or resumed most recently, to show first */
    public String getCurrentSwapId() {
        return currentSwapId;
    }

    public ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<Coin> coinProperty() {
        return coin.getReadOnlyProperty();
    }

    public String getLog() {
        return logText.toString();
    }

    public void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
    }

    public void removeLogListener(Consumer<String> listener) {
        logListeners.remove(listener);
    }

    /** Told when a swap starts or stops, so a swap list can refresh */
    public void addSwapListener(Runnable listener) {
        swapListeners.add(listener);
    }

    public void removeSwapListener(Runnable listener) {
        swapListeners.remove(listener);
    }

    /** Chooses the Badbank coin to swap next, from the wallet view */
    public void chooseCoin(String walletName, Wallet badbank, BlockTransactionHashIndex outpoint) {
        coin.set(new Coin(walletName, badbank, outpoint));
    }

    /** Called when a wallet is locked or removed: a coin chosen from it is forgotten */
    public void walletUnloaded(Wallet wallet) {
        Coin chosen = coin.get();
        if(chosen != null && chosen.badbank().getMasterWallet() == wallet) {
            coin.set(null);
        }
    }

    /** Starts the engine if swaps are on and everything it needs is there; says why not otherwise */
    public void startIfEnabled() {
        if(!Config.get().isSwapsEnabled()) {
            status.set("Off.");
            return;
        }
        if(running.get() || starting) {
            return;
        }
        String problem = problem();
        if(problem != null) {
            status.set(problem);
            return;
        }
        start(false);
    }

    /** Stops the engine, e.g. when swaps are turned off. Unfinished swaps resume when it starts again. */
    public void stop() {
        if(!running.get() && !starting) {
            status.set("Off.");
            return;
        }
        SwapEngine stopping = engine;
        running.set(false);
        status.set("Off.");
        runInBackground(() -> {
            stopping.shutdown();
            return null;
        }, ignored -> appendLog("Swap engine stopped"), e -> {});
    }

    /** Restarts the engine with the current settings, e.g. after the Monero nodes changed */
    public void restart() {
        if(running.get() && !isSwapActive()) {
            running.set(false);
            start(false);
        } else if(!running.get()) {
            startIfEnabled();
        }
    }

    private String problem() {
        if(!isAvailable()) {
            return Network.get() == Network.MAINNET ? "This build does not include the swap engine." : "Swaps run on mainnet only.";
        }
        Server server = Config.get().getServer();
        if(server == null || Config.get().getServerType() == ServerType.BITCOIN_CORE) {
            return "Waiting for an Electrum server: swaps watch the chain through one. Connect Ashigaru to a Dojo or Electrum server.";
        }
        String nodes = MoneroNodes.problem(MoneroNodes.getSelected());
        if(nodes != null) {
            return nodes;
        }
        if(AppServices.getTorProxy() == null) {
            return "Waiting for Tor. Swaps only connect through Tor.";
        }
        return null;
    }

    /**
     * Starts (or replaces) the engine. With {@code renewIdentity}, a fresh identity is used: after a
     * swap finished, or at start when the last identity's swaps are all finished.
     */
    private void start(boolean renewIdentity) {
        HostAndPort torProxy = AppServices.getTorProxy();
        List<String> electrumUrls = List.of(Config.get().getServer().getUrl());
        List<String> moneroNodes = MoneroNodes.forEngine(MoneroNodes.getSelected());
        File swapsDir = new File(Storage.getSparrowHome(), "swaps");
        SwapEngine loaded = loadEngine();

        starting = true;
        status.set(renewIdentity ? "Taking a fresh identity for the next swap…" : "Starting through Tor…");
        runInBackground(() -> {
            SwapIdentity identity = renewIdentity ? SwapIdentity.renew(swapsDir) : SwapIdentity.current(swapsDir);
            loaded.start(identity.getDataDir(), torProxy, identity.getSeed(), this, electrumUrls, moneroNodes);
            List<String> resumed = new ArrayList<>();
            loaded.resumeAll().forEach(id -> resumed.add(id.getAsString()));
            boolean used = !loaded.listSwaps().isEmpty();
            //Identity already used and nothing left to finish: makers have seen it, so take a new one
            if(!renewIdentity && resumed.isEmpty() && used) {
                identity = SwapIdentity.renew(swapsDir);
                loaded.start(identity.getDataDir(), torProxy, identity.getSeed(), this, electrumUrls, moneroNodes);
            }
            return resumed;
        }, resumed -> {
            starting = false;
            if(!Config.get().isSwapsEnabled()) {
                stop();
                return;
            }
            runningSwaps.addAll(resumed);
            if(!resumed.isEmpty()) {
                currentSwapId = resumed.get(resumed.size() - 1);
            }
            running.set(true);
            status.set("On. Using " + moneroNodes.size() + " Monero node" + (moneroNodes.size() == 1 ? "" : "s")
                    + (moneroNodes.size() > 1 ? ", which must agree before a swap releases your BTC." : ", your own."));
            appendLog("Swap engine running" + (moneroNodes.size() > 1 ? " with " + moneroNodes.size() + " Monero nodes in agreement" : ""));
            if(!resumed.isEmpty()) {
                appendLog("Resumed unfinished swaps: " + String.join(", ", resumed));
            }
            swapListeners.forEach(Runnable::run);
        }, e -> {
            starting = false;
            status.set("Could not start: " + e.getMessage());
        });
    }

    @Subscribe
    public void torReady(TorReadyStatusEvent event) {
        Platform.runLater(this::startIfEnabled);
    }

    public void listQuotes(Consumer<List<SwapEngine.Quote>> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(engine::listQuotes, onSuccess, onFailure);
    }

    /** The fee and swap amount for the chosen coin at the current fee rate */
    public long[] lockFeeAndAmount() {
        Coin chosen = coin.get();
        BadbankSwapHost host = new BadbankSwapHost(chosen.badbank(), chosen.outpoint(), null);
        long fee = host.estimateLockFee();
        return new long[] {fee, chosen.outpoint().getValue() - fee};
    }

    /** Swaps the chosen coin with this maker */
    public void startSwap(SwapEngine.Quote maker, String xmrAddress, Consumer<String> onSuccess, Consumer<Throwable> onFailure) {
        Coin chosen = coin.get();
        if(chosen == null) {
            onFailure.accept(new IllegalStateException("Choose a Badbank coin first."));
            return;
        }
        if(isSwapActive()) {
            onFailure.accept(new IllegalStateException("A swap is already running; one at a time."));
            return;
        }

        BadbankSwapHost host = new BadbankSwapHost(chosen.badbank(), chosen.outpoint(), AshigaruGui.get().getMainStage());
        long fee;
        try {
            fee = host.estimateLockFee();
        } catch(Exception e) {
            onFailure.accept(e);
            return;
        }
        long sats = chosen.outpoint().getValue() - fee;
        String refundAddress = host.getFreshAddress();
        activeHost = host;
        runInBackground(() -> engine.startSwap(maker, sats, fee, xmrAddress, refundAddress), swapId -> {
            runningSwaps.add(swapId);
            currentSwapId = swapId;
            progress.putIfAbsent(swapId, new SwapProgress.Status(SwapProgress.Step.NODES, null, java.time.Instant.now(), false));
            coin.set(null);
            appendLog("Swap " + swapId + " started: " + BadbankSwapHost.btc(sats) + " BTC from " + chosen.walletName()
                    + "'s Badbank coin to " + xmrAddress + ", network fee " + fee + " sats, refunds to " + refundAddress);
            swapListeners.forEach(Runnable::run);
            onSuccess.accept(swapId);
        }, e -> {
            activeHost = null;
            onFailure.accept(e);
        });
    }

    /** Asks each Monero node for its height through Tor, so a dead one is found before a swap needs it */
    public void checkNodes(Consumer<JsonArray> onSuccess, Consumer<Throwable> onFailure) {
        if(!running.get()) {
            onFailure.accept(new IllegalStateException("Swaps are not running"));
            return;
        }
        runInBackground(engine::checkNodes, onSuccess, onFailure);
    }

    /** The unreachable nodes in a check, as one line each, or an empty list when all answered */
    public static List<String> unreachable(JsonArray check) {
        List<String> problems = new ArrayList<>();
        for(JsonElement element : check) {
            JsonObject node = element.getAsJsonObject();
            if(!node.get("ok").getAsBoolean()) {
                problems.add(node.get("node").getAsString() + ": " + (node.has("error") ? node.get("error").getAsString() : "no answer"));
            }
        }
        return problems;
    }

    public void listSwaps(Consumer<JsonArray> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(engine::listSwaps, onSuccess, onFailure);
    }

    public void refund(String swapId, Consumer<String> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(() -> engine.cancelAndRefund(swapId), state -> {
            appendLog("Swap " + swapId + " refunded: " + state);
            swapListeners.forEach(Runnable::run);
            onSuccess.accept(state);
        }, onFailure);
    }

    @Override
    public CompletableFuture<JsonElement> handle(String kind, JsonObject payload) {
        BadbankSwapHost host = activeHost;
        if(host == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No swap is being set up"));
        }
        if("sign".equals(kind) || "approve".equals(kind)) {
            //The user is being asked: show it as the swap's current step
            Platform.runLater(() -> {
                String swapId = currentSwapId;
                if(swapId != null) {
                    SwapProgress.Status previous = progress.get(swapId);
                    java.time.Instant since = previous != null && previous.step() == SwapProgress.Step.APPROVAL ? previous.since() : java.time.Instant.now();
                    progress.put(swapId, new SwapProgress.Status(SwapProgress.Step.APPROVAL,
                            "sign".equals(kind) ? "Sign the lock in the dialog" : "Approve or reject the final terms in the dialog", since, false));
                    swapListeners.forEach(Runnable::run);
                }
            });
        }
        return host.handle(kind, payload);
    }

    @Override
    public void onNotification(String kind, JsonObject payload) {
        Platform.runLater(() -> notification(kind, payload));
    }

    private void notification(String kind, JsonObject payload) {
        if("log".equals(kind)) {
            String level = payload.has("level") ? payload.get("level").getAsString() : "INFO";
            String message = payload.has("message") ? payload.get("message").getAsString() : "";
            appendLog(("INFO".equals(level) ? "" : level + " ") + message);
            return;
        }
        if("swap_stopped".equals(kind)) {
            String swapId = payload.get("swapId").getAsString();
            runningSwaps.remove(swapId);
            activeHost = null;
            SwapProgress.Status status = SwapProgress.stopped(payload, progress.get(swapId), java.time.Instant.now());
            progress.put(swapId, status);
            appendLog("Swap " + swapId + " stopped" + (payload.has("error") ? ": " + payload.get("error").getAsString()
                    : payload.has("state") ? " at " + payload.get("state").getAsString() : "") + ".");
            swapListeners.forEach(Runnable::run);
            renewIdentityIfIdle();
            return;
        }

        String event = payload.has("event") ? payload.get("event").getAsString() : kind;
        JsonElement body = payload.get("payload");
        String channel = body != null && body.isJsonObject() && body.getAsJsonObject().has("channelName")
                ? body.getAsJsonObject().get("channelName").getAsString() : event;
        //Log lines and peer discovery are frequent and add nothing to a swap's progress
        if("CliLog".equals(channel) || "P2P".equals(channel) || "BackgroundProgress".equals(channel)) {
            return;
        }
        if("SwapProgress".equals(channel) && body.isJsonObject()) {
            String swapId = SwapProgress.swapId(body.getAsJsonObject());
            if(swapId != null) {
                progress.put(swapId, SwapProgress.read(body.getAsJsonObject(), progress.get(swapId), java.time.Instant.now()));
            }
        }
        if("SwapProgress".equals(channel) || "SwapDatabaseStateUpdate".equals(channel)) {
            swapListeners.forEach(Runnable::run);
        }
        String text = body == null ? "" : body.toString();
        appendLog(channel + ": " + (text.length() > MAX_EVENT_CHARS ? text.substring(0, MAX_EVENT_CHARS) + "…" : text));
    }

    /** After a swap stops: if none is left unfinished, take a new identity for the next one */
    private void renewIdentityIfIdle() {
        if(!running.get() || starting) {
            return;
        }
        listSwaps(swaps -> {
            boolean unfinished = false;
            for(JsonElement swap : swaps) {
                if(!swap.getAsJsonObject().get("complete").getAsBoolean()) {
                    unfinished = true;
                }
            }
            if(!unfinished && !isSwapActive() && problem() == null) {
                start(true);
            }
        }, e -> {});
    }

    private void appendLog(String line) {
        String stamped = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + line + System.lineSeparator();
        logText.append(stamped);
        if(logText.length() > MAX_LOG_CHARS) {
            logText.delete(0, logText.length() - MAX_LOG_CHARS);
        }
        log.info("Swap engine: " + line);
        logListeners.forEach(listener -> listener.accept(stamped));
    }

    private SwapEngine loadEngine() {
        if(engine == null) {
            try {
                File override = Config.get().getSwapEngineLibrary();
                engine = override != null ? SwapEngine.load(override) : SwapEngine.loadBundled();
            } catch(SwapEngineException e) {
                log.info("Swap engine not available: " + e.getMessage());
            }
        }
        return engine;
    }

    private static <T> void runInBackground(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Service<T> service = new Service<>() {
            @Override
            protected Task<T> createTask() {
                return new Task<>() {
                    @Override
                    protected T call() throws Exception {
                        return work.call();
                    }
                };
            }
        };
        service.setOnSucceeded(event -> onSuccess.accept(service.getValue()));
        service.setOnFailed(event -> {
            log.error("Swap engine call failed", service.getException());
            onFailure.accept(service.getException());
        });
        service.start();
    }
}
