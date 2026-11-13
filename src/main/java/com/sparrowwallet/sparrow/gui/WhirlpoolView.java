package com.sparrowwallet.sparrow.gui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * A coin's privacy is its mix count, so this draws it. Every ring is a completed mix: Premix coins
 * on the outside, coins that have reached the wallet's minimum at the centre, around the eagle.
 * Coins in the round happening now are red and tied to the core.
 *
 * <p>A third view beside UTXOs and Transactions; it reads the same per-coin mix counts they do.
 */
public class WhirlpoolView extends HBox {
    /** One coin: how many mixes it has completed, and whether it is in a round right now. */
    public record Coin(int mixes, boolean active) {}

    private static final int RINGS = 4;
    private static final int MAX_DRAWN_PER_RING = 36;
    private static final double[] RADIUS = {0.90, 0.72, 0.54, 0.36};
    private static final double[] DEGREES_PER_SECOND = {4.2, -3.2, 2.4, -1.8};
    private static final Color INK = Color.web("#ECECEC"), FAINT = Color.web("#8B8D95"), LINE = Color.web("#2E3038"),
            LINE_STRONG = Color.web("#5B5E68"), CANVAS = Color.web("#0E0F12"), RED = Color.web("#CC1111"),
            RED_INK = Color.web("#FF5A4D"), GREEN = Color.web("#4CAF50"), GREEN_INK = Color.web("#6BCB77"), IDLE = Color.web("#C9CAD0");

