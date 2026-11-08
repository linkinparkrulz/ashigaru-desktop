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
import com.sparrowwallet.sparrow.event.TorBootStatusEvent;
import com.sparrowwallet.sparrow.event.TorFailedStatusEvent;
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
    //Why the engine could not be loaded, shown in Settings → Swaps
    private String loadError;
    private boolean starting;
    //The Tor proxy the running engine was started with, to notice when Tor comes back on another port
    private HostAndPort engineProxy;
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Off.");
    private final ReadOnlyObjectWrapper<Coin> coin = new ReadOnlyObjectWrapper<>();
    //The swap being set up: the only one Ashigaru answers wallet requests for
    private BadbankSwapHost activeHost;
    private final Set<String> runningSwaps = ConcurrentHashMap.newKeySet();
    //Each swap's latest step, as reported by the engine; the swap being set up is the latest started
    private final Map<String, SwapProgress.Status> progress = new ConcurrentHashMap<>();
    //Each swap's latest timelock state, from the engine's TimelockChange events: blocks left until cancel or punish
    private final Map<String, SwapTimelock> timelocks = new ConcurrentHashMap<>();
    //The Swaps screen, while it can show a swap's reviews
    private volatile ReviewPresenter reviewPresenter;
    //What was agreed for each swap of this identity: amounts, address, maker. Opened when the engine starts.
    private volatile SwapDetails.Store detailsStore;
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

    /**
     * The Swaps screen registers here to show the swap's reviews in the page. A review only goes to it
     * while it is showing; at any other time Ashigaru asks in a dialog, so a request cannot wait unseen.
     */
    public void setReviewPresenter(ReviewPresenter presenter) {
        reviewPresenter = presenter;
    }

    /** The Swaps screen is going away: a review still on it moves to a dialog */
    public void clearReviewPresenter(ReviewPresenter presenter) {
        if(reviewPresenter == presenter) {
            reviewPresenter = null;
            BadbankSwapHost host = activeHost;
            if(host != null) {
                host.reviewPresenterGone();
            }
        }
    }

    /** The presenter to show a review in, or null when none is on screen and a dialog is needed */
    public ReviewPresenter getReviewPresenter() {
        ReviewPresenter presenter = reviewPresenter;
        return presenter != null && presenter.isShowing() ? presenter : null;
    }

    /** What was agreed for a swap, or null when this identity has no record of it */
    public SwapDetails getDetails(String swapId) {
        SwapDetails.Store store = detailsStore;
        return store == null ? null : store.get(swapId);
    }

    private void saveDetails(String swapId, SwapDetails details) {
        SwapDetails.Store store = detailsStore;
        if(store != null && swapId != null) {
            store.put(swapId, details);
        }
    }

    /** True while the engine is running this swap, as opposed to a swap that stopped and waits to be resumed or cancelled */
    public boolean isSwapRunning(String swapId) {
        return swapId != null && runningSwaps.contains(swapId);
    }

    /** Where a swap stands against its timelocks, or null when the engine has not said (or the swap is over) */
    public SwapTimelock getTimelock(String swapId) {
        return swapId == null ? null : timelocks.get(swapId);
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
            return Network.get() != Network.MAINNET ? "Swaps run on mainnet only."
                    : loadError != null ? loadError : "This build does not include the swap engine.";
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
        engineProxy = torProxy;
        List<String> electrumUrls = List.of(Config.get().getServer().getUrl());
        List<String> moneroNodes = MoneroNodes.forEngine(MoneroNodes.getSelected());
        File swapsDir = new File(Storage.getSparrowHome(), "swaps");
        SwapEngine loaded = loadEngine();

        starting = true;
        long startedAt = System.currentTimeMillis();
        status.set(renewIdentity ? "Taking a fresh identity for the next swap…" : "Starting through Tor…");
        runInBackground(() -> {
            SwapIdentity identity = renewIdentity ? SwapIdentity.renew(swapsDir) : SwapIdentity.current(swapsDir);
            loaded.start(identity.getDataDir(), torProxy, identity.getSeed(), this, electrumUrls, moneroNodes,
                    SwapIdentity.pastDataDirs(swapsDir, identity.getDataDir()));
            List<String> resumed = new ArrayList<>();
            loaded.resumeAll().forEach(id -> resumed.add(id.getAsString()));
            boolean used = usedByThisIdentity(loaded.listSwaps());
            //Identity already used and nothing left to finish: makers have seen it, so take a new one
            if(!renewIdentity && resumed.isEmpty() && used) {
                identity = SwapIdentity.renew(swapsDir);
                loaded.start(identity.getDataDir(), torProxy, identity.getSeed(), this, electrumUrls, moneroNodes,
                    SwapIdentity.pastDataDirs(swapsDir, identity.getDataDir()));
            }
            detailsStore = new SwapDetails.Store(identity.getDataDir());
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
            appendLog("Swap engine running" + (moneroNodes.size() > 1 ? " with " + moneroNodes.size() + " Monero nodes in agreement" : "")
                    + " (ready in " + (System.currentTimeMillis() - startedAt) / 1000 + " s)");
            if(!resumed.isEmpty()) {
                appendLog("Resumed unfinished swaps: " + String.join(", ", resumed));
            }
            swapListeners.forEach(Runnable::run);
            //Tor may have moved or gone while the engine was starting, which followTor had to leave until now
            followTor();
        }, e -> {
            starting = false;
            status.set("Could not start: " + e.getMessage());
            if(!Objects.equals(AppServices.getTorProxy(), engineProxy)) {
                followTor();
            }
        });
    }

    @Subscribe
    public void torReady(TorReadyStatusEvent event) {
        Platform.runLater(this::followTor);
    }

    @Subscribe
    public void torBooting(TorBootStatusEvent event) {
        Platform.runLater(this::followTor);
    }

    @Subscribe
    public void torFailed(TorFailedStatusEvent event) {
        Platform.runLater(this::followTor);
    }

    /**
     * Keeps the engine on the Tor proxy that is actually there. The engine dials the one port it was
     * started with, and a Tor that restarts listens on another ("ProxyServerUnreachable" until the
     * engine is started again) or may not come back at all. A running swap resumes from its database.
     * While the engine is starting nothing is done; it looks again when the start finishes.
     */
    private void followTor() {
        if(starting) {
            return;
        }
        if(!running.get()) {
            startIfEnabled();
            return;
        }
        if(!Config.get().isSwapsEnabled()) {
            return;
        }
        HostAndPort proxy = AppServices.getTorProxy();
        if(proxy == null) {
            appendLog("Tor is gone; stopping the swap engine until it is back");
            SwapEngine stopping = engine;
            running.set(false);
            status.set("Waiting for Tor. Swaps only connect through Tor.");
            runInBackground(() -> {
                stopping.shutdown();
                return null;
            }, ignored -> {}, e -> {});
        } else if(!proxy.equals(engineProxy)) {
            appendLog("Tor restarted on " + proxy + "; restarting the swap engine to use it");
            running.set(false);
            start(false);
        }
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
        host.setPresenterSource(this::getReviewPresenter);
        host.setSwapContext(xmrAddress, SwapReview.shortId(maker.peerId()));
        //The XMR amount and any deposit are only known once the maker's terms reach the approval
        host.setTermsListener((piconero, amnestySats, fullRefund) -> {
            String id = currentSwapId;
            SwapDetails known = getDetails(id);
            if(known != null) {
                saveDetails(id, known.withTerms(piconero, amnestySats, fullRefund));
                swapListeners.forEach(Runnable::run);
            }
        });
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
            saveDetails(swapId, SwapDetails.started(sats, fee, xmrAddress, SwapReview.shortId(maker.peerId())));
            progress.putIfAbsent(swapId, new SwapProgress.Status(SwapProgress.Step.NODES, null, java.time.Instant.now(), false));
            coin.set(null);
            appendLog("Swap " + swapId + " started: " + BadbankSwapHost.btc(sats) + " BTC from " + chosen.walletName()
                    + "'s Badbank coin to " + xmrAddress + ", network fee " + fee + " sats, refunds to " + refundAddress
                    + ", with maker " + maker.peerId() + (maker.version() == null ? "" : " v" + maker.version()) + " at " + maker.multiaddr());
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
        runInBackground(engine::listSwaps, list -> {
            noteListedSwaps(list);
            onSuccess.accept(list);
        }, onFailure);
    }

    /**
     * A swap that sent no progress events in this run, such as one found again after a restart, is placed
     * from the engine's record of it, so it can be shown and, if it stopped, recovered. A swap that has
     * already reported progress is left as it is: its own events know better.
     */
    void noteListedSwaps(JsonArray swaps) {
        java.time.Instant now = java.time.Instant.now();
        for(JsonElement element : swaps) {
            JsonObject swap = element.getAsJsonObject();
            if(swap.has("past") && swap.get("past").getAsBoolean() || !swap.has("swapId")) {
                continue;
            }
            String swapId = swap.get("swapId").getAsString();
            SwapProgress.Status known = progress.get(swapId);
            boolean running = swap.has("running") && swap.get("running").getAsBoolean();
            boolean complete = swap.has("complete") && swap.get("complete").getAsBoolean();
            //A swap that stopped in this run has its own status, with the reason; a finished one is final
            if(known != null && (known.finished() || running)) {
                continue;
            }
            String state = swap.has("state") ? swap.get("state").getAsString() : "";
            SwapProgress.Status placed = SwapProgress.fromState(state, complete, running, now);
            if(known == null || known.step() != SwapProgress.Step.STOPPED) {
                progress.put(swapId, placed);
            }
        }
    }

    public void refund(String swapId, Consumer<String> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(() -> engine.cancelAndRefund(swapId), state -> {
            appendLog("Swap " + swapId + " refunded: " + state);
            //A manual refund sends no progress events, so the screens are told it is done
            progress.put(swapId, new SwapProgress.Status(SwapProgress.Step.REFUNDED, "Refund published to Badbank.", java.time.Instant.now(), true));
            timelocks.remove(swapId);
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

    void notification(String kind, JsonObject payload) {
        if("log".equals(kind)) {
            String level = payload.has("level") ? payload.get("level").getAsString() : "INFO";
            String message = ("INFO".equals(level) ? "" : level + " ") + (payload.has("message") ? payload.get("message").getAsString() : "");
            //Routine chatter (wallet2, maker connections coming and going) is kept for the log file only
            if(payload.has("quiet") && payload.get("quiet").getAsBoolean()) {
                log.info("Swap engine: " + message);
            } else {
                appendLog(message);
            }
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
        //The timelock moves with every block, so it updates the screens and nothing else
        if("TimelockChange".equals(channel) && body != null && body.isJsonObject()) {
            String swapId = SwapTimelock.swapId(body.getAsJsonObject());
            if(swapId != null) {
                SwapTimelock timelock = SwapTimelock.read(body.getAsJsonObject(), java.time.Instant.now());
                if(timelock == null) {
                    timelocks.remove(swapId);
                } else {
                    timelocks.put(swapId, timelock);
                }
                swapListeners.forEach(Runnable::run);
                return;
            }
        }
        String text = body == null ? "" : body.toString();
        String raw = channel + ": " + (text.length() > MAX_EVENT_CHARS ? text.substring(0, MAX_EVENT_CHARS) + "…" : text);
        if("SwapProgress".equals(channel) && body.isJsonObject()) {
            String swapId = SwapProgress.swapId(body.getAsJsonObject());
            if(swapId != null) {
                SwapProgress.Status previous = progress.get(swapId);
                SwapProgress.Status status = SwapProgress.read(body.getAsJsonObject(), previous, java.time.Instant.now());
                progress.put(swapId, status);
                //The swap log gets a readable line per step; the raw event stays in the log file
                log.info("Swap engine: " + raw);
                if(previous == null || previous.step() != status.step() || !java.util.Objects.equals(previous.detail(), status.detail())) {
                    appendLog("Swap " + swapId.substring(0, Math.min(8, swapId.length())) + ": " + status.step().getTitle()
                            + (status.detail() == null || status.detail().isEmpty() ? "" : " (" + status.detail() + ")"));
                }
                swapListeners.forEach(Runnable::run);
                return;
            }
        }
        if("SwapProgress".equals(channel) || "SwapDatabaseStateUpdate".equals(channel)) {
            swapListeners.forEach(Runnable::run);
        }
        appendLog(raw);
    }

    /** True when the engine's own database holds a swap; earlier identities' swaps, listed too, do not count */
    static boolean usedByThisIdentity(JsonArray swaps) {
        for(JsonElement swap : swaps) {
            JsonObject entry = swap.getAsJsonObject();
            if(!entry.has("past") || !entry.get("past").getAsBoolean()) {
                return true;
            }
        }
        return false;
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
                loadError = e.getMessage();
                if(e.getCause() != null) {
                    log.warn("Swap engine not available: " + e.getMessage(), e.getCause());
                } else {
                    log.info("Swap engine not available: " + e.getMessage());
                }
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
