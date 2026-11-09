package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.swap.SwapCopy;
import com.sparrowwallet.sparrow.swap.SwapReview;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.time.Instant;
import java.util.Locale;

/**
 * The swap's final review, drawn in the Swaps screen instead of a dialog: the final terms, then approve
 * and publish the BTC lock. The action bar is pinned below a scrolling body, so Approve and Reject stay
 * in view however much of the terms is scrolled; the bar says what is below. It only draws a
 * {@link SwapReview} and reports the user's choice through its decision.
 */
public class SwapReviewPane extends BorderPane {
    private static final String TITLE = "Send BTC  →  Receive XMR";

    private final VBox content = new VBox(16);
    private final ScrollPane scroll = new ScrollPane(content);
    private final HBox bar = new HBox(16);
    private Timeline countdown;
    private Label expiryChip;
    private SwapReview review;

    public SwapReviewPane() {
        getStyleClass().add("review-pane");
        content.getStyleClass().add("review-content");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("review-scroll");
        bar.getStyleClass().add("review-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        setCenter(scroll);
    }

    /** Draws the review, replacing whatever was shown */
    public void show(SwapReview review) {
        clear();
        this.review = review;
        content.getChildren().clear();
        bar.getChildren().clear();
        setBottom(null);
        buildReview(review);
        scroll.setVvalue(0);
    }

    /** Stops the countdown and forgets the review */
    public void clear() {
        if(countdown != null) {
            countdown.stop();
            countdown = null;
        }
        expiryChip = null;
        review = null;
    }

    /** The text of the countdown chip, for tests and screenshots */
    String expiryText() {
        return expiryChip == null ? null : expiryChip.getText();
    }

    private void buildReview(SwapReview review) {
        content.getChildren().add(titleRow());

        //What you send and what you receive, the two figures the decision is about
        HBox hero = new HBox(20);
        hero.getStyleClass().addAll("ag-card", "review-hero");
        hero.setAlignment(Pos.CENTER_LEFT);
        VBox send = new VBox(6, eyebrow("You send"), amount(review.coinBtc(), null, "BTC"),
                wrapped("Whole Badbank coin, no change. Includes the " + sats(review.feeSats()) + " sat network fee.", "review-fine"));
        Label arrow = label("→", "review-arrow");
        String xmr = review.xmr();
        int split = Math.min(xmr.length(), xmr.indexOf('.') + 4);
        VBox receive = new VBox(6, eyebrow("You receive"), amount(xmr.substring(0, split), xmr.substring(split), "XMR"),
                wrapped("To " + shortAddress(review.xmrAddress()) + " · " + String.format(Locale.ROOT, "%.2f", review.xmrPerBtc())
                        + " XMR per BTC · Maker " + review.maker(), "review-fine"));
        HBox.setHgrow(send, Priority.ALWAYS);
        HBox.setHgrow(receive, Priority.ALWAYS);
        send.setPrefWidth(300);
        receive.setPrefWidth(400);
        hero.getChildren().addAll(send, arrow, receive);

        //What approving commits you to, and what happens if it does not work out. The consequence most
        //easily missed, the maker's deposit, stands out first.
        VBox commitment = card();
        commitment.getChildren().add(eyebrow("Commitment and recovery"));
        if(review.hasDeposit()) {
            Label icon = label("!", "review-callout-icon");
            icon.setAlignment(Pos.CENTER);
            VBox text = new VBox(2, wrapped(SwapCopy.depositHeadline(review.depositBtc(), review.depositPercent()), "review-callout-title"),
                    wrapped(SwapCopy.depositDetail(), "review-fine"));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox callout = new HBox(12, icon, text);
            callout.getStyleClass().add("review-callout");
            callout.setAlignment(Pos.TOP_LEFT);
            commitment.getChildren().add(callout);
        }
        GridPane columns = new GridPane();
        columns.setHgap(22);
        for(int i = 0; i < 3; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100d / 3);
            columns.getColumnConstraints().add(constraints);
        }
        columns.add(section("What Approve does", SwapCopy.approveEffect(review.lockBtc())), 0, 0);
        columns.add(section("If the maker does not complete", SwapCopy.cancelAndRefundSteps(review.refundBtc())), 1, 0);
        columns.add(section("Keep Ashigaru running", SwapCopy.keepRunningDetail()), 2, 0);
        commitment.getChildren().add(columns);

        //The accounting that resolves the figures above, and the full address to check
        VBox transaction = card();
        VBox rows = new VBox();
        rows.getChildren().addAll(
                row("Locked in the swap", review.lockBtc() + " BTC", false),
                row("Network fee (inside the coin)", review.feeBtc() + " BTC", false),
                totalRow("Total leaving Badbank", review.coinBtc() + " BTC"));
        transaction.getChildren().addAll(eyebrow("Transaction"), rows);

        VBox destination = card();
        Button copy = button("Copy", "secondary-btn");
        copy.setOnAction(event -> {
            ClipboardContent clip = new ClipboardContent();
            clip.putString(review.xmrAddress());
            Clipboard.getSystemClipboard().setContent(clip);
            copy.setText("Copied");
            PauseTransition back = new PauseTransition(Duration.seconds(1.5));
            back.setOnFinished(e -> copy.setText("Copy"));
            back.play();
        });
        Label check = wrapped("Check the first and last characters against your wallet.", "review-fine");
        HBox.setHgrow(check, Priority.ALWAYS);
        HBox checkRow = new HBox(12, check, copy);
        checkRow.setAlignment(Pos.CENTER_LEFT);
        destination.getChildren().addAll(eyebrow("Destination · Your Monero address"), addressBox(review.xmrAddress()), checkRow);

        GridPane details = new GridPane();
        details.setHgap(16);
        for(int i = 0; i < 2; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(50);
            details.getColumnConstraints().add(constraints);
        }
        details.add(transaction, 0, 0);
        details.add(destination, 1, 0);
        GridPane.setFillHeight(transaction, true);

        content.getChildren().addAll(hero, commitment, details);

        //Pinned below the scrolling body so the decision is always in reach
        Button reject = button("Reject", "secondary-btn");
        Label note = wrapped("Nothing is published until you press Approve. The fee breakdown and full address are below.", "review-body");
        HBox.setHgrow(note, Priority.ALWAYS);
        note.setMaxWidth(Double.MAX_VALUE);
        Button approve = button("Approve and publish BTC lock", "primary-btn", "review-primary");
        approve.setOnAction(event -> {
            approve.setDisable(true);
            reject.setDisable(true);
            review.decision().confirm();
        });
        reject.setOnAction(event -> {
            approve.setDisable(true);
            reject.setDisable(true);
            review.decision().decline();
        });
        bar.getChildren().addAll(reject, note, approve);
        setBottom(bar);

        startCountdown(review.expiresAt());
    }

