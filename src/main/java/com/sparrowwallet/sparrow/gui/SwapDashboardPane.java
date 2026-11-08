package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.swap.SwapCopy;
import com.sparrowwallet.sparrow.swap.SwapDashboard;
import com.sparrowwallet.sparrow.swap.SwapDashboard.Action;
import com.sparrowwallet.sparrow.swap.SwapDashboard.Fact;
import com.sparrowwallet.sparrow.swap.SwapDashboard.Tone;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.*;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * The swap dashboard: what is happening, where the funds are, whether the user needs to act, whether
 * Ashigaru can be closed, and what happens if the swap stalls. It draws a {@link SwapDashboard} and
 * reports the one action the user can take; the working-out is in the model.
 *
 * <p>Top to bottom: the banner (what to do), the named milestones, the current step beside the
 * summary of the swap, the state of the connections, and the technical details, folded away.
 */
public class SwapDashboardPane extends VBox {
    /** The services a swap depends on, as far as Ashigaru can tell: which work, and what is wrong with the rest */
    public record Connections(List<String> working, List<String> problems) {}

    private Consumer<Action> onAction = action -> {};
    private boolean detailsExpanded;
    private String message;

    public SwapDashboardPane() {
        super(16);
        getStyleClass().add("dash-pane");
    }

    /** Called when the user presses the banner's button */
    public void setOnAction(Consumer<Action> onAction) {
        this.onAction = onAction;
    }

    /** A failure to show under the banner, such as a refund that could not be started; null clears it */
    public void setMessage(String message) {
        this.message = message;
    }

    /** Draws the dashboard, replacing what was shown */
    public void show(String swapId, SwapDashboard dashboard, Connections connections) {
        getChildren().clear();
        Label eyebrow = label("YOUR SWAP  ·  " + swapId.substring(0, Math.min(8, swapId.length())), "dash-eyebrow");
        getChildren().add(eyebrow);
        getChildren().add(banner(dashboard.banner()));
        if(message != null && !message.isBlank()) {
            getChildren().add(wrapped(message, "dash-message"));
        }
        getChildren().add(milestones(dashboard.milestones()));

        GridPane columns = new GridPane();
        columns.setHgap(16);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(55.5);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(44.5);
        columns.getColumnConstraints().addAll(left, right);
        columns.add(nowCard(dashboard.now()), 0, 0);
        columns.add(summaryCard(dashboard.summary()), 1, 0);
        GridPane.setValignment(columns.getChildren().get(0), javafx.geometry.VPos.TOP);
        GridPane.setValignment(columns.getChildren().get(1), javafx.geometry.VPos.TOP);
        getChildren().add(columns);

        getChildren().add(connectionsLine(connections));
        getChildren().add(technicalDetails(dashboard));
    }

