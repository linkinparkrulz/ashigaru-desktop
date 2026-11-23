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
import com.sparrowwallet.sparrow.event.ConnectionEvent;
import com.sparrowwallet.sparrow.event.TorBootStatusEvent;
import com.sparrowwallet.sparrow.event.TorDiedEvent;
import com.sparrowwallet.sparrow.event.TorFailedStatusEvent;
import com.sparrowwallet.sparrow.event.TorReadyStatusEvent;
import com.sparrowwallet.sparrow.gui.AshigaruGui;
import com.sparrowwallet.drongo.log.LogRedaction;
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
import java.util.concurrent.CompletionException;
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
    //Tor stopping by itself this soon after the engine started is put down to the engine
    static final long TOR_LOSS_WINDOW_MILLIS = 90_000;
    static final String PAUSED_FOR_TOR = "Swaps paused: Tor stopped right after the swap engine started, twice in a row. Your wallet stays connected. "
            + "To try again, turn swaps off and on in Settings → Swaps. Please send the log from Tools → Logs.";

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
    //The Bitcoin server the running engine was started with, to notice when Ashigaru connects to another
    private String engineServerUrl;
    //Said once per change, while a running swap keeps the engine on the old server
    private boolean serverChangeWaiting;
    //When the engine last came up, and how many times in a row Tor then stopped by itself within the window
    private long engineStartedAt;
    private int torLostAfterStart;
    private boolean pausedForTor;
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("Off.");
    private final ReadOnlyObjectWrapper<Coin> coin = new ReadOnlyObjectWrapper<>();
    //The swap being set up: the only one Ashigaru answers wallet requests for
    private BadbankSwapHost activeHost;
    private final Set<String> runningSwaps = ConcurrentHashMap.newKeySet();
    //Who each swap of this run is with and for how much, so a maker's refusal is remembered against the right maker
    private record Started(String peerId, long sats) {}
    private final Map<String, Started> startedSwaps = new ConcurrentHashMap<>();
    private final MakerRefusals refusals = new MakerRefusals();
    //Why the approval refused or rejected the maker's final terms, until the swap that stopped for it reports in
    private volatile String pendingRefusal;
    //Swaps that existed when the swap now being set up began, so that a stop event of one of those is not taken for this setup's
    private volatile Set<String> swapsBeforeSetup = Set.of();
    private final Set<String> listedSwapIds = ConcurrentHashMap.newKeySet();
    //Swaps the engine last said were unfinished, kept on disk so the top bar can warn about them while the engine is not running
    private volatile UnfinishedSwaps unfinished = new UnfinishedSwaps(null);
    //Each swap's latest step, as reported by the engine; the swap being set up is the latest started
    private final Map<String, SwapProgress.Status> progress = new ConcurrentHashMap<>();
    //Each swap's latest timelock state, from the engine's TimelockChange events: blocks left until cancel or punish
    private final Map<String, SwapTimelock> timelocks = new ConcurrentHashMap<>();
    //The Swaps screen, while it can show a swap's reviews
    private volatile ReviewPresenter reviewPresenter;
    //What was agreed for each swap of this identity: amounts, address, maker. Opened when the engine starts.
    private volatile SwapDetails.Store detailsStore;
    //Earlier identities' records, read only: a finished swap stays in the list under the identity it ran as
    private volatile List<SwapDetails.Store> pastDetailsStores = List.of();
    private String currentSwapId;
    private final StringBuilder logText = new StringBuilder();
    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> swapListeners = new CopyOnWriteArrayList<>();

    public static synchronized SwapService get() {
        if(instance == null) {
            instance = new SwapService();
            instance.unfinished = new UnfinishedSwaps(new File(Storage.getSparrowHome(), "swaps"));
            EventManager.get().register(instance);
        }
        return instance;
    }

    /** How many swaps are known to be unfinished: they hold BTC, and only a running engine can complete or refund them */
    public int unfinishedCount() {
        return unfinished.count();
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

    /** What makers have refused during this run, so their offers can be left out for the coin they refused */
    public MakerRefusals getRefusals() {
        return refusals;
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

    /** What was agreed for a swap, or null when no identity has a record of it */
    public SwapDetails getDetails(String swapId) {
        SwapDetails.Store store = detailsStore;
        SwapDetails found = store == null ? null : store.get(swapId);
        for(SwapDetails.Store past : pastDetailsStores) {
            if(found != null) {
                break;
            }
            found = past.get(swapId);
        }
        return found;
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

    /**
     * What a bug report needs to know about swaps: whether they are on, whether the engine loaded, and what the
     * Swaps screen says. Paths under the user's home folder are shortened so the report does not carry the user name, and the status (which can
     * carry an engine error) is redacted like the log file, amounts included.
     */
    public String diagnostics() {
        String home = System.getProperty("user.home");
        String text = "Swaps:    " + (Config.get().isSwapsEnabled() ? "on" : "off") + ", engine "
                + (engine != null ? "loaded" : loadError != null ? "not loaded (" + loadError + ")" : "not loaded yet")
                + (running.get() ? ", running" : ", not running") + "\n"
                + "          " + status.get() + "\n";
        return LogRedaction.redactAmounts(home == null || home.isEmpty() ? text : text.replace(home, "~"));
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
        if(pausedForTor) {
            status.set(PAUSED_FOR_TOR);
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
        //Turning swaps off and on again is how the user asks for another try
        pausedForTor = false;
        torLostAfterStart = 0;
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
        if(AppServices.getTorProxyIfUp() == null) {
            return "Waiting for Tor. Swaps only connect through Tor.";
        }
        return null;
    }

    /**
     * Starts (or replaces) the engine. With {@code renewIdentity}, a fresh identity is used: after a
     * swap finished, or at start when the last identity's swaps are all finished.
     */
    private void start(boolean renewIdentity) {
        HostAndPort torProxy = AppServices.getTorProxyIfUp();
        engineProxy = torProxy;
        String serverUrl = Config.get().getServer().getUrl();
        engineServerUrl = serverUrl;
        serverChangeWaiting = false;
        List<String> electrumUrls = List.of(serverUrl);
        List<String> selectedNodes = MoneroNodes.getSelected();
        List<String> moneroNodes = MoneroNodes.forEngine(selectedNodes);
        //Said as the engine will use them: ticked public nodes are left out when the user's own node is ticked
        String nodeUse = MoneroNodes.describe(selectedNodes);
        File swapsDir = new File(Storage.getSparrowHome(), "swaps");
        SwapEngine loaded = loadEngine();

        starting = true;
        long startedAt = System.currentTimeMillis();
        status.set(renewIdentity ? "Taking a fresh identity for the next swap…" : "Starting through Tor…");
        runInBackground(() -> {
            boolean renewed = false;
            SwapIdentity identity = renewIdentity ? SwapIdentity.renew(swapsDir) : SwapIdentity.current(swapsDir);
            loaded.start(identity.getDataDir(), torProxy, identity.getSeed(), this, electrumUrls, moneroNodes,
                    SwapIdentity.pastDataDirs(swapsDir, identity.getDataDir()));
            List<String> resumed = new ArrayList<>();
            loaded.resumeAll().forEach(id -> resumed.add(id.getAsString()));
            JsonArray listed = loaded.listSwaps();
            boolean used = usedByThisIdentity(listed);
            unfinished.replaceAll(notFinishedIds(listed));
            //Identity already used and nothing left to finish: makers have seen it, so take a new one. Not in a run that just recovered
            //its identity from a damaged file: whatever went wrong should be looked at before the identity is changed.
            if(!renewIdentity && !identity.wasRecovered() && resumed.isEmpty() && used) {
                identity = SwapIdentity.renew(swapsDir);
                loaded.start(identity.getDataDir(), torProxy, identity.getSeed(), this, electrumUrls, moneroNodes,
                    SwapIdentity.pastDataDirs(swapsDir, identity.getDataDir()));
                renewed = true;
            }
            //Identities left behind whose swaps were read and are all finished no longer need their seeds. Decided folder by folder:
            //a folder whose database could not be read has no entries in the list, so it is not retired.
            if((renewIdentity || renewed) && resumed.isEmpty()) {
                SwapIdentity.retire(swapsDir, identity.getDataDir(), finishedFolders(listed));
            }
            detailsStore = new SwapDetails.Store(identity.getDataDir());
            pastDetailsStores = SwapIdentity.pastDataDirs(swapsDir, identity.getDataDir()).stream().map(SwapDetails.Store::new).toList();
            return resumed;
        }, resumed -> {
            starting = false;
            //Swaps were turned off, or Tor stopped by itself for the second time, while this engine was starting: do not keep it.
            //stop() cannot do this, as it finds nothing running and returns, so the engine that just came up is shut down here.
            Arrival arrival = arrival(Config.get().isSwapsEnabled(), pausedForTor);
            if(arrival != Arrival.KEEP) {
                runInBackground(() -> {
                    loaded.shutdown();
                    return null;
                }, ignored -> appendLog("Swap engine stopped"), e -> {});
                if(arrival == Arrival.SHUT_DOWN_OFF) {
                    pausedForTor = false;
                    torLostAfterStart = 0;
                    status.set("Off.");
                } else {
                    status.set(PAUSED_FOR_TOR);
                }
                return;
            }
            runningSwaps.addAll(resumed);
            if(!resumed.isEmpty()) {
                currentSwapId = resumed.get(resumed.size() - 1);
            }
            running.set(true);
            engineStartedAt = System.currentTimeMillis();
            status.set("On. Using " + nodeUse + ".");
            appendLog("Swap engine running with " + nodeUse + " (ready in " + (System.currentTimeMillis() - startedAt) / 1000 + " s)");
            if(!resumed.isEmpty()) {
                appendLog("Resumed unfinished swaps: " + String.join(", ", resumed));
            }
            swapListeners.forEach(Runnable::run);
            //Tor may have moved or gone while the engine was starting, which followTor had to leave until now
            followTor();
            followServer();
        }, e -> {
            starting = false;
            //The engine may have come up before the step that failed, and nothing else holds it: stop it, ignoring a failure
            runInBackground(() -> {
                loaded.shutdown();
                return null;
            }, ignored -> {}, ignored -> {});
            status.set("Could not start: " + e.getMessage());
            if(!Objects.equals(AppServices.getTorProxyIfUp(), engineProxy)) {
                followTor();
            }
        });
    }

    /** Whether the engine's record of a swap says it is finished. A record with no such field counts as unfinished: it is not safe to assume it is over. */
    public static boolean isComplete(JsonObject swap) {
        return swap.has("complete") && !swap.get("complete").isJsonNull() && swap.get("complete").getAsBoolean();
    }

    /** Whether the engine's record says the swap is running; no field means it is not */
    public static boolean isRunning(JsonObject swap) {
        return swap.has("running") && !swap.get("running").isJsonNull() && swap.get("running").getAsBoolean();
    }

    /** Why this amount cannot be swapped with this maker, or null. The same limits the offers screen shows. */
    static String amountProblem(SwapEngine.Quote maker, long sats, long maxSats) {
        if(sats < maker.minSats() || sats > maker.maxSats()) {
            return "This maker takes " + BadbankSwapHost.btc(maker.minSats()) + " to " + BadbankSwapHost.btc(maker.maxSats()) + " BTC; this coin swaps "
                    + BadbankSwapHost.btc(sats) + " BTC now, at the current fee rate.";
        }
        if(sats > maxSats) {
            return "Swaps are limited to " + BadbankSwapHost.btc(maxSats) + " BTC. Change the maximum in Settings → Swaps.";
        }
        return null;
    }

    /** Whether a stopped swap is the one being set up: there is a setup, and the swap was not there before it began */
    static boolean isOfThisSetup(boolean setupActive, Set<String> swapsBeforeSetup, String swapId) {
        return setupActive && !swapsBeforeSetup.contains(swapId);
    }

    /** What is written to the log file for an engine line: redacted, as the file is the one that gets sent around */
    static String forLogFile(String line) {
        return "Swap engine: " + LogRedaction.redactAmounts(line);
    }

    /** A peer id as its last characters only */
    static String shortPeer(String peerId) {
        return peerId == null || peerId.length() <= 10 ? peerId : "\u2026" + peerId.substring(peerId.length() - 6);
    }

    /** The ids of the swaps in the engine's list that are not finished */
    static List<String> notFinishedIds(JsonArray swaps) {
        List<String> ids = new ArrayList<>();
        for(JsonElement element : swaps) {
            if(element.isJsonObject() && element.getAsJsonObject().has("swapId") && !isComplete(element.getAsJsonObject())) {
                ids.add(element.getAsJsonObject().get("swapId").getAsString());
            }
        }
        return ids;
    }

    /**
     * The identity folders (by name) whose swaps the engine read and found all finished. A folder counts only when at least one entry names it
     * as its {@code dataDir} and every entry that names it is complete; a folder whose database could not be read has no entries, and an engine
     * too old to name folders gives none, so neither is ever retired.
     */
    static java.util.Set<String> finishedFolders(JsonArray swaps) {
        java.util.Map<String, Boolean> folders = new java.util.HashMap<>();
        for(JsonElement element : swaps) {
            if(!element.isJsonObject()) {
                continue;
            }
            JsonObject swap = element.getAsJsonObject();
            if(!swap.has("dataDir") || swap.get("dataDir").isJsonNull()) {
                continue;
            }
            String folder = new File(swap.get("dataDir").getAsString()).getName();
            folders.merge(folder, isComplete(swap), Boolean::logicalAnd);
        }
        java.util.Set<String> finished = new java.util.TreeSet<>();
        folders.forEach((folder, done) -> {
            if(done) {
                finished.add(folder);
            }
        });
        return finished;
    }

    /** An address as its first and last characters, enough to recognise it and not to use it */
    static String shortAddress(String address) {
        return address == null || address.length() <= 12 ? address : address.substring(0, 4) + "\u2026" + address.substring(address.length() - 4);
    }

    /** What to do with an engine whose start has just finished */
    enum Arrival { KEEP, SHUT_DOWN_OFF, SHUT_DOWN_PAUSED }

    static Arrival arrival(boolean swapsEnabled, boolean pausedForTor) {
        if(!swapsEnabled) {
            return Arrival.SHUT_DOWN_OFF;
        }
        return pausedForTor ? Arrival.SHUT_DOWN_PAUSED : Arrival.KEEP;
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

    /** Tor's daemon stopped without being asked to. Ashigaru restarts it; this decides whether the engine may follow. */
    @Subscribe
    public void torDied(TorDiedEvent event) {
        Platform.runLater(() -> {
            if(running.get() || starting) {
                torLostAfterStart = diedSoonAfterStart(engineStartedAt, System.currentTimeMillis(), starting) ? torLostAfterStart + 1 : 0;
                if(torLostAfterStart >= 2) {
                    pausedForTor = true;
                    appendLog(PAUSED_FOR_TOR);
                }
            }
            followTor();
            if(pausedForTor) {
                status.set(PAUSED_FOR_TOR);
            }
        });
    }

    /** Whether Tor stopping now is close enough to the engine's start to be put down to the engine */
    static boolean diedSoonAfterStart(long engineStartedAt, long now, boolean starting) {
        return starting || engineStartedAt > 0 && now - engineStartedAt < TOR_LOSS_WINDOW_MILLIS;
    }

    /** Ashigaru connected to its Bitcoin server, which may be another one than the engine was started with */
    @Subscribe
    public void newConnection(ConnectionEvent event) {
        Platform.runLater(this::followServer);
    }

    /**
     * Keeps the engine on the Bitcoin server Ashigaru is connected to. The engine is given the server
     * when it starts, so after a change in Settings → Server it kept asking the old one and reported
     * a server that Ashigaru itself was connected to as unreachable. A running swap is not restarted
     * under: it keeps the server it started with, and the engine switches when the swap ends.
     */
    private void followServer() {
        if(starting || !Config.get().isSwapsEnabled()) {
            return;
        }
        if(!running.get()) {
            startIfEnabled();
            return;
        }
        Server server = Config.get().getServer();
        if(server == null || Config.get().getServerType() == ServerType.BITCOIN_CORE || !serverChanged(engineServerUrl, server.getUrl())) {
            return;
        }
        if(isSwapActive()) {
            if(!serverChangeWaiting) {
                serverChangeWaiting = true;
                appendLog("The Bitcoin server changed to " + server.getUrl() + ". The swap engine stays on " + engineServerUrl
                        + " while a swap is running, and switches when it ends.");
            }
            return;
        }
        appendLog("The Bitcoin server changed to " + server.getUrl() + "; restarting the swap engine to use it");
        running.set(false);
        start(false);
    }

    /** A Java system property that names a native swap engine library to load instead of the bundled one, for developers */
    static final String ENGINE_PROPERTY = "ashigaru.swap.engine";
    /** The same, as an environment variable */
    static final String ENGINE_ENVIRONMENT = "ASHIGARU_SWAP_ENGINE";

    /**
     * The library to load instead of the bundled engine, or null for the bundled one. It comes only from how Ashigaru was started (a system
     * property or an environment variable), never from the config file: a file anyone who can write to the home folder could change must not
     * be able to make Ashigaru load a native library of their choosing, with the swap keys and Tor access that come with it.
     */
    static File engineOverride(File fromConfigFile, String property, String environment) {
        if(fromConfigFile != null) {
            log.warn("The swapEngineLibrary setting in the config file is ignored. Use -D" + ENGINE_PROPERTY + " or " + ENGINE_ENVIRONMENT + " to load another engine.");
        }
        String path = property != null && !property.isBlank() ? property : environment;
        if(path == null || path.isBlank()) {
            return null;
        }
        log.warn("Loading the swap engine from " + path + " instead of the bundled one");
        return new File(path.trim());
    }

    /** The engine follows a new Tor proxy by restarting, which must not happen while a swap is being set up */
    static boolean restartAllowed(boolean swapBeingSetUp) {
        return !swapBeingSetUp;
    }

    /**
     * Whether a swap is in its setup: from the moment it is started until the lock is signed or the keys held for it are wiped. The host stays
     * active after that (a refusal or a stop still belongs to it), but the exchange with the maker is over and a restart only resumes the swap.
     */
    static boolean isSettingUp(BadbankSwapHost host) {
        return host != null && !host.isSetupOver();
    }

    /** Whether the engine, started with one server, should be restarted for the server Ashigaru uses now */
    static boolean serverChanged(String engineUrl, String currentUrl) {
        return currentUrl != null && !currentUrl.equals(engineUrl);
    }

    /** The Bitcoin server the engine is using, or null when it is not running */
    public String getEngineServerUrl() {
        return running.get() ? engineServerUrl : null;
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
        HostAndPort proxy = AppServices.getTorProxyIfUp();
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
            if(!restartAllowed(isSettingUp(activeHost))) {
                //Restarting now would stop the swap being set up, in the middle of the maker's exchange, so wait until the lock is signed or
                //the keys are wiped. Not longer: a swap whose lock is out must not sit on a dead Tor proxy while its timelocks run.
                appendLog("Tor restarted on " + proxy + ". The swap engine stays as it is while a swap is being set up, and follows Tor when that ends.");
                return;
            }
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

        Set<String> before = new HashSet<>(progress.keySet());
        before.addAll(startedSwaps.keySet());
        before.addAll(listedSwapIds);
        swapsBeforeSetup = before;
        BadbankSwapHost host = new BadbankSwapHost(chosen.badbank(), chosen.outpoint(), AshigaruGui.get().getMainStage());
        host.setPresenterSource(this::getReviewPresenter);
        host.setSwapContext(xmrAddress, SwapReview.shortId(maker.peerId()));
        //The final terms are checked against this offer before the user is asked to approve anything
        host.setQuote(maker);
        pendingRefusal = null;
        host.setRefusalListener(reason -> pendingRefusal = reason);
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
        //The fee, and so the amount, moves with the fee rate: what is swapped is what is checked here, not what the offers screen last drew
        String amountProblem = amountProblem(maker, sats, Config.get().getSwapMaxSats());
        if(amountProblem != null) {
            onFailure.accept(new IllegalStateException(amountProblem));
            return;
        }
        host.setAgreedAmounts(sats, fee);
        String refundAddress = host.getFreshAddress();
        //A Tor move that waited for the setup is followed as soon as the setup is over, not when the whole swap ends
        host.setSetupOverListener(() -> Platform.runLater(this::followTor));
        activeHost = host;
        //The wallet password is asked once, here: the maker wants proof of the coin during the setup, and the
        //lock is signed straight after, so the keys are held (and then wiped) until the lock is signed or the swap fails
        host.unlock().whenComplete((ignored, unlockFailure) -> Platform.runLater(() -> {
            if(unlockFailure != null) {
                activeHost = null;
                Platform.runLater(this::followTor);  //Tor may have moved while this was being set up
                onFailure.accept(unlockFailure instanceof CompletionException && unlockFailure.getCause() != null ? unlockFailure.getCause() : unlockFailure);
                return;
            }
            runInBackground(() -> engine.startSwap(maker, sats, fee, xmrAddress, refundAddress), swapId -> {
                runningSwaps.add(swapId);
                unfinished.add(swapId);
                startedSwaps.put(swapId, new Started(maker.peerId(), sats));
                currentSwapId = swapId;
                saveDetails(swapId, SwapDetails.started(sats, fee, xmrAddress, SwapReview.shortId(maker.peerId())));
                progress.putIfAbsent(swapId, new SwapProgress.Status(SwapProgress.Step.NODES, null, java.time.Instant.now(), false));
                coin.set(null);
                //Addresses are shortened: a shared log must not tie an XMR address to the exact amount of a lock that can be found on chain
                //No amount, wallet, fee, refund address or maker address: the log is shared, and these would tie the swap to its transactions on chain
                appendLog("Swap " + swapId + " started to XMR address " + shortAddress(xmrAddress) + ", with maker " + shortPeer(maker.peerId())
                        + (maker.version() == null ? "" : " v" + maker.version()));
                swapListeners.forEach(Runnable::run);
                onSuccess.accept(swapId);
            }, e -> {
                host.release();
                activeHost = null;
                Platform.runLater(this::followTor);  //Tor may have moved while this was being set up
                onFailure.accept(e);
            });
        }));
    }

    /** Asks each Monero node for its height through Tor, so a dead one is found before a swap needs it */
    public void checkNodes(Consumer<JsonArray> onSuccess, Consumer<Throwable> onFailure) {
        if(!running.get()) {
            onFailure.accept(new IllegalStateException("Swaps are not running"));
            return;
        }
        runInBackground(engine::checkNodes, onSuccess, onFailure);
    }

    /** How the engine's check names the Bitcoin server, which it asks before the Monero nodes */
    public static final String BITCOIN_SERVER = "Bitcoin server (Electrum)";

    /**
     * Why a swap was not started, from the lines {@link #unreachable} found. The Bitcoin server comes first: the lock is
     * published and watched through it, so a swap that cannot reach it would stop after the user had approved.
     */
    public static String notStartedMessage(List<String> unreachable) {
        return notStartedMessage(unreachable, null);
    }

    /** As above, naming the server the engine asked, so one that differs from Settings → Server shows */
    public static String notStartedMessage(List<String> unreachable, String engineServerUrl) {
        boolean bitcoin = unreachable.stream().anyMatch(line -> line.startsWith(BITCOIN_SERVER));
        long monero = unreachable.stream().filter(line -> !line.startsWith(BITCOIN_SERVER)).count();
        String lines = String.join("\n", unreachable);
        if(bitcoin) {
            return "Not started: the swap engine cannot reach your Bitcoin server" + (engineServerUrl == null ? "" : " (" + engineServerUrl + ")") + ". The BTC lock is published through it, so a swap started now would "
                    + "stop after you approve. Check the server in Settings → Server, then try again. A server on your own network or this computer "
                    + "is reached directly; any other server is reached through Tor.\n" + lines;
        }
        return "Not started: " + (monero == 1 ? "a Monero node" : monero + " Monero nodes")
                + " did not answer. Untick it in Settings → Swaps (keeping at least two public nodes) or add your own node, then try again.\n" + lines;
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
            if(swap.has("swapId")) {
                listedSwapIds.add(swap.get("swapId").getAsString());
            }
            if(swap.has("past") && !swap.get("past").isJsonNull() && swap.get("past").getAsBoolean() || !swap.has("swapId")) {
                continue;
            }
            String swapId = swap.get("swapId").getAsString();
            SwapProgress.Status known = progress.get(swapId);
            boolean running = isRunning(swap);
            boolean complete = isComplete(swap);
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
        unfinished.replaceAll(notFinishedIds(swaps));
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
        if("approve".equals(kind)) {
            //The user is being asked: show it as the swap's current step
            Platform.runLater(() -> {
                String swapId = currentSwapId;
                if(swapId != null) {
                    SwapProgress.Status previous = progress.get(swapId);
                    java.time.Instant since = previous != null && previous.step() == SwapProgress.Step.APPROVAL ? previous.since() : java.time.Instant.now();
                    progress.put(swapId, new SwapProgress.Status(SwapProgress.Step.APPROVAL,
                            "Approve or reject the final terms", since, false));
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
                log.info(forLogFile(message));
            } else {
                appendLog(message);
            }
            return;
        }
        if("swap_stopped".equals(kind)) {
            String swapId = payload.get("swapId").getAsString();
            //A swap that stopped because its final terms were refused says that, not the engine's "rejected"
            //Any swap can stop: one that was already there before this setup began (an old swap being refunded, say) says nothing
            //about the setup, which keeps its refusal and its keys
            boolean ofThisSetup = isOfThisSetup(activeHost != null, swapsBeforeSetup, swapId);
            String ourRefusal = ofThisSetup ? pendingRefusal : null;
            if(ofThisSetup) {
                pendingRefusal = null;
            }
            if(ourRefusal != null) {
                payload.addProperty("error", ourRefusal);
            }
            runningSwaps.remove(swapId);
            if(ofThisSetup) {
                activeHost.release();
                activeHost = null;
                Platform.runLater(this::followTor);  //Tor may have moved while this was being set up
            }
            SwapProgress.Status status = SwapProgress.stopped(payload, progress.get(swapId), java.time.Instant.now());
            progress.put(swapId, status);
            Started started = startedSwaps.get(swapId);
            if(started != null && payload.has("error")) {
                MakerRefusals.parse(payload.get("error").getAsString(), started.sats(), java.time.Instant.now())
                        .ifPresent(refusal -> refusals.record(started.peerId(), refusal));
            }
            appendLog("Swap " + swapId + " stopped" + (payload.has("error") ? ": " + payload.get("error").getAsString()
                    : payload.has("state") ? " at " + payload.get("state").getAsString() : "") + ".");
            swapListeners.forEach(Runnable::run);
            //A server change that waited for this swap can go ahead now; the restart takes a new identity if due
            followServer();
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
                if(status.finished()) {
                    unfinished.remove(swapId);
                }
                //The swap log gets a readable line per step; the raw event stays in the log file
                log.info(forLogFile(raw));
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
            boolean anyUnfinished = false;
            for(JsonElement swap : swaps) {
                if(!isComplete(swap.getAsJsonObject())) {
                    anyUnfinished = true;
                }
            }
            if(!anyUnfinished && !isSwapActive() && problem() == null) {
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
        //The in-app swap log keeps the line as it is; the file, which people send around, gets it redacted
        log.info(forLogFile(line));
        logListeners.forEach(listener -> listener.accept(stamped));
    }

    private SwapEngine loadEngine() {
        if(engine == null) {
            try {
                File override = engineOverride(Config.get().getSwapEngineLibrary(), System.getProperty(ENGINE_PROPERTY), System.getenv(ENGINE_ENVIRONMENT));
                engine = override != null ? SwapEngine.load(override) : SwapEngine.loadBundled();
            } catch(SwapEngineException e) {
                loadError = e.getMessage();
                //In the swap engine's log too, which is what a bug report carries
                appendLog("The swap engine could not be loaded: " + e.getMessage());
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
