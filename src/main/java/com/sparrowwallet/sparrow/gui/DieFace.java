package com.sparrowwallet.sparrow.gui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/**
 * One face of a physical die. The passphrase is rolled with real dice, so the review shows the
 * rolls as dice: the user can check each word against what is on the table in front of them.
 * The one is drawn as a red disc, the way Japanese dice mark it.
 */
public class DieFace extends Canvas {
    private static final int[][][] PIPS = {
            {{1, 1}},
            {{0, 0}, {2, 2}},
            {{0, 0}, {1, 1}, {2, 2}},
            {{0, 0}, {2, 0}, {0, 2}, {2, 2}},
            {{0, 0}, {2, 0}, {1, 1}, {0, 2}, {2, 2}},
            {{0, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {2, 2}}
    };

    public DieFace(int value) {
        this(value, 20);
    }

    public DieFace(int value, double size) {
        super(size, size);
        GraphicsContext g = getGraphicsContext2D();
        g.setFill(Color.web("#ECE9E1"));
        g.fillRoundRect(0.5, 0.5, size - 1, size - 1, size * 0.36, size * 0.36);
        if(value < 1 || value > 6) {
            return;
        }
        double step = size * 0.23, origin = size / 2 - step;
        double r = value == 1 ? size * 0.2 : size * 0.09;
        g.setFill(value == 1 ? Color.web("#CC1111") : Color.web("#15161B"));
        for(int[] pip : PIPS[value - 1]) {
            g.fillOval(origin + pip[0] * step - r, origin + pip[1] * step - r, r * 2, r * 2);
        }
        setAccessibleText("Die showing " + value);
    }
}
