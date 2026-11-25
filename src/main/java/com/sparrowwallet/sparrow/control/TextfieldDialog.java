package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.sparrow.AppServices;
import javafx.application.Platform;
import javafx.beans.NamedArg;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TextfieldDialog extends Dialog<String> {
    private static final Logger log = LoggerFactory.getLogger(TextfieldDialog.class);

    private final TextField textField;
    private final String defaultValue;

    public TextfieldDialog() {
        this("");
    }

    public TextfieldDialog(String defaultValue) {
        this(defaultValue, true);
    }

    public TextfieldDialog(@NamedArg("defaultValue") String defaultValue, @NamedArg("editable") boolean editable) {
        final DialogPane dialogPane = getDialogPane();
        setDialogPane(dialogPane);

        Image image = new Image("/image/Ashigaru_Terminal_Logo_Circle.png");
        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setFitHeight(50);
        dialogPane.setGraphic(imageView);

        HBox hbox = new HBox();
        this.textField = new TextField(defaultValue);
        this.textField.setMaxWidth(Double.MAX_VALUE);
        this.textField.setEditable(editable);
        hbox.getChildren().add(textField);
        HBox.setHgrow(this.textField, Priority.ALWAYS);

        this.defaultValue = defaultValue;

        dialogPane.setContent(hbox);
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.addAshigaruStylesheets(dialogPane.getStylesheets());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());

        dialogPane.getStyleClass().add("text-input-dialog");
        dialogPane.getButtonTypes().add(ButtonType.OK);
        if(editable) {
            dialogPane.getButtonTypes().add(ButtonType.CANCEL);
        }

        Platform.runLater(textField::requestFocus);

        setResultConverter((dialogButton) -> {
            ButtonBar.ButtonData data = dialogButton == null ? null : dialogButton.getButtonData();
            return data == ButtonBar.ButtonData.OK_DONE ? textField.getText() : null;
        });

        dialogPane.setPrefWidth(600);
        dialogPane.setPrefHeight(230);
        AppServices.moveToActiveWindowScreen(this);
    }

    public final TextField getEditor() {
        return textField;
    }

    public final String getDefaultValue() {
        return defaultValue;
    }
}
