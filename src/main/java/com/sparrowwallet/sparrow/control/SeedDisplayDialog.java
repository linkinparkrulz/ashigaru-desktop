package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.sparrow.AppServices;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;

public class SeedDisplayDialog extends Dialog<Void> {
    public SeedDisplayDialog(Keystore decryptedKeystore) {
        final DialogPane dialogPane = getDialogPane();
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.addAshigaruStylesheets(dialogPane.getStylesheets());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());

        int lines = decryptedKeystore.getSeed().getMnemonicCode().size() / 3;
        int height = lines * 40;

        StackPane stackPane = new StackPane();
        dialogPane.setContent(stackPane);

        AnchorPane anchorPane = new AnchorPane();
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.getStyleClass().add("edge-to-edge");
        scrollPane.setPrefHeight(74 + height);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        anchorPane.getChildren().add(scrollPane);
        scrollPane.setFitToWidth(true);
        AnchorPane.setLeftAnchor(scrollPane, 0.0);
        AnchorPane.setRightAnchor(scrollPane, 0.0);

        Accordion keystoreAccordion = new Accordion();
        scrollPane.setContent(keystoreAccordion);

        MnemonicKeystoreDisplayPane keystorePane = new MnemonicKeystoreDisplayPane(decryptedKeystore);
        keystorePane.setAnimated(false);
        keystoreAccordion.getPanes().add(keystorePane);

        stackPane.getChildren().addAll(anchorPane);

        //The words start hidden, so opening this near someone else does not expose them. They show
        //only while the button is held, by mouse or by keyboard.
        javafx.scene.effect.GaussianBlur blur = new javafx.scene.effect.GaussianBlur(22);
        anchorPane.setEffect(blur);
        Button reveal = new Button("Hold to reveal");
        reveal.getStyleClass().add("action-btn");
        //While hidden the words are also disabled, so Tab cannot walk through fields nobody can
        //see and the keyboard stays on the reveal button, where holding Space shows them.
        anchorPane.setDisable(true);
        reveal.armedProperty().addListener((obs, was, armed) -> {
            anchorPane.setEffect(armed ? null : blur);
            anchorPane.setDisable(!armed);
            reveal.setOpacity(armed ? 0 : 1);
        });
        stackPane.getChildren().add(reveal);
        Platform.runLater(reveal::requestFocus);

        final ButtonType cancelButtonType = new javafx.scene.control.ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(cancelButtonType);

        dialogPane.setPrefWidth(500);
        dialogPane.setPrefHeight(150 + height);
        AppServices.moveToActiveWindowScreen(this);

        Platform.runLater(() -> keystoreAccordion.setExpandedPane(keystorePane));
    }
}