    private final Canvas canvas = new Canvas(420, 420);
    private final Pane canvasHolder = new Pane(canvas);
    private final Image logo = new Image(WhirlpoolView.class.getResourceAsStream("/image/Ashigaru_Terminal_Logo_Circle.png"), 176, 176, true, true);
    private final List<List<Coin>> rings = new ArrayList<>();
    private final Label[] counts = new Label[RINGS];
    private final Region[] bars = new Region[RINGS];
    private final Label summary = new Label();
    private final Label round = new Label();
    private int minMixes = 3;
    private long startNanos;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if(startNanos == 0) {
                startNanos = now;
            }
            draw((now - startNanos) / 1e9);
        }
    };

    public WhirlpoolView() {
        getStyleClass().add("whirlpool-view");
        setSpacing(18);
        setPadding(new Insets(16, 24, 16, 24));
        for(int i = 0; i < RINGS; i++) {
            rings.add(new ArrayList<>());
        }
        HBox.setHgrow(canvasHolder, Priority.ALWAYS);
        canvasHolder.setMinSize(200, 200);
        canvasHolder.widthProperty().addListener(o -> fit());
        canvasHolder.heightProperty().addListener(o -> fit());

        VBox side = new VBox(12, roundCard(), histogramCard(), note());
        side.setMinWidth(300);
        side.setMaxWidth(300);
        getChildren().addAll(canvasHolder, side);

        //Only animate while on screen
        sceneProperty().addListener((obs, old, scene) -> { if(scene == null) timer.stop(); });
        visibleProperty().addListener((obs, was, is) -> { if(is) timer.start(); else timer.stop(); });
    }

    private VBox roundCard() {
        Label title = new Label("LIVE ROUND");
        title.getStyleClass().add("hero-caption");
        round.getStyleClass().add("ag-read");
        round.setWrapText(true);
        round.setMinHeight(Region.USE_PREF_SIZE);
        VBox card = new VBox(8, title, round);
        card.getStyleClass().add("whirlpool-card");
        return card;
    }

    private VBox histogramCard() {
        Label title = new Label("MIXES COMPLETED");
        title.getStyleClass().add("hero-caption");
        summary.getStyleClass().add("legend-value");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox head = new HBox(title, grow, summary);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(9, head);
        card.getStyleClass().add("whirlpool-card");
        for(int i = 0; i < RINGS; i++) {
            Label name = new Label();
            name.getStyleClass().add("legend-name");
            name.setMinWidth(74);
            name.setUserData(i);
            bars[i] = new Region();
            bars[i].getStyleClass().addAll("whirlpool-bar", i == RINGS - 1 ? "private" : i == 0 ? "waiting" : "mixed");
            StackPane track = new StackPane(bars[i]);
            track.getStyleClass().add("whirlpool-track");
            track.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(track, Priority.ALWAYS);
            counts[i] = new Label("0");
            counts[i].getStyleClass().add("legend-value");
            counts[i].setMinWidth(26);
            counts[i].setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(10, name, track, counts[i]);
            row.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(row);
        }
        return card;
    }

    private Label note() {
        Label note = new Label("Remixing is free. Leave Postmix coins available and they keep moving inward at no cost.");
        note.getStyleClass().addAll("ag-read", "faint", "whirlpool-note");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);
        return note;
    }

    /**
     * @param coins     every Premix and Postmix coin in the wallet
     * @param minMixes  the wallet's own "private enough" count; the innermost ring holds coins at or past it
     */
    public void show(List<Coin> coins, int minMixes) {
        this.minMixes = Math.max(minMixes, RINGS - 1);
        rings.forEach(List::clear);
        int active = 0;
        for(Coin coin : coins) {
            int ring = coin.mixes() >= this.minMixes ? RINGS - 1 : Math.min(coin.mixes(), RINGS - 2);
            rings.get(ring).add(coin);
            if(coin.active()) {
                active++;
            }
        }
        int max = rings.stream().mapToInt(List::size).max().orElse(0);
        for(int i = 0; i < RINGS; i++) {
            int n = rings.get(i).size();
            counts[i].setText(String.valueOf(n));
            bars[i].setMaxWidth(Region.USE_PREF_SIZE);
            bars[i].prefWidthProperty().unbind();
            double share = max == 0 ? 0 : (double) n / max;
            bars[i].prefWidthProperty().bind(((Region) bars[i].getParent()).widthProperty().multiply(share));
            HBox row = (HBox) bars[i].getParent().getParent();
            ((Label) row.getChildren().get(0)).setText(ringName(i));
        }
        summary.setText(coins.size() + (coins.size() == 1 ? " coin" : " coins"));
        round.setText(coins.isEmpty() ? "No coins in Premix or Postmix yet. Run a Tx0 from Deposit to start."
                : active == 0 ? "None of your coins is in a round right now."
                : active + (active == 1 ? " of your coins is" : " of your coins are") + " in a round right now, shown in red.");
        draw(0);
    }

    private String ringName(int ring) {
        if(ring == 0) return "0 · PREMIX";
        if(ring == RINGS - 1) return minMixes + "+ MIXES";
        return ring + (ring == 1 ? " MIX" : " MIXES");
    }

    private void fit() {
        double size = Math.max(200, Math.min(canvasHolder.getWidth(), canvasHolder.getHeight()));
        canvas.setWidth(size);
        canvas.setHeight(size);
        canvas.setLayoutX((canvasHolder.getWidth() - size) / 2);
        canvas.setLayoutY((canvasHolder.getHeight() - size) / 2);
        draw(0);
    }

    private void draw(double seconds) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double size = canvas.getWidth(), c = size / 2, R = size / 2 - 14;
        g.clearRect(0, 0, size, size);

        //Bezel ticks
        for(int d = 0; d < 360; d += 5) {
            boolean big = d % 30 == 0;
            double a = Math.toRadians(d), r1 = R + 3, r2 = r1 + (big ? 8 : 4);
            g.setStroke(big ? LINE_STRONG : LINE);
            g.setLineWidth(big ? 1.3 : 1);
            g.strokeLine(c + Math.cos(a) * r1, c + Math.sin(a) * r1, c + Math.cos(a) * r2, c + Math.sin(a) * r2);
        }
        //Orbits
        g.setLineWidth(1);
        for(int i = 0; i < RINGS; i++) {
            double r = R * RADIUS[i];
            g.setStroke(i == 0 ? RED.deriveColor(0, 1, 1, 0.5) : i == RINGS - 1 ? GREEN.deriveColor(0, 1, 1, 0.55) : LINE);
            g.setLineDashes(i == 0 ? new double[] {2, 5} : null);
            g.strokeOval(c - r, c - r, r * 2, r * 2);
        }
        g.setLineDashes(null);
        //Core
        double core = R * 0.2;
        g.setStroke(RED.deriveColor(0, 1, 1, 0.7));
        g.setLineWidth(1.5);
        g.setFill(CANVAS);
        g.fillOval(c - core, c - core, core * 2, core * 2);
        g.strokeOval(c - core, c - core, core * 2, core * 2);
        double img = core * 1.7;
        g.drawImage(logo, c - img / 2, c - img / 2, img, img);
        //Ring labels, at twelve o'clock
        g.setFont(Font.font("Roboto Mono", 10));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        for(int i = 0; i < RINGS; i++) {
            double y = c - R * RADIUS[i];
            String text = i == RINGS - 1 ? minMixes + "+" : String.valueOf(i);
            g.setFill(CANVAS);
            g.fillRect(c - 12, y - 7, 24, 14);
            g.setFill(i == RINGS - 1 ? GREEN_INK : FAINT);
            g.fillText(text, c, y);
        }
        //Coins
        for(int i = 0; i < RINGS; i++) {
            List<Coin> coins = rings.get(i);
            int drawn = Math.min(coins.size(), MAX_DRAWN_PER_RING);
            double r = R * RADIUS[i];
            for(int k = 0; k < drawn; k++) {
                Coin coin = coins.get(k);
                double angle = Math.toRadians(360.0 * k / drawn + i * 37 + 24 + DEGREES_PER_SECOND[i] * seconds);
                double x = c + Math.cos(angle) * r, y = c + Math.sin(angle) * r;
                if(coin.active()) {
                    g.setStroke(RED_INK.deriveColor(0, 1, 1, 0.7));
                    g.setLineWidth(1.2);
                    g.setLineDashes(3, 6);
                    g.strokeLine(x, y, c + Math.cos(angle) * (core + 4), c + Math.sin(angle) * (core + 4));
                    g.setLineDashes(null);
                    g.setFill(RED.deriveColor(0, 1, 1, 0.25));
                    g.fillOval(x - 12, y - 12, 24, 24);
                    dot(g, x, y, 7, RED_INK, Color.WHITE, 1.5);
                } else if(i == 0) {
                    dot(g, x, y, 6, Color.web("#15161B"), Color.web("#A6A7AD"), 1.6);
                } else if(i == RINGS - 1) {
                    dot(g, x, y, 6, GREEN, Color.web("#BFE8C4"), 1);
                } else {
                    dot(g, x, y, 6, IDLE, Color.web("#15161B"), 1.5);
                }
            }
        }
    }

    private static void dot(GraphicsContext g, double x, double y, double r, Color fill, Color stroke, double width) {
        g.setFill(fill);
        g.fillOval(x - r, y - r, r * 2, r * 2);
        g.setStroke(stroke);
        g.setLineWidth(width);
        g.strokeOval(x - r, y - r, r * 2, r * 2);
    }
}
