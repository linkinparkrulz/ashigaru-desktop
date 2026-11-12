package com.sparrowwallet.sparrow.gui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * How much of the wallet is private, as a ring: Postmix, then Premix, Deposit and Badbank. The
 * colours carry the same meaning everywhere in the app: green is private, red is the brand's
 * "in progress", grey is untouched and amber needs attention.
 */
public class PrivacyDonut extends Canvas {
    public static final Color PRIVATE = Color.web("#4CAF50");
    public static final Color MIXING = Color.web("#CC1111");
    public static final Color UNMIXED = Color.web("#6B6E79");
    public static final Color BADBANK = Color.web("#E5A00C");
    private static final Color TRACK = Color.web("#23252D");
    private static final double STROKE = 10;
    private static final double GAP_DEGREES = 3;

    public PrivacyDonut() {
        super(96, 96);
    }

    public void show(WalletPrivacy privacy) {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        g.clearRect(0, 0, w, h);
        double inset = STROKE / 2 + 1;
        g.setLineWidth(STROKE);
        g.setLineCap(StrokeLineCap.BUTT);
        g.setStroke(TRACK);
        g.strokeOval(inset, inset, w - inset * 2, h - inset * 2);

        long total = privacy.total();
        if(total == 0) {
            return;
        }
        long[] parts = {privacy.postmix().sats(), privacy.premix().sats(), privacy.deposit().sats(), privacy.badbank().sats()};
        Color[] colors = {PRIVATE, MIXING, UNMIXED, BADBANK};
        long segments = java.util.Arrays.stream(parts).filter(p -> p > 0).count();
        double gap = segments > 1 ? GAP_DEGREES : 0;
        //Starts at twelve o'clock and runs clockwise; JavaFX measures arcs anticlockwise from three.
        double start = 90;
        for(int i = 0; i < parts.length; i++) {
            if(parts[i] <= 0) {
                continue;
            }
            double sweep = 360.0 * parts[i] / total;
            //A sliver still gets drawn: Badbank is usually tiny and is the part worth noticing.
            double drawn = Math.max(sweep - gap, 2.5);
            g.setStroke(colors[i]);
            g.strokeArc(inset, inset, w - inset * 2, h - inset * 2, start - drawn, drawn, ArcType.OPEN);
            start -= sweep;
        }
    }
}
