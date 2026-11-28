package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.net.update.ReleaseTrust;
import com.sparrowwallet.sparrow.paynym.PayNymService;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Tools > Verifier. The signed message is one paste, read as it is pasted; the message and signature boxes
 * below it mirror what was read, and editing them takes the block's place. Verify always uses the message and
 * signature boxes, so a block pasted after another can never be silently outvoted by the earlier one. The answer
 * is shown beside the inputs and goes back to blank whenever an input changes.
 */
public class Bip47MessageVerifierController {
    private static final String IDLE = "verifier-result-idle";
    private static final String SUCCESS = "verifier-result-success";
    private static final String ERROR = "verifier-result-error";

    @FXML private VBox root;
    @FXML private TextField paymentCodeField;
    @FXML private TextField payNymHandleField;
    @FXML private Button payNymLookupBtn;
    @FXML private Label payNymLookupStatus;
    @FXML private TextArea signedBlockArea;
    @FXML private Label blockStatus;
    @FXML private TitledPane manualPane;
    @FXML private TextArea messageArea;
    @FXML private TextArea signatureArea;
    @FXML private VBox resultBox;
    @FXML private Label resultIcon;
    @FXML private Label resultTitleLabel;
    @FXML private Label resultDetailLabel;
    @FXML private VBox checksBox;

    //Set while the boxes are being filled in from a block, or the block cleared for typed input, so that
    //doing so does not count as the person editing them
    private boolean mirroring;

