package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.net.dojo.SignedMessageVerifier;
import com.sparrowwallet.sparrow.paynym.Bip47Signing;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.function.Supplier;

/**
 * Tools > Sign: signs a message as one of the open wallets' BIP47 payment codes, in the block Tools > Verifier reads.
 * What is signed and how is in {@link Bip47Signing}; this shows it. The answer sits beside the inputs and goes back to
 * blank whenever an input changes, as in the Verifier, so a signature on screen is always for what is in the boxes.
 */
public class Bip47MessageSignerController {
    private static final String IDLE = "verifier-result-idle";
    private static final String SUCCESS = "verifier-result-success";
    private static final String ERROR = "verifier-result-error";

    /** Where the wallets that can sign come from. Replaced by tests, which have no running Ashigaru. */
    static Supplier<List<Wallet>> walletSource = Bip47MessageSignerController::openWallets;

    @FXML private VBox root;
    @FXML private ComboBox<Wallet> walletCombo;
    @FXML private Label noWalletLabel;
    @FXML private VBox identityBox;
    @FXML private TextField paymentCodeField;
    @FXML private TextField addressField;
    @FXML private Button copyCodeBtn;
    @FXML private TextArea messageArea;
    @FXML private CheckBox addCodeCheck;
    @FXML private Label messageNote;
    @FXML private Button signBtn;
    @FXML private VBox resultBox;
    @FXML private Label resultIcon;
    @FXML private Label resultTitleLabel;
    @FXML private Label resultDetailLabel;
    @FXML private VBox checksBox;
    @FXML private VBox blockBox;
    @FXML private TextArea blockArea;
    @FXML private Button copyBlockBtn;
    @FXML private Button copySignatureBtn;

    private Bip47Signing.Signed signed;

    private static List<Wallet> openWallets() {
        AshigaruGui gui = AshigaruGui.get();
        if(gui == null) {
            return List.of();
        }
        return gui.getWalletForms().values().stream().map(form -> form.getWallet()).filter(Bip47Signing::canSign).toList();
    }

    @FXML
    public void initialize() {
        walletCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Wallet wallet) {
                if(wallet == null) {
                    return "";
                }
                String name = wallet.getMasterName();
                return name == null || name.isBlank() ? wallet.getDisplayName() : name;
            }

