package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.UnitFormat;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the money goes in a Tx0, drawn to scale. The selected coins enter on the left and leave as
 * four ribbons: the premix outputs, the change that lands in Badbank, the anti-Sybil fee and the
 * miner fee. The label-and-value grid beside it gives the exact figures; this shows their
 * proportions, and that the change has a destination.
 */
public class Tx0Flow extends Canvas {
    private static final Color INK = Color.web("#ECECEC"), DIM = Color.web("#A6A7AD"), FAINT = Color.web("#8B8D95");
    private static final Color INPUT = Color.web("#C9CAD0"), PREMIX = Color.web("#CC1111"), PREMIX_INK = Color.web("#FF5A4D");
    private static final Color CHANGE = Color.web("#E5A00C"), CHANGE_INK = Color.web("#F2B53A"), FEE = Color.web("#6B6E79");
    private static final double TOP = 34, HEIGHT = 300, X_IN = 16, X_NODE = 120, NODE_W = 16, X_OUT = 216, BAR = 12, MIN_BLOCK = 13, GAP = 22;

    private record Out(long sats, Color fill, Color ink, String value, String label) {}

    public Tx0Flow() {
        super(380, 380);
        clear("Choose a pool to see where the coins go.");
    }

    public void clear(String message) {
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());
        g.setFill(FAINT);
        g.setFont(Font.font("Inter", 12));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(message, getWidth() / 2, getHeight() / 2);
    }

    public void show(long total, int premixCount, long perPremix, long change, long poolFee, long minerFee) {
        if(total <= 0) {
            clear("Nothing selected.");
            return;
        }
        UnitFormat fmt = UnitFormat.DOT;
        List<Out> outs = new ArrayList<>();
        outs.add(new Out((long) premixCount * perPremix, PREMIX, PREMIX_INK, premixCount + " × " + fmt.formatBtcValue(perPremix), "To Premix"));
        outs.add(new Out(change, CHANGE, CHANGE_INK, fmt.formatBtcValue(change), "Change → Badbank"));
        outs.add(new Out(poolFee, FEE, DIM, fmt.formatBtcValue(poolFee), "Anti-Sybil fee"));
        outs.add(new Out(minerFee, FEE, DIM, fmt.formatBtcValue(minerFee), "Miner fee"));
        outs.removeIf(o -> o.sats() <= 0);

        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());
        long sum = outs.stream().mapToLong(Out::sats).sum();
        double scale = HEIGHT / Math.max(sum, 1);

        //Input bar and the transaction itself
        g.setFill(INPUT);
        g.fillRoundRect(X_IN, TOP, BAR, HEIGHT, 3, 3);
        band(g, X_IN + BAR, TOP, TOP + HEIGHT, X_NODE, TOP, TOP + HEIGHT, Color.web("#A6A7AD", 0.2));
        g.setFill(Color.web("#23252D"));
        g.fillRoundRect(X_NODE, TOP, NODE_W, HEIGHT, 3, 3);
        g.setFill(PREMIX);
        g.fillRect(X_NODE, TOP, NODE_W, 3);
        g.setFill(INK);
        g.setFont(Font.font("Nikkyou Sans", 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BASELINE);
        g.fillText("TX0", X_NODE + NODE_W / 2, TOP - 12);
        g.setFill(FAINT);
        g.setFont(Font.font("Roboto Mono", 10.5));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(fmt.formatBtcValue(total) + " in", X_IN, TOP + HEIGHT + 20);

        //Output blocks: to scale, except that small ones are enlarged enough to stay legible
        double[] heights = new double[outs.size()];
        double small = 0, big = 0;
        for(int i = 0; i < outs.size(); i++) {
            double h = outs.get(i).sats() * scale;
            heights[i] = Math.max(h, MIN_BLOCK);
            if(h <= MIN_BLOCK) small += heights[i]; else big += heights[i];
        }
        double gaps = GAP * (outs.size() - 1), room = HEIGHT + 30;
        if(small + big + gaps > room && big > 0) {
            double squeeze = (room - gaps - small) / big;
            for(int i = 0; i < heights.length; i++) {
                if(outs.get(i).sats() * scale > MIN_BLOCK) heights[i] *= squeeze;
            }
        }
        double used = gaps;
        for(double h : heights) used += h;
        double outY = Math.max(8, TOP + (HEIGHT - used) / 2), srcY = TOP;
        for(int i = 0; i < outs.size(); i++) {
            Out o = outs.get(i);
            double seg = o.sats() * scale;
            band(g, X_NODE + NODE_W, srcY, srcY + seg, X_OUT, outY, outY + heights[i], o.fill().deriveColor(0, 1, 1, i < 2 ? 0.42 : 0.3));
            g.setFill(o.fill());
            g.fillRoundRect(X_OUT, outY, BAR, heights[i], 3, 3);
            g.setTextBaseline(VPos.CENTER);
            g.setFill(i < 2 ? INK : DIM);
            g.setFont(Font.font("Roboto Mono", 11.5));
            g.fillText(o.value(), X_OUT + BAR + 12, outY + heights[i] / 2 - 7);
            g.setFill(o.ink());
            g.setFont(Font.font("Inter", 11));
            g.fillText(o.label(), X_OUT + BAR + 12, outY + heights[i] / 2 + 8);
            srcY += seg;
            outY += heights[i] + GAP;
        }
    }

    private static void band(GraphicsContext g, double x0, double y0, double y1, double x1, double z0, double z1, Color fill) {
        double mid = (x0 + x1) / 2;
        g.setFill(fill);
        g.beginPath();
        g.moveTo(x0, y0);
        g.bezierCurveTo(mid, y0, mid, z0, x1, z0);
        g.lineTo(x1, z1);
        g.bezierCurveTo(mid, z1, mid, y1, x0, y1);
        g.closePath();
        g.fill();
    }
}
