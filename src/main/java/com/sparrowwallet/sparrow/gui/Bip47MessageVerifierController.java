package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.sparrow.net.dojo.SignedMessageVerifier;
import com.sparrowwallet.sparrow.net.update.ReleaseTrust;
import com.sparrowwallet.sparrow.paynym.PayNymService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.security.SignatureException;

public class Bip47MessageVerifierController {

    @FXML private TextField paymentCodeField;
    @FXML private TextField payNymHandleField;
    @FXML private Button payNymLookupBtn;
    @FXML private Label payNymLookupStatus;
    @FXML private TextArea signedBlockArea;
    @FXML private TextArea messageArea;
    @FXML private TextArea signatureArea;
    @FXML private VBox resultBox;
    @FXML private Label resultTitleLabel;
    @FXML private Label notificationAddressLabel;
    @FXML private Label signerAddressLabel;

    @FXML
    private void onUseReleaseCode() {
        paymentCodeField.setText(ReleaseTrust.RELEASE_SIGNING_PAYMENT_CODE);
    }

    /**
     * Resolves a PayNym handle to its payment code and fills the field above.
     *
     * <p>Deliberately on a button rather than as-you-type: every attempt is a request that tells the
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
        showLookupStatus("Looking up " + nymIdentifier + "...");

        //compact: this needs the payment code, not the follower and following lists
        PayNymService.getPayNym(nymIdentifier, true).subscribe(payNym -> {
            payNymLookupBtn.setDisable(false);
            paymentCodeField.setText(payNym.paymentCode().toString());
            showLookupStatus("Filled in the payment code for " + payNym.nymName());
        }, error -> {
            payNymLookupBtn.setDisable(false);
            showLookupStatus("Could not look up " + nymIdentifier + ": " + lookupFailure(error));
        });
    }

    /** Separates "no such handle" from "could not reach the directory" - different problems. */
    private String lookupFailure(Throwable error) {
        String message = error.getMessage();
        if(message == null || message.isBlank()) {
            return "no response from the PayNym directory";
        }
        if(message.contains("404")) {
            return "no PayNym with that handle";
        }
        return message;
    }

    private void showLookupStatus(String text) {
        payNymLookupStatus.setText(text);
        payNymLookupStatus.setVisible(true);
        payNymLookupStatus.setManaged(true);
    }

    @FXML
    private void onParseSignedBlock() {
        try {
            SignedMessageVerifier.ParsedBlock parsed = SignedMessageVerifier.parse(signedBlockArea.getText());
            messageArea.setText(parsed.message());
            signatureArea.setText(parsed.signature());
            showInfo("Signed message block parsed", null, null);
        } catch(Exception e) {
            showError(e.getMessage());
        }
    }

    @FXML
    private void onVerify() {
        try {
            String paymentCodeText = paymentCodeField.getText() == null ? "" : paymentCodeField.getText().trim();
            if(paymentCodeText.isEmpty()) {
                throw new IllegalArgumentException("BIP47 payment code is required");
            }

            String message = messageArea.getText();
            String signature = signatureArea.getText() == null ? "" : signatureArea.getText().trim();
            if((message == null || message.isEmpty()) && signedBlockArea.getText() != null && !signedBlockArea.getText().isBlank()) {
                SignedMessageVerifier.ParsedBlock parsed = SignedMessageVerifier.parse(signedBlockArea.getText());
                message = parsed.message();
                signature = parsed.signature();
                messageArea.setText(message);
                signatureArea.setText(signature);
            }

            if(message == null || message.isEmpty()) {
                throw new IllegalArgumentException("Message is required");
            }
            if(signature.isEmpty()) {
                throw new IllegalArgumentException("Base64 signature is required");
            }

            PaymentCode paymentCode = new PaymentCode(paymentCodeText);
            Address notificationAddress = paymentCode.getNotificationAddress();
            Address recoveredAddress = SignedMessageVerifier.recoverAddress(message, signature);

            if(notificationAddress.equals(recoveredAddress)) {
                showSuccess(notificationAddress.toString(), recoveredAddress.toString());
            } else {
                showInvalid(notificationAddress.toString(), recoveredAddress.toString());
            }
        } catch(SignatureException e) {
            showError("Signature could not be verified: " + e.getMessage());
        } catch(Exception e) {
            showError(e.getMessage());
        }
    }

    private void showSuccess(String notificationAddress, String signerAddress) {
        resultBox.getStyleClass().removeAll("verifier-result-error", "verifier-result-info");
        if(!resultBox.getStyleClass().contains("verifier-result-success")) {
            resultBox.getStyleClass().add("verifier-result-success");
        }
        showInfo("Signature verified", notificationAddress, signerAddress);
    }

    private void showInvalid(String notificationAddress, String signerAddress) {
        resultBox.getStyleClass().removeAll("verifier-result-success", "verifier-result-info");
        if(!resultBox.getStyleClass().contains("verifier-result-error")) {
            resultBox.getStyleClass().add("verifier-result-error");
        }
        showInfo("Signature does not match this payment code", notificationAddress, signerAddress);
    }

    private void showError(String error) {
        resultBox.getStyleClass().removeAll("verifier-result-success", "verifier-result-info");
        if(!resultBox.getStyleClass().contains("verifier-result-error")) {
            resultBox.getStyleClass().add("verifier-result-error");
        }
        showInfo("Verification error: " + error, null, null);
    }

    private final VBox checks = new VBox();

    /**
     * The result is the list of what was checked, each with the value it produced, so a mismatch
     * shows which link failed and with what, not only that something did.
     */
    private void showInfo(String title, String notificationAddress, String signerAddress) {
        resultTitleLabel.setText(title);
        boolean ran = notificationAddress != null && signerAddress != null;
        checks.getChildren().clear();
        if(ran) {
            boolean match = notificationAddress.equals(signerAddress);
            checks.getChildren().addAll(
                    check(true, "Payment code is valid", null),
                    check(true, "Notification address derived", notificationAddress),
                    check(true, "Signer recovered from the signature", signerAddress),
                    check(match, match ? "The two addresses are identical" : "The two addresses differ",
                            match ? null : "Do not trust this message or the file it belongs to."));
            if(!resultBox.getChildren().contains(checks)) {
                resultBox.getChildren().add(checks);
            }
        } else {
            resultBox.getChildren().remove(checks);
        }
        notificationAddressLabel.setText("");
        signerAddressLabel.setText("");
        for(Label old : new Label[] {notificationAddressLabel, signerAddressLabel}) {
            old.setVisible(false);
            old.setManaged(false);
        }
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private static Label check(boolean passed, String text, String value) {
        Label label = new Label(value == null ? text : text + "\n" + value);
        label.getStyleClass().addAll("verify-step", passed ? "verifier-step-pass" : "verifier-step-fail");
        label.setGraphic(Icons.of(passed ? FontAwesome5.Glyph.CHECK : FontAwesome5.Glyph.TIMES, 12));
        label.setGraphicTextGap(11);
        label.setWrapText(true);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }
}
