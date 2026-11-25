package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** The rows of a Verifier or Sign result: a check mark or cross and what was checked, with the value it produced */
final class VerifierRows {
    private VerifierRows() {}

    static VBox check(boolean passed, String text, String value) {
        Label label = new Label(text);
        label.getStyleClass().addAll("verify-step", passed ? "verifier-step-pass" : "verifier-step-fail");
        label.setGraphic(Icons.of(passed ? FontAwesome5.Glyph.CHECK : FontAwesome5.Glyph.TIMES, 12));
        label.setGraphicTextGap(11);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        VBox row = new VBox(label);
        if(value != null) {
            Label address = new Label(value);
            address.getStyleClass().add("verifier-value");
            address.setWrapText(true);
            address.setMinHeight(Region.USE_PREF_SIZE);
            HBox.setHgrow(address, Priority.ALWAYS);
            CopyButton copy = new CopyButton();
            copy.setOnAction(event -> copy.copy(value));
            HBox valueRow = new HBox(8, address, copy);
            valueRow.setAlignment(Pos.CENTER_LEFT);
            VBox.setMargin(valueRow, new Insets(-6, 0, 8, 25));
            row.getChildren().add(valueRow);
        }
        return row;
    }
}
