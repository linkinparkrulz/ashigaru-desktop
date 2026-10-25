package com.sparrowwallet.sparrow.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.swap.BadbankSwapHost;
import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import com.sparrowwallet.sparrow.swap.SwapService;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * The Swap to XMR window, opened from a wallet's Badbank account. A view over {@link SwapService}:
 * closing it leaves the engine and any running swap alone.
 */
public class SwapController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(SwapController.class);

    private static Stage stage;
    private static SwapController current;

    @FXML private Label walletLabel;
    @FXML private Label statusLabel;
    @FXML private Button refreshBtn;
    @FXML private ComboBox<Quote> makerCombo;
    @FXML private TextField amountField;
    @FXML private Label balanceLabel;
    @FXML private TextField xmrAddressField;
    @FXML private Button swapBtn;
    @FXML private Button listSwapsBtn;
    @FXML private TextField swapIdField;
    @FXML private Button refundBtn;
    @FXML private Label messageLabel;
    @FXML private TextArea logArea;

    private String walletId;
    private final Consumer<String> logListener = line -> logArea.appendText(line);
    private final ChangeListener<String> statusListener = (observable, oldValue, newValue) -> updateState();

    /** Opens the swap window for a master wallet, or brings it forward switched to that wallet */
    public static void show(WalletForm masterForm, Window owner) {
        try {
            if(stage == null) {
                FXMLLoader loader = new FXMLLoader(AshigaruGui.class.getResource("swap.fxml"));
                Parent root = loader.load();
                current = loader.getController();
                stage = new Stage();
                stage.initOwner(owner);
                Scene scene = new Scene(root, 900, 680);
                AppServices.addAshigaruStylesheets(scene.getStylesheets());
                scene.setFill(AshigaruGui.APP_BACKGROUND);
                stage.setScene(scene);
                stage.setOnHidden(event -> {
                    current.close();
                    current = null;
                    stage = null;
                });
            }
            current.setWallet(masterForm);
            stage.setTitle("Swap Badbank to XMR – " + masterForm.getWallet().getMasterName());
            stage.show();
            stage.toFront();
        } catch(Exception e) {
            log.error("Could not open the swap window", e);
            AppServices.showErrorDialog("Could not open the swap window", e.getMessage());
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
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

        SwapService service = SwapService.get();
        logArea.setText(service.getLog());
        logArea.positionCaret(logArea.getLength());
        service.addLogListener(logListener);
        statusLabel.textProperty().bind(service.statusProperty());
        service.statusProperty().addListener(statusListener);
    }

    private void setWallet(WalletForm masterForm) {
        walletId = masterForm.getWalletId();
        walletLabel.setText("Swap " + masterForm.getWallet().getMasterName() + " Badbank to XMR");
        String problem = SwapService.get().ensureRunningFor(walletId);
        messageLabel.setText(problem == null ? "" : problem);
        updateState();
    }

    private void close() {
        SwapService service = SwapService.get();
        service.removeLogListener(logListener);
        service.statusProperty().removeListener(statusListener);
        statusLabel.textProperty().unbind();
    }

    private void updateState() {
        boolean running = walletId != null && SwapService.get().isRunningFor(walletId);
        for(Control control : List.of(refreshBtn, makerCombo, amountField, xmrAddressField, swapBtn, listSwapsBtn, swapIdField, refundBtn)) {
            control.setDisable(!running);
        }
        BadbankSwapHost host = SwapService.get().getHost();
        balanceLabel.setText(running && host != null ? "Badbank: " + btc(host.getBalance()) + " BTC" : "");
    }

    @FXML
    private void onRefresh() {
        SwapService.get().listQuotes(quotes -> {
            Quote selected = makerCombo.getValue();
            makerCombo.getItems().setAll(quotes);
            if(selected != null) {
                quotes.stream().filter(q -> q.peerId().equals(selected.peerId())).findFirst().ifPresent(makerCombo::setValue);
            }
            updateState();
            messageLabel.setText(quotes.isEmpty() ? "No makers yet - they are discovered over a minute or two after the engine starts."
                    : quotes.size() + " maker" + (quotes.size() == 1 ? "" : "s") + " with quotes.");
        }, e -> messageLabel.setText("Could not list quotes: " + e.getMessage()));
    }

    @FXML
    private void onStartSwap() {
        Quote maker = makerCombo.getValue();
        if(maker == null) {
            messageLabel.setText("Choose a maker.");
            return;
        }
        if(SwapService.get().isSwapActive()) {
            messageLabel.setText("A swap is already running; one at a time.");
            return;
        }

        long sats;
        try {
            sats = new BigDecimal(amountField.getText().trim()).movePointRight(8).longValueExact();
        } catch(ArithmeticException | NumberFormatException e) {
            messageLabel.setText("Enter the amount in BTC, at most 8 decimal places.");
            return;
        }
        long maxSats = Config.get().getSwapMaxSats();
        if(sats < maker.minSats() || sats > maker.maxSats()) {
            messageLabel.setText("This maker takes between " + btc(maker.minSats()) + " and " + btc(maker.maxSats()) + " BTC.");
            return;
        }
        if(sats > maxSats) {
            messageLabel.setText("Swaps are limited to " + btc(maxSats) + " BTC. Change the maximum in Settings → Swaps.");
            return;
        }

        String xmrAddress = xmrAddressField.getText().trim();
        if(!isMainnetMoneroAddress(xmrAddress)) {
            messageLabel.setText("Enter a mainnet Monero address: 95 characters, starting with 4 or 8.");
            return;
        }

        BadbankSwapHost host = SwapService.get().getHost();
        long lockFee;
        try {
            lockFee = host.estimateLockFee(sats);
        } catch(Exception e) {
            messageLabel.setText("Badbank cannot fund this swap: " + e.getMessage());
            return;
        }
        String refundAddress = host.getFreshAddress();

        swapBtn.setDisable(true);
        messageLabel.setText("Setting up the swap with the maker…");
        SwapService.get().startSwap(maker, sats, lockFee, xmrAddress, refundAddress, swapId -> {
            swapIdField.setText(swapId);
            swapBtn.setDisable(false);
            messageLabel.setText("Swap running. Ashigaru asks before signing and again before locking anything.");
        }, e -> {
            swapBtn.setDisable(false);
            messageLabel.setText("Could not start the swap: " + e.getMessage());
        });
    }

    @FXML
    private void onListSwaps() {
        SwapService.get().listSwaps(swaps -> {
            if(swaps.isEmpty()) {
                logArea.appendText("No swaps yet." + System.lineSeparator());
            }
            for(JsonElement element : swaps) {
                JsonObject swap = element.getAsJsonObject();
                logArea.appendText("Swap " + swap.get("swapId").getAsString() + ": " + swap.get("state").getAsString()
                        + (swap.get("running").getAsBoolean() ? " (running)" : "")
                        + (swap.get("complete").getAsBoolean() ? " (finished)" : "") + System.lineSeparator());
            }
        }, e -> messageLabel.setText("Could not list swaps: " + e.getMessage()));
    }

    @FXML
    private void onRefund() {
        String swapId = swapIdField.getText().trim();
        if(swapId.isEmpty()) {
            messageLabel.setText("Enter the id of the swap to refund.");
            return;
        }
        refundBtn.setDisable(true);
        messageLabel.setText("Cancelling and refunding " + swapId + "…");
        SwapService.get().refund(swapId, state -> {
            refundBtn.setDisable(false);
            messageLabel.setText("Refund published to Badbank.");
        }, e -> {
            refundBtn.setDisable(false);
            messageLabel.setText("Could not refund: " + e.getMessage());
        });
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
                        + "and if the swap was cancelled the refund must be claimed in time. Reopen Ashigaru and unlock the "
                        + "wallet again soon - it picks unfinished swaps back up.\n\nQuit anyway?",
                ButtonType.CANCEL, ButtonType.YES);
        alert.setTitle("Swap running");
        alert.setHeaderText("A swap is running");
        alert.initOwner(owner);
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
        return alert.showAndWait().filter(button -> button == ButtonType.YES).isPresent();
    }

    private static String btc(long sats) {
        return String.format(Locale.ROOT, "%.8f", sats / 100_000_000d);
    }
}
