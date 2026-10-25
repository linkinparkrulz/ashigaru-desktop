package com.sparrowwallet.sparrow.gui;

import com.google.common.net.HostAndPort;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.ServerType;
import com.sparrowwallet.sparrow.swap.BadbankSwapHost;
import com.sparrowwallet.sparrow.swap.SwapEngine;
import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import com.sparrowwallet.sparrow.swap.SwapSeed;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Platform;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Developer preview of Badbank to XMR swaps through the embedded eigenwallet engine, on mainnet and
 * over Ashigaru's Tor only. The engine and its log outlive this page, so leaving Tools does not stop
 * a swap; quitting Ashigaru asks first while one is running.
 */
public class SwapEngineController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(SwapEngineController.class);
    private static final int MAX_LOG_CHARS = 200_000;
    private static final int MAX_EVENT_CHARS = 600;

    //One engine per app run: the native library keeps a single engine
    private static SwapEngine engine;
    private static BadbankSwapHost host;
    private static File libraryFile;
    private static String engineWallet;
    private static final StringBuilder logText = new StringBuilder();
    private static final Set<String> runningSwaps = Collections.synchronizedSet(new LinkedHashSet<>());
    private static SwapEngineController current;

    @FXML private Button chooseLibraryBtn;
    @FXML private ComboBox<WalletForm> walletCombo;
    @FXML private Button startBtn;
    @FXML private Button refreshBtn;
    @FXML private ComboBox<Quote> makerCombo;
    @FXML private TextField amountField;
    @FXML private TextField xmrAddressField;
    @FXML private Button swapBtn;
    @FXML private Button listSwapsBtn;
    @FXML private TextField swapIdField;
    @FXML private Button refundBtn;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;

    /** True while a swap runs: quitting then leaves it unattended with timelocks running */
    public static boolean isSwapActive() {
        return !runningSwaps.isEmpty();
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        current = this;
        walletCombo.getItems().setAll(AshigaruGui.get().getWalletForms().values().stream()
                .filter(form -> form.getWallet().isMasterWallet()).toList());
        walletCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(WalletForm form) {
                return form == null ? "" : form.getWallet().getMasterName();
            }

            @Override
            public WalletForm fromString(String string) {
                return null;
            }
        });
        makerCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Quote quote) {
                return quote == null ? "" : String.format(Locale.ROOT, "%s…  %s BTC/XMR  %s–%s BTC",
                        quote.peerId().substring(0, Math.min(12, quote.peerId().length())),
                        btc(quote.priceSats()), btc(quote.minSats()), btc(quote.maxSats()));
            }

            @Override
            public Quote fromString(String string) {
                return null;
            }
        });
        logArea.setText(logText.toString());
        logArea.positionCaret(logArea.getLength());

        if(engine != null) {
            setEngineRunning();
            statusLabel.setText("Engine running for " + engineWallet + ". Makers keep being discovered in the background.");
        } else if(libraryFile != null) {
            startBtn.setDisable(false);
            statusLabel.setText("Library: " + libraryFile.getAbsolutePath());
        }
    }

    @FXML
    private void onChooseLibrary() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the swap engine library");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Native library", "*.so", "*.dylib", "*.dll"));
        File chosen = chooser.showOpenDialog(window());
        if(chosen != null) {
            libraryFile = chosen;
            startBtn.setDisable(engine != null);
            statusLabel.setText("Library: " + chosen.getAbsolutePath());
        }
    }

    @FXML
    private void onStart() {
        String problem = startProblem();
        if(problem != null) {
            statusLabel.setText(problem);
            return;
        }

        WalletForm form = walletCombo.getValue();
        Wallet master = form.getWallet();
        Wallet badbank = master.getChildWallet(StandardAccount.WHIRLPOOL_BADBANK);
        HostAndPort torProxy = AppServices.getTorProxy();
        List<String> electrumUrls = List.of(Config.get().getServer().getUrl());
        String moneroNode = Config.get().getSwapMoneroNode().trim();

        withDecrypted(master, decrypted -> {
            byte[] seed;
            try {
                seed = SwapSeed.derive(decrypted.getKeystores().get(0));
            } catch(Exception e) {
                log.error("Could not derive the swap seed", e);
                statusLabel.setText("Could not derive the swap identity: " + e.getMessage());
                return;
            } finally {
                if(decrypted != master) {
                    decrypted.clearPrivate();
                }
            }

            BadbankSwapHost badbankHost = new BadbankSwapHost(badbank, window(), SwapEngineController::onNotification);
            File dataDir = new File(new File(Storage.getSparrowHome(), "swaps"), SwapSeed.directoryName(seed));
            File library = libraryFile;
            startBtn.setDisable(true);
            chooseLibraryBtn.setDisable(true);
            walletCombo.setDisable(true);
            statusLabel.setText("Starting the engine through Tor at " + torProxy + "…");
            runInBackground(() -> {
                SwapEngine loaded = SwapEngine.load(library);
                loaded.start(dataDir, torProxy, seed, badbankHost, electrumUrls, moneroNode);
                Arrays.fill(seed, (byte)0);
                List<String> resumed = new ArrayList<>();
                loaded.resumeAll().forEach(id -> resumed.add(id.getAsString()));
                return new Started(loaded, resumed);
            }, result -> {
                engine = result.engine();
                host = badbankHost;
                engineWallet = master.getMasterName();
                List<String> resumed = result.resumed();
                runningSwaps.addAll(resumed);
                setEngineRunning();
                appendLog("Engine started for " + engineWallet + ", data in " + dataDir);
                if(!resumed.isEmpty()) {
                    appendLog("Resumed unfinished swaps: " + String.join(", ", resumed));
                }
                statusLabel.setText("Engine running. Makers are discovered over the next minute or two - refresh to see quotes.");
            }, e -> {
                startBtn.setDisable(false);
                chooseLibraryBtn.setDisable(false);
                walletCombo.setDisable(false);
                statusLabel.setText("Could not start the engine: " + e.getMessage());
            });
        });
    }

    private String startProblem() {
        if(libraryFile == null) {
            return "Choose the engine library first.";
        }
        if(Network.get() != Network.MAINNET) {
            return "Swaps run on mainnet only.";
        }
        if(AppServices.getTorProxy() == null) {
            return "Tor is not running. The swap engine only connects through Tor - connect first, then start it.";
        }
        WalletForm form = walletCombo.getValue();
        if(form == null) {
            return "Choose the wallet whose Badbank funds the swaps.";
        }
        if(form.getWallet().getChildWallet(StandardAccount.WHIRLPOOL_BADBANK) == null) {
            return "This wallet has no Badbank account.";
        }
        Server server = Config.get().getServer();
        if(Config.get().getServerType() == ServerType.BITCOIN_CORE || server == null) {
            return "The swap engine watches the chain through an Electrum server. Connect Ashigaru to one (a Dojo or Electrum server) first.";
        }
        String moneroNode = Config.get().getSwapMoneroNode();
        if(moneroNode == null || moneroNode.isBlank()) {
            return "Set swapMoneroNode in the config file to your Monero node's onion host:port.";
        }
        try {
            HostAndPort node = HostAndPort.fromString(moneroNode.trim());
            if(!node.hasPort() || !node.getHost().endsWith(".onion")) {
                return "swapMoneroNode must be an onion host:port, reached through Tor.";
            }
        } catch(IllegalArgumentException e) {
            return "swapMoneroNode is not a host:port.";
        }
        return null;
    }

    @FXML
    private void onRefresh() {
        runInBackground(engine::listQuotes, quotes -> {
            Quote selected = makerCombo.getValue();
            makerCombo.getItems().setAll(quotes);
            if(selected != null) {
                quotes.stream().filter(q -> q.peerId().equals(selected.peerId())).findFirst().ifPresent(makerCombo::setValue);
            }
            statusLabel.setText(quotes.size() + " maker" + (quotes.size() == 1 ? "" : "s") + " with quotes so far. Badbank holds "
                    + btc(host.getBalance()) + " BTC.");
        }, e -> statusLabel.setText("Could not list quotes: " + e.getMessage()));
    }

    @FXML
    private void onStartSwap() {
        Quote maker = makerCombo.getValue();
        if(maker == null) {
            statusLabel.setText("Choose a maker.");
            return;
        }
        if(!runningSwaps.isEmpty()) {
            statusLabel.setText("A swap is already running; one at a time.");
            return;
        }

        long sats;
        try {
            sats = new BigDecimal(amountField.getText().trim()).movePointRight(8).longValueExact();
        } catch(ArithmeticException | NumberFormatException e) {
            statusLabel.setText("Enter the amount in BTC, at most 8 decimal places.");
            return;
        }
        long maxSats = Config.get().getSwapMaxSats();
        if(sats < maker.minSats() || sats > maker.maxSats()) {
            statusLabel.setText("This maker takes between " + btc(maker.minSats()) + " and " + btc(maker.maxSats()) + " BTC.");
            return;
        }
        if(sats > maxSats) {
            statusLabel.setText("The experimental engine swaps at most " + btc(maxSats) + " BTC (swapMaxSats in the config file).");
            return;
        }

        String xmrAddress = xmrAddressField.getText().trim();
        if(!isMainnetMoneroAddress(xmrAddress)) {
            statusLabel.setText("Enter a mainnet Monero address: 95 characters, starting with 4 or 8.");
            return;
        }

        long lockFee;
        try {
            lockFee = host.estimateLockFee(sats);
        } catch(Exception e) {
            statusLabel.setText("Badbank cannot fund this swap: " + e.getMessage());
            return;
        }
        String refundAddress = host.getFreshAddress();

        swapBtn.setDisable(true);
        statusLabel.setText("Setting up the swap with the maker…");
        runInBackground(() -> engine.startSwap(maker, sats, lockFee, xmrAddress, refundAddress), swapId -> {
            runningSwaps.add(swapId);
            swapIdField.setText(swapId);
            swapBtn.setDisable(false);
            appendLog("Swap " + swapId + " started: " + btc(sats) + " BTC to " + xmrAddress + ", lock fee " + lockFee
                    + " sats, refunds to " + refundAddress);
            statusLabel.setText("Swap running. Ashigaru asks before signing and again before locking anything.");
        }, e -> {
            swapBtn.setDisable(false);
            statusLabel.setText("Could not start the swap: " + e.getMessage());
        });
    }

    @FXML
    private void onListSwaps() {
        runInBackground(engine::listSwaps, swaps -> {
            if(swaps.isEmpty()) {
                appendLog("No swaps yet.");
            }
            for(JsonElement element : swaps) {
                JsonObject swap = element.getAsJsonObject();
                appendLog("Swap " + swap.get("swapId").getAsString() + ": " + swap.get("state").getAsString()
                        + (swap.get("running").getAsBoolean() ? " (running)" : "")
                        + (swap.get("complete").getAsBoolean() ? " (finished)" : ""));
            }
        }, e -> statusLabel.setText("Could not list swaps: " + e.getMessage()));
    }

    @FXML
    private void onRefund() {
        String swapId = swapIdField.getText().trim();
        if(swapId.isEmpty()) {
            statusLabel.setText("Enter the id of the swap to refund.");
            return;
        }
        refundBtn.setDisable(true);
        statusLabel.setText("Cancelling and refunding " + swapId + "…");
        runInBackground(() -> engine.cancelAndRefund(swapId), state -> {
            refundBtn.setDisable(false);
            appendLog("Swap " + swapId + " refunded: " + state);
            statusLabel.setText("Refund published to Badbank.");
        }, e -> {
            refundBtn.setDisable(false);
            statusLabel.setText("Could not refund: " + e.getMessage());
        });
    }

    private static void onNotification(String kind, JsonObject payload) {
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
        //Quote polling and log lines are frequent and already reflected elsewhere
        if("CliLog".equals(channel) || "P2P".equals(channel) || "BackgroundProgress".equals(channel)) {
            return;
        }
        String text = body == null ? "" : body.toString();
        appendLog(channel + ": " + (text.length() > MAX_EVENT_CHARS ? text.substring(0, MAX_EVENT_CHARS) + "…" : text));
    }

    private static void appendLog(String line) {
        String stamped = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + line + System.lineSeparator();
        logText.append(stamped);
        if(logText.length() > MAX_LOG_CHARS) {
            logText.delete(0, logText.length() - MAX_LOG_CHARS);
        }
        log.info("Swap engine: " + line);
        if(current != null && current.logArea.getScene() != null) {
            current.logArea.appendText(stamped);
        }
    }

    private void setEngineRunning() {
        startBtn.setDisable(true);
        chooseLibraryBtn.setDisable(true);
        walletCombo.setDisable(true);
        for(Control control : List.of(refreshBtn, makerCombo, amountField, xmrAddressField, swapBtn, listSwapsBtn, swapIdField, refundBtn)) {
            control.setDisable(false);
        }
    }

    private void withDecrypted(Wallet wallet, Consumer<Wallet> action) {
        if(!wallet.isEncrypted()) {
            action.accept(wallet);
            return;
        }
        WalletPasswordDialog dialog = new WalletPasswordDialog(wallet.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
        dialog.initOwner(window());
        Optional<SecureString> password = dialog.showAndWait();
        if(password.isEmpty()) {
            return;
        }
        Storage.DecryptWalletService decrypt = new Storage.DecryptWalletService(wallet.copy(), password.get());
        decrypt.setOnSucceeded(event -> action.accept(decrypt.getValue()));
        decrypt.setOnFailed(event -> AppServices.showErrorDialog("Incorrect Password", decrypt.getException().getMessage()));
        decrypt.start();
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

    static boolean isMainnetMoneroAddress(String address) {
        //Standard (4) and subaddress (8) mainnet addresses; the engine checks the network again
        return address.length() == 95 && (address.charAt(0) == '4' || address.charAt(0) == '8')
                && address.chars().allMatch(c -> "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".indexOf(c) >= 0);
    }

    /** Asks before quitting while a swap runs. Returns true to quit. */
    static boolean confirmQuitDuringSwap(Window owner) {
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "A swap is still running. If Ashigaru is closed, the swap pauses: the maker can still finish its side, "
                        + "and if the swap was cancelled the refund must be claimed in time. Reopen Ashigaru and start the "
                        + "swap engine again soon - it picks unfinished swaps back up.\n\nQuit anyway?",
                ButtonType.CANCEL, ButtonType.YES);
        alert.setTitle("Swap running");
        alert.setHeaderText("A swap is running");
        alert.initOwner(owner);
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
        return alert.showAndWait().filter(button -> button == ButtonType.YES).isPresent();
    }

    private Window window() {
        return statusLabel.getScene().getWindow();
    }

    private static String btc(long sats) {
        return String.format(Locale.ROOT, "%.8f", sats / 100_000_000d);
    }

    private record Started(SwapEngine engine, List<String> resumed) {}
}