    private Region banner(SwapDashboard.Banner banner) {
        Label icon = label(banner.tone() == Tone.GOOD ? "✓" : banner.tone() == Tone.WARN ? "!" : "i", "dash-banner-icon");
        icon.setAlignment(Pos.CENTER);
        VBox text = new VBox(2, wrapped(banner.title(), "dash-banner-title"), wrapped(banner.text(), "dash-banner-text"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(14, icon, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("dash-banner", banner.tone() == Tone.GOOD ? "dash-banner-good" : banner.tone() == Tone.WARN ? "dash-banner-warn" : "dash-banner-info");
        if(banner.cta() != null) {
            SwapDashboard.Cta cta = banner.cta();
            Button button = new Button(cta.label());
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.getStyleClass().add(cta.action() == Action.BACK_TO_OFFERS ? "secondary-btn" : "primary-btn");
            button.setDisable(!cta.enabled());
            button.setOnAction(event -> {
                button.setDisable(true);
                onAction.accept(cta.action());
            });
            VBox action = new VBox(4, button);
            action.setAlignment(Pos.CENTER_RIGHT);
            if(cta.note() != null) {
                Label note = label(cta.note(), "dash-fine");
                note.setWrapText(true);
                note.setMaxWidth(200);
                note.setMinWidth(0);
                note.setMinHeight(Region.USE_PREF_SIZE);
                note.setAlignment(Pos.CENTER_RIGHT);
                note.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
                action.getChildren().add(note);
            }
            box.getChildren().add(action);
        }
        return box;
    }

    /** Named stages, not a percentage: a bar at 80% would say "nearly finished" about waits of unknown length */
    private Region milestones(SwapDashboard.Milestones milestones) {
        VBox card = card();
        int n = milestones.labels().size();
        GridPane grid = new GridPane();
        for(int i = 0; i < n; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100d / n);
            grid.getColumnConstraints().add(constraints);
        }
        for(int i = 0; i < n; i++) {
            boolean done = i < milestones.current() && !(milestones.recovery() && i == 0);
            boolean current = i == milestones.current();
            boolean marker = milestones.recovery() && i == 0;
            Label node = label(marker ? "!" : done ? "✓" : current ? "●" : String.valueOf(i + 1), "dash-node");
            node.setAlignment(Pos.CENTER);
            node.getStyleClass().add(marker ? "dash-node-stop" : done ? "dash-node-done" : current ? (milestones.attention() ? "dash-node-warn" : "dash-node-current") : "dash-node-todo");
            Region before = line(i > 0, i <= milestones.current() ? (milestones.attention() && i == milestones.current() ? "dash-line-warn" : "dash-line-done") : "dash-line-todo");
            if(milestones.recovery() && i == 1) {
                before.getStyleClass().remove("dash-line-done");
                before.getStyleClass().add("dash-line-warn");
            }
            Region after = line(i < n - 1, i + 1 <= milestones.current() ? (milestones.attention() && i + 1 == milestones.current() ? "dash-line-warn" : "dash-line-done") : "dash-line-todo");
            if(milestones.recovery() && i + 1 == 1) {
                after.getStyleClass().remove("dash-line-done");
                after.getStyleClass().add("dash-line-warn");
            }
            HBox marker1 = new HBox(before, node, after);
            marker1.setAlignment(Pos.CENTER);
            HBox.setHgrow(before, Priority.ALWAYS);
            HBox.setHgrow(after, Priority.ALWAYS);
            Label name = wrapped(milestones.labels().get(i), current || done ? "dash-ms-name-on" : "dash-ms-name");
            name.setAlignment(Pos.TOP_CENTER);
            name.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            String noteText = i < milestones.notes().size() ? milestones.notes().get(i) : "";
            Label note = wrapped(noteText, "dash-fine");
            note.setAlignment(Pos.TOP_CENTER);
            note.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            VBox column = new VBox(8, marker1, name, note);
            column.setAlignment(Pos.TOP_CENTER);
            column.setPadding(new Insets(0, 4, 0, 4));
            grid.add(column, i, 0);
        }
        card.getChildren().add(grid);
        if(milestones.stoppedAt() != null) {
            Label chip = label("Where it stopped", "dash-chip-warn");
            HBox context = new HBox(10, chip, wrapped(milestones.stoppedAt(), "dash-body"));
            context.setAlignment(Pos.CENTER_LEFT);
            context.getStyleClass().add("dash-context");
            card.getChildren().add(context);
        }
        return card;
    }

    private static Region line(boolean visible, String style) {
        Region line = new Region();
        line.getStyleClass().addAll("dash-line", style);
        line.setMinWidth(0);
        line.setPrefHeight(2);
        line.setMaxHeight(2);
        line.setVisible(visible);
        return line;
    }

    private Region nowCard(SwapDashboard.Now now) {
        VBox card = card();
        card.setSpacing(12);
        Label title = wrapped(now.title(), "swaps-step-title");
        card.getChildren().add(title);
        card.getChildren().add(wrapped(now.doing(), "dash-doing"));
        if(!now.facts().isEmpty()) {
            VBox facts = new VBox(6);
            facts.getStyleClass().add("dash-facts");
            for(Fact fact : now.facts()) {
                facts.getChildren().add(factRow(fact.label(), fact.value()));
            }
            card.getChildren().add(facts);
        }
        if(now.waitingOn() != null) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(16, 16);
            spinner.setMinSize(16, 16);
            spinner.getStyleClass().add("dash-spinner");
            String usual = now.usual() == null ? "" : ", usually under " + SwapCopy.about(now.usual()).replace("about ", "");
            HBox wait = new HBox(10, spinner, wrapped("Waiting on " + now.waitingOn() + ": " + minutes(now.waited()) + " so far" + usual, "dash-body"));
            wait.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(wait);
        }
        if(now.slow() != null) {
            card.getChildren().add(wrapped(now.slow(), "dash-slow"));
        }
        if(now.next() != null) {
            card.getChildren().add(wrapped("Next: " + now.next(), "dash-body"));
        }
        return card;
    }

    private Region summaryCard(SwapDashboard.Summary summary) {
        VBox card = card();
        card.setSpacing(0);
        card.getChildren().add(label("Your swap", "review-subheading"));
        VBox rows = new VBox();
        rows.setPadding(new Insets(8, 0, 0, 0));
        rows.getChildren().addAll(dataRow("BTC committed", summary.btcCommitted()), dataRow(summary.xmrLabel(), summary.xmr()),
                dataRow("Receiving address", summary.address()));
        card.getChildren().add(rows);

        card.getChildren().add(section("Where your funds are", wrapped(summary.funds(), "dash-body-text")));

        Label pill = label(summary.closing().label(), summary.closing().tone() == Tone.GOOD ? "dash-pill-good" : "dash-pill-warn");
        card.getChildren().add(section("Can I close Ashigaru?", new VBox(8, pill, wrapped(summary.closing().text(), "dash-body-text"))));

        VBox recovery = new VBox(8, wrapped(summary.recoveryText(), "dash-body-text"));
        if(summary.timeline() != null) {
            recovery.getChildren().add(new RecoveryTimeline(summary.timeline(), summary.timelineCancelOpen()));
        }
        card.getChildren().add(section(summary.recoveryTitle(), recovery));
        return card;
    }

    private Region connectionsLine(Connections connections) {
        VBox box = new VBox(6);
        if(connections != null) {
            if(!connections.working().isEmpty()) {
                Region dot = dot("dash-dot-good");
                HBox line = new HBox(10, dot, label(connections.problems().isEmpty() ? "All swap connections working" : "Working", "dash-body"),
                        label(String.join(" · ", connections.working()), "dash-fine"));
                line.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().add(line);
            }
            for(String problem : connections.problems()) {
                HBox line = new HBox(10, dot("dash-dot-warn"), wrapped(problem, "dash-body"));
                line.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().add(line);
            }
        }
        return box;
    }

    /** Protocol steps and identifiers, for the person who wants them, folded away for everyone else */
    private Region technicalDetails(SwapDashboard dashboard) {
        GridPane inner = new GridPane();
        inner.setHgap(28);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(50);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(50);
        inner.getColumnConstraints().addAll(left, right);

        VBox steps = new VBox(5, label("PROTOCOL STEPS", "dash-eyebrow"));
        for(int i = 0; i < dashboard.protocolSteps().size(); i++) {
            boolean done = i < dashboard.protocolCurrent();
            boolean current = i == dashboard.protocolCurrent();
            Label step = label((done ? "✓  " : current ? "●  " : "○  ") + dashboard.protocolSteps().get(i), current ? "dash-step-now" : done ? "dash-step-done" : "dash-step-todo");
            steps.getChildren().add(step);
        }
        VBox ids = new VBox(8, label("IDENTIFIERS", "dash-eyebrow"));
        for(Fact fact : dashboard.summary().identifiers()) {
            ids.getChildren().add(identifier(fact.label(), fact.value()));
        }
        steps.setMinWidth(0);
        ids.setMinWidth(0);
        inner.setMinWidth(0);
        inner.add(steps, 0, 0);
        inner.add(ids, 1, 0);
        inner.setPadding(new Insets(4, 0, 8, 0));

        TitledPane pane = new TitledPane("Technical details", inner);
        pane.getStyleClass().add("dash-details");
        pane.setExpanded(detailsExpanded);
        pane.expandedProperty().addListener((observable, was, expanded) -> detailsExpanded = expanded);
        return pane;
    }

    /**
     * A name and a long value, such as an address: the value wraps in groups of five characters, so it never
     * widens the page, and stays readable character by character
     */
    private static VBox identifier(String name, String value) {
        VBox box = new VBox(2, label(name, "dash-fine"));
        box.setMinWidth(0);
        if(value.length() > 24) {
            FlowPane groups = new FlowPane(8, 2);
            groups.setMinWidth(0);
            for(int i = 0; i < value.length(); i += 5) {
                groups.getChildren().add(label(value.substring(i, Math.min(value.length(), i + 5)), "dash-fact-value"));
            }
            box.getChildren().add(groups);
        } else {
            box.getChildren().add(label(value, "dash-fact-value"));
        }
        return box;
    }

    private static Region dot(String style) {
        Region dot = new Region();
        dot.getStyleClass().addAll("dash-dot", style);
        dot.setMinSize(8, 8);
        dot.setPrefSize(8, 8);
        dot.setMaxSize(8, 8);
        return dot;
    }

    /** A name and its value on one line; either wraps when the card is narrow, so a long value never widens the page */
    private static HBox factRow(String label, String value) {
        Label k = wrapped(label, "dash-fact-key");
        Label v = wrapped(value, "dash-fact-value");
        v.setAlignment(Pos.TOP_RIGHT);
        v.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
        HBox row = new HBox(12, k, v);
        HBox.setHgrow(k, Priority.SOMETIMES);
        HBox.setHgrow(v, Priority.ALWAYS);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static HBox dataRow(String key, String value) {
        Label k = label(key, "review-key");
        Label v = label(value, "review-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(16, k, spacer, v);
        row.getStyleClass().add("review-row");
        row.setAlignment(Pos.BASELINE_LEFT);
        return row;
    }

    private static VBox section(String title, Region body) {
        VBox box = new VBox(8, label(title.toUpperCase(java.util.Locale.ROOT), "review-eyebrow"), body);
        box.getStyleClass().add("dash-section");
        return box;
    }

    private static VBox card() {
        VBox card = new VBox(12);
        card.getStyleClass().addAll("ag-card", "review-card");
        return card;
    }

    private static String minutes(Duration duration) {
        long minutes = duration == null ? 0 : duration.toMinutes();
        return minutes < 1 ? "less than a minute" : minutes + " min";
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label wrapped(String text, String style) {
        Label label = label(text, style);
        label.setMinWidth(0);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /**
     * Where the swap is against its cancel timelock: lock confirmed, cancel available, and the end of the
     * refund window. It is a clock for recovery and not a measure of the swap's progress, and the marks
     * are evenly spaced, not to scale.
     */
    static final class RecoveryTimeline extends VBox {
        RecoveryTimeline(double position, boolean cancelOpen) {
            super(8);
            getStyleClass().add("dash-timeline");
            Track track = new Track(Math.max(0, Math.min(1, position)), cancelOpen);
            //Equal columns whose text wraps, so the labels never decide how wide the page has to be
            GridPane labels = new GridPane();
            for(int i = 0; i < 3; i++) {
                ColumnConstraints column = new ColumnConstraints();
                column.setPercentWidth(100d / 3);
                labels.getColumnConstraints().add(column);
            }
            Label lock = wrapped("Lock confirmed", "dash-fine");
            Label cancel = wrapped("Cancel available", "dash-fine");
            Label end = wrapped("Refund window ends", "dash-fine");
            lock.setAlignment(Pos.TOP_LEFT);
            cancel.setAlignment(Pos.TOP_CENTER);
            cancel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            end.setAlignment(Pos.TOP_RIGHT);
            end.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
            labels.add(lock, 0, 0);
            labels.add(cancel, 1, 0);
            labels.add(end, 2, 0);
            getChildren().addAll(track, labels);
            setPadding(new Insets(4, 0, 0, 0));
        }

        /** The line, how far along it the clock is, and three marks; it places them itself, as the width is only known at layout */
        private static final class Track extends Pane {
            private final Region base = new Region();
            private final Region fill = new Region();
            private final Region[] points = {new Region(), new Region(), new Region()};
            private final double position;

            Track(double position, boolean cancelOpen) {
                this.position = position;
                base.getStyleClass().add("dash-track");
                fill.getStyleClass().add("dash-track-fill");
                getChildren().addAll(base, fill);
                for(int i = 0; i < points.length; i++) {
                    points[i].getStyleClass().add("dash-point");
                    if(i == 0 || i == 1 && cancelOpen) {
                        points[i].getStyleClass().add("dash-point-on");
                    }
                    getChildren().add(points[i]);
                }
                setMinHeight(14);
                setPrefHeight(14);
                setMaxHeight(14);
            }

            @Override
            protected void layoutChildren() {
                double width = Math.max(0, getWidth() - 12);
                base.resizeRelocate(6, 5, width, 4);
                fill.resizeRelocate(6, 5, width * position, 4);
                for(int i = 0; i < points.length; i++) {
                    points[i].resizeRelocate(width * (i / 2d), 1, 12, 12);
                }
            }
        }
    }
}
