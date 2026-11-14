package com.sparrowwallet.sparrow.control;

import impl.org.controlsfx.skin.CustomTextFieldSkin;
import javafx.scene.control.Tooltip;
import javafx.scene.Cursor;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.*;
import org.controlsfx.control.textfield.CustomPasswordField;

public abstract class ViewPasswordFieldSkin extends CustomTextFieldSkin {
    public static final char BULLET = '\u25CF';

    private boolean mask = true;

    public ViewPasswordFieldSkin(CustomPasswordField textField) {
        super(textField);

        Region viewPasswordButton = new Region();
        viewPasswordButton.getStyleClass().addAll("graphic");
        StackPane viewPasswordButtonPane = new StackPane(viewPasswordButton);
        viewPasswordButtonPane.getStyleClass().addAll("view-password-button");
        //Always there, so it can be found before a character is typed. It used to fade in only
        //once the field had text, which hid it at the moment someone looks for it.
        viewPasswordButtonPane.setCursor(Cursor.HAND);
        Tooltip showTip = new Tooltip("Show what you type");
        Tooltip hideTip = new Tooltip("Hide what you type");
        Tooltip.install(viewPasswordButtonPane, showTip);
        viewPasswordButtonPane.setAccessibleText("Show or hide what you type");

        viewPasswordButtonPane.setOnMouseReleased(e -> {
            if(mask) {
                viewPasswordButtonPane.getStyleClass().remove("view-password-button");
                viewPasswordButtonPane.getStyleClass().addAll("hide-password-button");
                mask = false;
                Tooltip.uninstall(viewPasswordButtonPane, showTip);
                Tooltip.install(viewPasswordButtonPane, hideTip);
            } else {
                viewPasswordButtonPane.getStyleClass().remove("hide-password-button");
                viewPasswordButtonPane.getStyleClass().addAll("view-password-button");
                mask = true;
                Tooltip.uninstall(viewPasswordButtonPane, hideTip);
                Tooltip.install(viewPasswordButtonPane, showTip);
            }
            textField.setText(textField.getText());
            textField.end();
        });

        textField.rightProperty().set(viewPasswordButtonPane);
    }

    @Override
    protected String maskText(String txt) {
        if(getSkinnable() instanceof PasswordField && mask) {
            int n = txt.length();
            StringBuilder passwordBuilder = new StringBuilder(n);
            for (int i = 0; i < n; i++) {
                passwordBuilder.append(BULLET);
            }
            return passwordBuilder.toString();
        } else {
            return txt;
        }
    }
}