            @Override
            public Wallet fromString(String string) {
                return null;
            }
        });
        List<Wallet> wallets = walletSource.get();
        walletCombo.getItems().setAll(wallets);
        walletCombo.valueProperty().addListener((observable, old, wallet) -> {
            showIdentity(wallet);
            showIdle();
            updateNote();
        });

        boolean any = !wallets.isEmpty();
        identityBox.setVisible(any);
        identityBox.setManaged(any);
        walletCombo.setVisible(any);
        walletCombo.setManaged(any);
        noWalletLabel.setVisible(!any);
        noWalletLabel.setManaged(!any);
        if(!any) {
            noWalletLabel.setText("No open wallet can sign. A payment code belongs to a wallet that has its seed and is open; "
                    + "watch-only wallets and hardware wallets do not hold one. Open or create a wallet first.");
        }
        signBtn.setDisable(!any);

        messageArea.textProperty().addListener((observable, old, text) -> {
            showIdle();
            updateNote();
        });
        addCodeCheck.selectedProperty().addListener((observable, old, selected) -> {
            showIdle();
            updateNote();
        });
        //Ctrl+Enter (Cmd+Enter on a Mac) signs from any field, including the text area where Enter types a line break
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if(event.isShortcutDown() && event.getCode() == KeyCode.ENTER) {
                event.consume();
                onSign();
            }
        });
        leaveWithTab(messageArea);

        if(any) {
            walletCombo.getSelectionModel().selectFirst();
        }
        showIdle();
        updateNote();
    }

    /** Tab moves on instead of typing a tab character, as in the Verifier */
    private static void leaveWithTab(TextArea area) {
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if(event.getCode() == KeyCode.TAB && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                event.consume();
                area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "\t", KeyCode.TAB, event.isShiftDown(), true, false, false));
            }
        });
    }

    private void showIdentity(Wallet wallet) {
        if(wallet == null || !Bip47Signing.canSign(wallet)) {
            paymentCodeField.clear();
            addressField.clear();
            return;
        }
        paymentCodeField.setText(wallet.getPaymentCode().toString());
        addressField.setText(wallet.getPaymentCode().getNotificationAddress().toString());
    }

    /** Says what will be added to the message, so that what is signed is never a surprise */
    private void updateNote() {
        Wallet wallet = walletCombo.getValue();
        String text = messageArea.getText();
        String prefix = "The message will start with the line \"" + Bip47Signing.MESSAGE_PREFIX.strip() + "\", so it cannot be used as a login.";
        String note = null;
        if(wallet != null && Bip47Signing.canSign(wallet) && addCodeCheck.isSelected() && text != null && !text.isBlank()
                && SignedMessageVerifier.extractPaymentCode(text) == null) {
            note = prefix + " This line will be added to the end of the message: BIP47: " + wallet.getPaymentCode();
        } else if(text != null && SignedMessageVerifier.extractPaymentCode(text) != null) {
            note = prefix + " The message already names a payment code, so nothing is added at the end.";
        } else if(text != null && !text.isBlank()) {
            note = prefix;
        }
        messageNote.setText(note == null ? "" : note);
        messageNote.setVisible(note != null);
        messageNote.setManaged(note != null);
    }

    @FXML
    private void onCopyCode() {
        copyToClipboard(paymentCodeField.getText());
    }

    @FXML
    private void onPaste() {
        String text = Clipboard.getSystemClipboard().getString();
        if(text != null && !text.isEmpty()) {
            messageArea.setText(text);
        }
        messageArea.requestFocus();
    }

    @FXML
    private void onClear() {
        messageArea.clear();
        showIdle();
        messageArea.requestFocus();
    }

    @FXML
    private void onCopyBlock() {
        if(signed != null) {
            copyToClipboard(signed.block());
            copyBlockBtn.setText("Copied");
        }
    }

    @FXML
    private void onCopySignature() {
        if(signed != null) {
            copyToClipboard(signed.signature());
            copySignatureBtn.setText("Copied");
        }
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    @FXML
    private void onSign() {
        if(signBtn.isDisabled()) {
            return;
        }
        Wallet wallet = walletCombo.getValue();
        if(wallet == null) {
            showProblem("Choose the wallet whose payment code should sign.");
            walletCombo.requestFocus();
            return;
        }
        try {
            Bip47Signing.Signed result = Bip47Signing.sign(wallet, messageArea.getText(), addCodeCheck.isSelected());
            showSigned(result);
        } catch(IllegalArgumentException | IllegalStateException e) {
            showProblem(e.getMessage());
            if(e instanceof IllegalArgumentException) {
                messageArea.requestFocus();
            }
        }
    }

    private void showIdle() {
        signed = null;
        setResult(IDLE, FontAwesome5.Glyph.PEN_FANCY, "The signed message appears here",
                "Choose a wallet, enter the message, then choose Sign Message (Ctrl+Enter).");
    }

    private void showProblem(String problem) {
        signed = null;
        setResult(ERROR, FontAwesome5.Glyph.EXCLAMATION_TRIANGLE, "Cannot sign yet", problem);
    }

    private void showSigned(Bip47Signing.Signed result) {
        signed = result;
        setResult(SUCCESS, FontAwesome5.Glyph.CHECK_CIRCLE, "Message signed",
                "Send the whole block below. Whoever receives it can check it in Tools, Verifier, with your payment code.");
        checksBox.getChildren().addAll(
                VerifierRows.check(true, "Signed with the key of this payment code", null),
                VerifierRows.check(true, "Checked as a verifier would: the signature gives this address", result.address()));
        //Whether the message says whose code it is: added by the ticked box, or already in the message
        if(SignedMessageVerifier.extractPaymentCode(result.message()) != null) {
            checksBox.getChildren().add(VerifierRows.check(true, "The message names this payment code", null));
        }
        blockArea.setText(result.block());
        copyBlockBtn.setText("Copy signed message");
        copySignatureBtn.setText("Copy signature only");
        blockBox.setVisible(true);
        blockBox.setManaged(true);
    }

    private void setResult(String state, FontAwesome5.Glyph icon, String title, String detail) {
        resultBox.getStyleClass().removeAll(IDLE, SUCCESS, ERROR);
        resultBox.getStyleClass().add(state);
        resultIcon.setGraphic(Icons.of(icon, 22));
        resultTitleLabel.setText(title);
        resultDetailLabel.setText(detail == null ? "" : detail);
        resultDetailLabel.setVisible(detail != null);
        resultDetailLabel.setManaged(detail != null);
        checksBox.getChildren().clear();
        blockArea.clear();
        blockBox.setVisible(false);
        blockBox.setManaged(false);
    }
}
