package com.sparrowwallet.sparrow.swap;

import com.google.common.eventbus.Subscribe;
import com.google.common.net.HostAndPort;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.TorReadyStatusEvent;
import com.sparrowwallet.sparrow.gui.AshigaruGui;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ServerType;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The app's one swap engine. It starts by itself for an open wallet once Tor is ready, resumes that
 * wallet's unfinished swaps, and outlives the swap window, so closing the window never stops a swap.
 *
 * <p>Each wallet's swap identity is derived while the wallet is being unlocked, when its key is
 * already at hand, so swapping never asks for the password a second time. Identities stay in memory
 * only, and are wiped when the wallet is locked.
 *
 * <p>Everything here runs on the JavaFX thread; engine calls go to background services.
 */
public class SwapService {
    private static final Logger log = LoggerFactory.getLogger(SwapService.class);
    private static final int MAX_LOG_CHARS = 200_000;
    private static final int MAX_EVENT_CHARS = 600;

    private static SwapService instance;

    private Boolean available;
    //The loaded library; it runs one engine at a time
    private SwapEngine engine;
    //The wallet the engine is for, once asked to start, and once it has started
    private String engineWalletId;
    private String runningWalletId;
    private String runningWalletName;
    private BadbankSwapHost host;
    private boolean starting;
    //Wallet ids by when they were unlocked, with their swap identities
    private final Map<String, byte[]> seeds = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<String> runningSwaps = ConcurrentHashMap.newKeySet();
    private final StringBuilder logText = new StringBuilder();
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Swaps are off.");

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
        return !runningSwaps.isEmpty();
    }

    public boolean isRunningFor(String walletId) {
        return walletId.equals(runningWalletId) && host != null;
    }

    public boolean hasIdentity(String walletId) {
        return seeds.containsKey(walletId);
    }

    public BadbankSwapHost getHost() {
        return host;
    }

    public String getEngineWalletName() {
        return runningWalletName == null ? "another wallet" : runningWalletName;
    }

    public ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
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

    /**
     * Called as a master wallet finishes unlocking, with the key that decrypts it (null when it is not
     * encrypted). Derives the wallet's swap identity and starts the engine if none is running.
     */
    public void walletLoaded(String walletId, Wallet wallet, Key key) {
        if(!isEnabled() || wallet.getChildWallet(StandardAccount.WHIRLPOOL_BADBANK) == null) {
            return;
        }

        Wallet decrypted = wallet;
        try {
            if(wallet.isEncrypted()) {
                if(key == null) {
                    return;
                }
                decrypted = wallet.copy();
                decrypted.decrypt(key);
            }
            seeds.put(walletId, SwapSeed.derive(decrypted.getKeystores().get(0)));
        } catch(Exception e) {
            log.error("Could not derive the swap identity for " + wallet.getMasterName(), e);
            return;
        } finally {
            if(decrypted != wallet) {
                decrypted.clearPrivate();
            }
        }

        if(runningWalletId == null && !starting) {
            start(walletId);
        }
    }

    /** Called when a wallet is locked or removed: its identity is wiped from memory */
    public void walletUnloaded(String walletId) {
        byte[] seed = seeds.remove(walletId);
        if(seed != null) {
            Arrays.fill(seed, (byte)0);
        }
        //A running swap keeps its wallet: it still needs Badbank to sign and to receive a refund
        if(walletId.equals(engineWalletId) && !isSwapActive()) {
            engineWalletId = null;
            runningWalletId = null;
            host = null;
            status.set("Stopped: the wallet was locked.");
        }
    }

    /**
     * Makes sure the engine runs for this wallet. Returns why it cannot, or null when it is running or
     * being started.
     */
    public String ensureRunningFor(String walletId) {
        if(walletId.equals(engineWalletId)) {
            return runningWalletId == null && !starting ? start(walletId) : null;
        }
        if(isSwapActive()) {
            return "A swap is running for " + getEngineWalletName() + ". Swaps for another wallet can start once it finishes.";
        }
        return start(walletId);
    }

    /** Starts the engine for the open wallet whose identity is known, e.g. after swaps were turned on */
    public void startForOpenWallet() {
        if(runningWalletId != null || starting) {
            return;
        }
        Optional<String> walletId;
        synchronized(seeds) {
            walletId = seeds.keySet().stream().findFirst();
        }
        if(walletId.isPresent()) {
            start(walletId.get());
        } else {
            status.set(problem(null));
        }
    }

    private String start(String walletId) {
        String problem = problem(walletId);
        engineWalletId = walletId;
        if(problem != null) {
            status.set(problem);
            return problem;
        }

        WalletForm form = AshigaruGui.get().getWalletForms().get(walletId);
        Wallet badbank = form.getWallet().getChildWallet(StandardAccount.WHIRLPOOL_BADBANK);
        byte[] seed = seeds.get(walletId).clone();
        HostAndPort torProxy = AppServices.getTorProxy();
        List<String> electrumUrls = List.of(Config.get().getServer().getUrl());
        String moneroNode = Config.get().getSwapMoneroNode().trim();
        File dataDir = new File(new File(Storage.getSparrowHome(), "swaps"), SwapSeed.directoryName(seed));
        BadbankSwapHost badbankHost = new BadbankSwapHost(badbank, AshigaruGui.get().getMainStage(), this::onNotification);
        SwapEngine loaded = loadEngine();

        starting = true;
        host = null;
        runningWalletId = null;
        String walletName = form.getWallet().getMasterName();
        status.set("Starting the swap engine for " + walletName + " through Tor…");
        runInBackground(() -> {
            try {
                loaded.start(dataDir, torProxy, seed, badbankHost, electrumUrls, moneroNode);
            } finally {
                Arrays.fill(seed, (byte)0);
            }
            List<String> resumed = new ArrayList<>();
            loaded.resumeAll().forEach(id -> resumed.add(id.getAsString()));
            return resumed;
        }, resumed -> {
            starting = false;
            if(!walletId.equals(engineWalletId)) {
                return;
            }
            host = badbankHost;
            runningWalletId = walletId;
            runningWalletName = walletName;
            runningSwaps.addAll(resumed);
            appendLog("Swap engine running for " + walletName);
            if(!resumed.isEmpty()) {
                appendLog("Resumed unfinished swaps: " + String.join(", ", resumed));
            }
            status.set("Running for " + walletName + ".");
        }, e -> {
            starting = false;
            status.set("Could not start the swap engine: " + e.getMessage());
        });
        return null;
    }

    /** Why the engine cannot start for this wallet now, or null */
    private String problem(String walletId) {
        if(!Config.get().isSwapsEnabled()) {
            return "Swaps are off. Turn them on in Settings → Swaps.";
        }
        if(!isAvailable()) {
            return Network.get() == Network.MAINNET ? "This build does not include the swap engine." : "Swaps run on mainnet only.";
        }
        if(walletId == null || !seeds.containsKey(walletId)) {
            return "Lock and reopen the wallet to swap with it: its swap identity is set up while it unlocks.";
        }
        Server server = Config.get().getServer();
        if(server == null || Config.get().getServerType() == ServerType.BITCOIN_CORE) {
            return "Swaps watch the chain through an Electrum server. Connect Ashigaru to one (a Dojo or Electrum server).";
        }
        String moneroNode = Config.get().getSwapMoneroNode();
        if(moneroNode == null || !isOnionHostAndPort(moneroNode)) {
            return "Set your Monero node's onion address in Settings → Swaps.";
        }
        if(AppServices.getTorProxy() == null) {
            return "Waiting for Tor. Swaps only connect through Tor.";
        }
        return null;
    }

    public static boolean isOnionHostAndPort(String value) {
        try {
            HostAndPort node = HostAndPort.fromString(value.trim());
            return node.hasPort() && node.getHost().endsWith(".onion");
        } catch(IllegalArgumentException e) {
            return false;
        }
    }

    @Subscribe
    public void torReady(TorReadyStatusEvent event) {
        Platform.runLater(() -> {
            if(engineWalletId != null && runningWalletId == null && !starting) {
                start(engineWalletId);
            }
        });
    }

    public void listQuotes(Consumer<List<SwapEngine.Quote>> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(engine::listQuotes, onSuccess, onFailure);
    }

    public void startSwap(SwapEngine.Quote maker, long sats, long lockFee, String xmrAddress, String refundAddress,
                          Consumer<String> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(() -> engine.startSwap(maker, sats, lockFee, xmrAddress, refundAddress), swapId -> {
            runningSwaps.add(swapId);
            appendLog("Swap " + swapId + " started: " + BadbankSwapHost.btc(sats) + " BTC to " + xmrAddress
                    + ", lock fee " + lockFee + " sats, refunds to " + refundAddress);
            onSuccess.accept(swapId);
        }, onFailure);
    }

    public void listSwaps(Consumer<JsonArray> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(engine::listSwaps, onSuccess, onFailure);
    }

    public void refund(String swapId, Consumer<String> onSuccess, Consumer<Throwable> onFailure) {
        runInBackground(() -> engine.cancelAndRefund(swapId), state -> {
            appendLog("Swap " + swapId + " refunded: " + state);
            onSuccess.accept(state);
        }, onFailure);
    }

    private void onNotification(String kind, JsonObject payload) {
        if("swap_stopped".equals(kind)) {
            String swapId = payload.get("swapId").getAsString();
            runningSwaps.remove(swapId);
            appendLog("Swap " + swapId + " stopped. List swaps to see where it ended.");
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
        String text = body == null ? "" : body.toString();
        appendLog(channel + ": " + (text.length() > MAX_EVENT_CHARS ? text.substring(0, MAX_EVENT_CHARS) + "…" : text));
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
