package com.sparrowwallet.sparrow.gui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * A one-line picture of where coins are on the Whirlpool pipeline. Receive and Mix To are the two
 * ends of the same line the sidebar draws, so each carries a strip saying where its coins land.
 */
public class PipelineStrip extends HBox {
    public PipelineStrip() {
        getStyleClass().add("pipeline-strip");
        setAlignment(Pos.CENTER_LEFT);
        showReceive();
    }

    /** Deposit is the start of the line: coins arrive there unmixed. */
    public final void showReceive() {
        getChildren().setAll(stop("DEPOSIT", "on"), line(false), stop("PREMIX", null), line(false), stop("POSTMIX", null));
    }

    /** Postmix coins leave for the named wallet once they reach the minimum mix count. */
    public final void showMixTo(int minMixes, String destination) {
        boolean set = destination != null && !destination.isBlank();
        getChildren().setAll(stop("POSTMIX", "on"), line(set),
                stop(minMixes + (minMixes == 1 ? " MIX" : " MIXES"), "gate"), line(set),
                stop(set ? destination.toUpperCase() : "NO DESTINATION", set ? "done" : null));
    }

    private static Label stop(String text, String state) {
        Region node = new Region();
        node.getStyleClass().add("strip-node");
        Label label = new Label(text, node);
        label.setGraphicTextGap(7);
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.getStyleClass().add("strip-stop");
        if(state != null) {
            label.getStyleClass().add(state);
        }
        return label;
    }

    private static Region line(boolean active) {
        Region line = new Region();
        line.getStyleClass().add("strip-line");
        if(active) {
            line.getStyleClass().add("active");
        }
        line.setMinWidth(12);
        HBox.setHgrow(line, Priority.ALWAYS);
        return line;
    }
}