    /** The title, and the real deadline for the decision */
    private HBox titleRow() {
        Label title = label(TITLE, "swaps-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, title, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        if(review != null && review.expiresAt() != null) {
            expiryChip = chip("Review expires in " + SwapReview.countdown(java.time.Duration.between(Instant.now(), review.expiresAt())), "review-chip-warn");
            row.getChildren().add(expiryChip);
        }
        return row;
    }

    private void startCountdown(Instant expiresAt) {
        if(expiresAt == null || expiryChip == null) {
            return;
        }
        countdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            java.time.Duration left = java.time.Duration.between(Instant.now(), expiresAt);
            expiryChip.setText(left.isNegative() || left.isZero() ? "Review expired" : "Review expires in " + SwapReview.countdown(left));
        }));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    private static VBox card() {
        VBox card = new VBox(12);
        card.getStyleClass().addAll("ag-card", "review-card");
        return card;
    }

    private static VBox section(String heading, String body) {
        Label title = label(heading, "review-subheading");
        //A heading wraps in its column rather than widening the page
        title.setMinWidth(0);
        title.setWrapText(true);
        title.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(6, title, wrapped(body, "review-body"));
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static HBox row(String key, String value, boolean last) {
        Label k = label(key, "review-key");
        Label v = label(value, "review-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(16, k, spacer, v);
        row.getStyleClass().add("review-row");
        if(last) {
            row.getStyleClass().add("review-row-last");
        }
        row.setAlignment(Pos.BASELINE_LEFT);
        return row;
    }

    private static HBox totalRow(String key, String value) {
        HBox row = row(key, value, true);
        row.getStyleClass().add("review-row-total");
        return row;
    }

    private static TextFlow amount(String lead, String tail, String unit) {
        Text lead1 = new Text(lead);
        lead1.getStyleClass().add("review-amount-lead");
        TextFlow flow = new TextFlow(lead1);
        if(tail != null && !tail.isEmpty()) {
            Text tail1 = new Text(tail);
            tail1.getStyleClass().add("review-amount-tail");
            flow.getChildren().add(tail1);
        }
        Text unit1 = new Text("  " + unit);
        unit1.getStyleClass().add("review-amount-unit");
        flow.getChildren().add(unit1);
        return flow;
    }

    /** The address in groups of five with the first and last emphasised, to be read against another screen */
    private static FlowPane addressBox(String address) {
        FlowPane box = new FlowPane(10, 4);
        box.getStyleClass().add("review-address");
        box.setPadding(new Insets(12, 14, 12, 14));
        int groups = (address.length() + 4) / 5;
        for(int i = 0; i < groups; i++) {
            Label group = label(address.substring(i * 5, Math.min(address.length(), i * 5 + 5)), "review-address-group");
            if(i == 0 || i == groups - 1) {
                group.getStyleClass().add("review-address-edge");
            }
            box.getChildren().add(group);
        }
        return box;
    }

    static String shortAddress(String address) {
        return address == null || address.length() <= 14 ? (address == null ? "" : address)
                : address.substring(0, 6) + "…" + address.substring(address.length() - 6);
    }

    private static Label eyebrow(String text) {
        return label(text.toUpperCase(Locale.ROOT), "review-eyebrow");
    }

    private static Label chip(String text, String style) {
        Label chip = label(text, "review-chip");
        if(!"review-chip".equals(style)) {
            chip.getStyleClass().add(style);
        }
        return chip;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        //A short label keeps the width of its text, so a crowded row never turns it into an ellipsis
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    /** A button keeps the width of its text: Approve and Reject are never shortened to fit */
    private static Button button(String text, String... styles) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styles);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static String sats(long sats) {
        return String.format(Locale.ROOT, "%,d", sats);
    }

    private static Label wrapped(String text, String style) {
        Label label = label(text, style);
        label.setMinWidth(0);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }
}
