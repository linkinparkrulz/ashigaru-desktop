package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.control.ViewPasswordField;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/**
 * The one small password window of the Ashigaru screens: a header saying what it is for, a password field, Unlock and
 * Cancel. Opening a wallet, viewing its seed, pairing with Sentinel and starting a swap all ask the same way, instead of
 * some of them using Sparrow's large fixed-size dialog. Also the one wording for a wrong password, which never carries
 * the cipher's own message.
 */
public final class WalletPasswordPrompt {
    private WalletPasswordPrompt() {}

    /** The window, answering with the password as typed, or null when cancelled */
    public static Dialog<String> dialog(Window owner, String walletName, String eyebrow) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Wallet Password");
        if(owner != null) {
            dialog.initOwner(owner);
        }
        WalletCreationFlow.styleWizardDialog(dialog, eyebrow, "Wallet password", "Enter the password for " + walletName + ".");

        ButtonType unlock = new ButtonType("Unlock", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(unlock, ButtonType.CANCEL);
        WalletCreationFlow.styleWizardButtons(dialog.getDialogPane());

        ViewPasswordField field = new ViewPasswordField();
        field.setPromptText("Password");
        VBox box = new VBox(field);
        box.setSpacing(8);
        dialog.getDialogPane().setContent(box);
        Platform.runLater(field::requestFocus);
        dialog.setResultConverter(button -> button == unlock ? field.getText() : null);
        return dialog;
    }

    /** Asks, and answers with the password as a {@link SecureString}, or nothing when cancelled */
    public static Optional<SecureString> ask(Window owner, String walletName, String eyebrow) {
        Optional<String> typed = dialog(owner, walletName, eyebrow).showAndWait();
        return typed.map(SecureString::new);
    }

    /** What a person is told when the password does not open the wallet */
    public static String wrongPasswordText(String walletName) {
        return "That is not the password for " + walletName + ". Nothing was unlocked, locked or sent. Try again.";
    }

    public static void showWrongPassword(String walletName) {
        AppServices.showErrorDialog("Wrong password", wrongPasswordText(walletName));
    }
}
