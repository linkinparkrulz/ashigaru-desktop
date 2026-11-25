package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.InteractionServices;
import com.sparrowwallet.sparrow.control.HelpLabel;
import com.sparrowwallet.sparrow.control.LifeHashIcon;
import com.sparrowwallet.sparrow.control.TextUtils;
import com.sparrowwallet.sparrow.control.ViewPasswordField;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.controlsfx.control.HyperlinkLabel;
import org.controlsfx.glyphfont.Glyph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sparrowwallet.sparrow.AppServices.*;

/**
 * Ashigaru-themed implementation of {@link InteractionServices}.
 * Replaces {@link com.sparrowwallet.sparrow.DefaultInteractionServices} for the
 * desktop GUI so that passphrase prompts and alert dialogs match the Ashigaru
 * dark identity instead of the legacy Sparrow light theme.
 */
public class AshigaruInteractionServices implements InteractionServices {
    private static final Logger log = LoggerFactory.getLogger(AshigaruInteractionServices.class);

    @Override
    public Optional<ButtonType> showAlert(String title, String content, Alert.AlertType alertType, Node graphic, ButtonType... buttons) {
        Alert alert = new Alert(alertType, content, buttons);
        alert.initOwner(getActiveWindow());
        setStageIcon(alert.getDialogPane().getScene().getWindow());
        // Apply Ashigaru dark theme (replaces the legacy general.css-only styling)
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getScene().getStylesheets());
        alert.setTitle(title);
        alert.setHeaderText(title);
        if(graphic != null) {
            alert.setGraphic(graphic);
        }

        Pattern linkPattern = Pattern.compile("\\[(http.+)]");
        Matcher matcher = linkPattern.matcher(content);
        if(matcher.find()) {
            String link = matcher.group(1);
            HyperlinkLabel hyperlinkLabel = new HyperlinkLabel(content);
            hyperlinkLabel.setMaxWidth(Double.MAX_VALUE);
            hyperlinkLabel.setMaxHeight(Double.MAX_VALUE);
            hyperlinkLabel.getStyleClass().add("content");
            Label label = new Label();
            hyperlinkLabel.setPrefWidth(Math.max(360, TextUtils.computeTextWidth(label.getFont(), link, 0.0D) + 50));
            hyperlinkLabel.setOnAction(event -> {
                alert.close();
                AppServices.openExternalLink(link);
            });
            alert.getDialogPane().setContent(hyperlinkLabel);
        }

        String[] lines = content.split("\r\n|\r|\n");
        if(lines.length > 3 || org.controlsfx.tools.Platform.getCurrent() == org.controlsfx.tools.Platform.WINDOWS) {
            double numLines = Arrays.stream(lines).mapToDouble(line -> Math.ceil(TextUtils.computeTextWidth(Font.getDefault(), line, 0) / 300)).sum();
            alert.getDialogPane().setPrefHeight(200 + numLines * 20);
        }

        alert.setResizable(true);
        moveToActiveWindowScreen(alert);
        return alert.showAndWait();
    }

    @Override
    public Optional<String> requestPassphrase(String walletName, Keystore keystore) {
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle("BIP39 Passphrase" + (walletName != null ? " — " + walletName : ""));
        dlg.initOwner(getActiveWindow());

        WalletCreationFlow.styleWizardDialog(dlg, "PASSPHRASE REQUIRED",
                "Enter BIP39 passphrase",
                walletName != null
                        ? "Wallet \"" + walletName + "\" needs its BIP39 passphrase to restore keys."
                        : "Enter the BIP39 passphrase for this keystore.");

        ButtonType okType = new ButtonType("Unlock", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        WalletCreationFlow.styleWizardButtons(dlg.getDialogPane());

        Label hint = new Label("Enter your BIP39 passphrase (leave empty if none):");
        hint.getStyleClass().add("field-label");
        ViewPasswordField passField = new ViewPasswordField();
        passField.setPromptText("BIP39 passphrase");

        // Live master fingerprint — mirrors KeystorePassphraseDialog so the user
        // can verify they've entered the right passphrase before unlocking.
        ObjectProperty<byte[]> masterFingerprint = new SimpleObjectProperty<>();

        passField.textProperty().addListener((obs, oldVal, newVal) ->
                masterFingerprint.set(computeFingerprint(keystore, newVal)));

        Label fingerprintLabel = new Label("Master fingerprint:");
        fingerprintLabel.getStyleClass().add("field-label");
        TextField fingerprintHex = new TextField();
        fingerprintHex.setDisable(true);
        fingerprintHex.setMaxWidth(80);
        fingerprintHex.getStyleClass().addAll("fixed-width", "fingerprint-hex");
        masterFingerprint.addListener((obs, oldVal, newVal) -> {
            if(newVal != null) {
                fingerprintHex.setText(Utils.bytesToHex(newVal));
            }
        });

        LifeHashIcon lifeHashIcon = new LifeHashIcon();
        lifeHashIcon.dataProperty().bind(masterFingerprint);

        HelpLabel helpLabel = new HelpLabel();
        helpLabel.setHelpText("All passphrases create valid wallets." +
                "\nThe master fingerprint identifies the keystore and changes as the passphrase changes." +
                "\nMake sure you recognise it before proceeding.");

        HBox fingerprintBox = new HBox(10, fingerprintLabel, fingerprintHex, lifeHashIcon, helpLabel);
        fingerprintBox.setAlignment(Pos.CENTER_LEFT);

        Glyph warnGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.EXCLAMATION_TRIANGLE);
        warnGlyph.getStyleClass().add("warn-icon");
        warnGlyph.setFontSize(12);
        Label warnLabel = new Label("Check the master fingerprint before proceeding!", warnGlyph);
        warnLabel.setGraphicTextGap(5);

        // Seed the fingerprint for the empty-passphrase default
        masterFingerprint.set(computeFingerprint(keystore, ""));

        VBox content = new VBox(10, hint, passField, fingerprintBox, warnLabel);
        content.setPadding(new Insets(20));
        content.setPrefWidth(460);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(460);
        dlg.getDialogPane().setPrefHeight(280);
        AppServices.moveToActiveWindowScreen(dlg);

        Platform.runLater(passField::requestFocus);
        dlg.setResultConverter(btn -> btn == okType ? passField.getText() : null);
        return dlg.showAndWait();
    }

    private static byte[] computeFingerprint(Keystore keystore, String passphrase) {
        try {
            Keystore copy = keystore.copy();
            copy.getSeed().setPassphrase(passphrase);
            return copy.getExtendedMasterPrivateKey().getKey().getFingerprint();
        } catch(MnemonicException e) {
            throw new RuntimeException(e);
        }
    }
}