    @FXML
    public void initialize() {
        signedBlockArea.textProperty().addListener((observable, old, text) -> {
            if(!mirroring) {
                onBlockChanged(text);
            }
        });
        messageArea.textProperty().addListener((observable, old, text) -> onTypedInputChanged());
        signatureArea.textProperty().addListener((observable, old, text) -> onTypedInputChanged());
        paymentCodeField.textProperty().addListener((observable, old, text) -> showIdle());

        paymentCodeField.setOnAction(event -> onVerify());
        payNymHandleField.setOnAction(event -> onLookupPayNym());
        //Ctrl+Enter (Cmd+Enter on a Mac) verifies from any field, including the text areas where Enter types a line break
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if(event.isShortcutDown() && event.getCode() == KeyCode.ENTER) {
                event.consume();
                onVerify();
            }
        });
        for(TextArea area : new TextArea[] {signedBlockArea, messageArea, signatureArea}) {
            leaveWithTab(area);
        }

        setBlockStatus(null, false);
        showIdle();
    }

    /**
     * A text area types a tab character on Tab. Nobody pastes a tab into these, and it leaves the keyboard stuck, so
     * Tab moves on as it does in a text field. A text area moves focus on Ctrl+Tab, so that is what is sent in its place.
     */
    private static void leaveWithTab(TextArea area) {
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if(event.getCode() == KeyCode.TAB && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                event.consume();
                area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "\t", KeyCode.TAB, event.isShiftDown(), true, false, false));
            }
        });
    }

    @FXML
    private void onUseReleaseCode() {
        paymentCodeField.setText(ReleaseTrust.RELEASE_SIGNING_PAYMENT_CODE);
    }

    /**
     * Resolves a PayNym handle to its payment code and fills the field above.
     *
     * <p>Deliberately on a button (and Enter) rather than as-you-type: every attempt is a request that tells the
     * directory which handle was asked about. PayNymService picks the onion host when Tor is
     * configured, and already hands its result back on the FX thread, so there is no threading to do
     * here. The resolved name is shown rather than the field silently changing, because a code
     * fetched from a directory is only as trustworthy as the directory - for checking a release,
     * "Use Release Signing Code" remains the path that does not depend on the network.
     */
    @FXML
    private void onLookupPayNym() {
        String handle = payNymHandleField.getText() == null ? "" : payNymHandleField.getText().trim();
        if(handle.isEmpty()) {
            showLookupStatus("Enter a PayNym handle, for example +linkinparkrulz");
            return;
        }
        //A bare name is the common way to type it; a pasted payment code must be left alone
        if(!handle.startsWith("+") && !handle.startsWith("PM")) {
            handle = "+" + handle;
        }

        final String nymIdentifier = handle;
        payNymLookupBtn.setDisable(true);
        showLookupStatus("Looking up " + nymIdentifier + "…");

        //compact: this needs the payment code, not the follower and following lists. A directory that does not answer is given
        //LOOKUP_TIMEOUT_SECONDS, and the request ends with the wait: its connection has the same timeout, and is closed if the wait ends first
        PayNymService.lookupPayNym(nymIdentifier, true, (int)java.util.concurrent.TimeUnit.SECONDS.toMillis(LOOKUP_TIMEOUT_SECONDS))
                .timeout(LOOKUP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS, io.reactivex.rxjavafx.schedulers.JavaFxScheduler.platform())
                .subscribe(payNym -> {
            payNymLookupBtn.setDisable(false);
            paymentCodeField.setText(payNym.paymentCode().toString());
            showLookupStatus("Filled in the payment code for " + payNym.nymName() + " from the PayNym directory. "
                    + "A code from a directory is only as trustworthy as the directory.", false);
        }, error -> {
            payNymLookupBtn.setDisable(false);
            showLookupStatus(lookupFailure(nymIdentifier, error), true);
        });
    }

    static final long LOOKUP_TIMEOUT_SECONDS = 30;
    static final String DIRECTORY_DOWN = "The PayNym directory isn't working right now, so the payment code can't be filled in. "
            + "Try again later, or paste the payment code yourself.";

    /**
     * What a failed lookup means for the person, from its error: no such handle, Ashigaru not online through Tor (said as TorOnly says it),
     * or the directory not working (down, slow, refusing, an error page), which is said plainly with what to do instead.
     */
    static String lookupFailure(String handle, Throwable error) {
        for(Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if(message != null && message.startsWith("PayNym directory not contacted: ")) {
                return "Could not look up " + handle + ": " + message.substring("PayNym directory not contacted: ".length());
            }
            if(message != null && (message.contains("status=404") || message.contains(" 404") || message.startsWith("404"))) {
                return "No PayNym called " + handle + ". Check the handle, or paste the payment code yourself.";
            }
        }
        return DIRECTORY_DOWN;
    }

    private void showLookupStatus(String text, boolean failed) {
        payNymLookupStatus.setText(text);
        payNymLookupStatus.setVisible(true);
        payNymLookupStatus.setManaged(true);
        payNymLookupStatus.getStyleClass().remove("lookup-failed");
        if(failed) {
            payNymLookupStatus.getStyleClass().add("lookup-failed");
        }
    }

    private void showLookupStatus(String text) {
        showLookupStatus(text, false);
    }

    @FXML
    private void onPaste() {
        String text = Clipboard.getSystemClipboard().getString();
        if(text != null && !text.isEmpty()) {
            signedBlockArea.setText(text);
        }
        signedBlockArea.requestFocus();
    }

    @FXML
    private void onClear() {
        mirroring = true;
        try {
            signedBlockArea.clear();
            messageArea.clear();
            signatureArea.clear();
        } finally {
            mirroring = false;
        }
        setBlockStatus(null, false);
        showIdle();
        signedBlockArea.requestFocus();
    }

    /** A block was pasted or edited: read it, and let the message and signature boxes show what it holds */
    private void onBlockChanged(String text) {
        showIdle();
        if(text == null || text.isBlank()) {
            fillMessageAndSignature("", "");
            setBlockStatus(null, false);
            return;
        }

        Bip47Verification.Block block = Bip47Verification.parseBlock(text);
        if(block != null) {
            fillMessageAndSignature(block.message(), block.signature());
            setBlockStatus("Signed message recognised.", false);
        } else {
            //What the boxes held came from an earlier block, so it must not outlive it
            fillMessageAndSignature("", "");
            setBlockStatus(Bip47Verification.looksLikeBlock(text) ? Bip47Verification.blockProblem(text)
                    : "That is not a signed message block. Paste the whole block, or enter the message and signature separately.", true);
        }
    }

    /** The message or signature was typed: it replaces any block, which no longer says what will be verified */
    private void onTypedInputChanged() {
        if(mirroring) {
            return;
        }
        showIdle();
        if(!signedBlockArea.getText().isEmpty()) {
            mirroring = true;
            try {
                signedBlockArea.clear();
            } finally {
                mirroring = false;
            }
        }
        boolean any = !messageArea.getText().isEmpty() || !signatureArea.getText().isEmpty();
        setBlockStatus(any ? "Using the message and signature entered below." : null, false);
    }

    private void fillMessageAndSignature(String message, String signature) {
        mirroring = true;
        try {
            messageArea.setText(message);
            signatureArea.setText(signature);
        } finally {
            mirroring = false;
        }
    }

    private void setBlockStatus(String text, boolean problem) {
        blockStatus.setText(text == null ? "" : text);
        blockStatus.setVisible(text != null);
        blockStatus.setManaged(text != null);
        blockStatus.getStyleClass().remove("verifier-status-warn");
        if(problem) {
            blockStatus.getStyleClass().add("verifier-status-warn");
        }
    }

    @FXML
    private void onVerify() {
        Bip47Verification.Result result = Bip47Verification.verify(paymentCodeField.getText(), messageArea.getText(), signatureArea.getText());
        switch(result.outcome()) {
            case VERIFIED -> showVerified(result);
            case MISMATCH -> showMismatch(result);
            case PROBLEM -> showProblem(result);
        }
    }

    private void showIdle() {
        setResult(IDLE, FontAwesome5.Glyph.SHIELD_ALT, "The result appears here",
                "Paste a signed message and the payment code it should have come from, then choose Verify Message (Ctrl+Enter).");
    }

    private void showVerified(Bip47Verification.Result result) {
        boolean namesOther = Boolean.FALSE.equals(result.namedCode());
        setResult(SUCCESS, FontAwesome5.Glyph.CHECK_CIRCLE, "Signature verified",
                "The message was signed by the notification address of this payment code."
                        + (namesOther ? " The message itself names a different payment code, so check it is the code you meant." : ""));
        addChecks(result, true);
    }

    private void showMismatch(Bip47Verification.Result result) {
        setResult(ERROR, FontAwesome5.Glyph.EXCLAMATION_TRIANGLE, "Signature does not match this payment code",
                "The signature is well formed but was made by a different address. Do not trust this message.");
        addChecks(result, false);
    }

    private void showProblem(Bip47Verification.Result result) {
        setResult(ERROR, FontAwesome5.Glyph.EXCLAMATION_TRIANGLE, "Cannot verify yet", result.problem());
        focus(result.field());
    }

    /** Puts the cursor in the input the problem is about, opening the typed-input section if that is where it is */
    private void focus(Bip47Verification.Field field) {
        switch(field) {
            case PAYMENT_CODE -> paymentCodeField.requestFocus();
            case MESSAGE, SIGNATURE -> {
                if(!signedBlockArea.getText().isBlank()) {
                    signedBlockArea.requestFocus();
                } else {
                    manualPane.setExpanded(true);
                    (field == Bip47Verification.Field.MESSAGE ? messageArea : signatureArea).requestFocus();
                }
            }
        }
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
    }

    /** The result is the list of what was checked, each with the value it produced, so a mismatch shows which link failed and with what */
    private void addChecks(Bip47Verification.Result result, boolean match) {
        checksBox.getChildren().addAll(
                check(true, "Payment code is valid", null),
                check(true, "Notification address derived", result.notificationAddress()),
                check(true, "Signer recovered from the signature", result.signerAddress()),
                check(match, match ? "The two addresses are identical" : "The two addresses differ", null));
        if(result.namedCode() != null) {
            checksBox.getChildren().add(check(result.namedCode(), result.namedCode() ? "The message names this payment code"
                    : "The message names a different payment code", null));
        }
    }

    private static VBox check(boolean passed, String text, String value) {
        return VerifierRows.check(passed, text, value);
    }
}
