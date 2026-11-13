package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.swap.SwapAttention;
import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import com.sparrowwallet.sparrow.swap.SwapOffers;
import com.sparrowwallet.sparrow.swap.SwapOffers.Row;
import com.sparrowwallet.sparrow.swap.SwapOffers.Sort;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * The offers screen: an attention notice for unfinished swaps, what the user is spending and could receive,
 * the receiving address with a permanent label, and the offers as a comparison table. Offers are led by
 * the estimated XMR and the cancellation deduction, with the maker and rate second and the gaps to the best
 * offer and the market third. The pane updates in place, so the address field keeps focus while it is
 * typed in, and it draws only what it is given; {@link SwapOffers} decides what the numbers are.
 */
public class SwapOffersPane extends VBox {
    /** What the screen asks of whoever owns it */
    public interface Listener {
        void review(Quote quote);

        void chooseCoin();

        void viewRecovery(String swapId);

        void refresh();
    }

    /**
     * Everything the pane shows. {@code problem} says why an offer cannot be reviewed now, or null; it is asked
     * when the pane draws, so it always sees the address as typed. {@code working} is a message to show with a
     * spinner while a swap is being set up, and {@code requesting} the maker it is being set up with.
     */
    public record State(List<SwapAttention> attention, boolean coinChosen, long coinSats, String walletName, long swapSats, long feeSats,
                        String coinProblem, List<Quote> shown, String footer, String marketText, Double market, boolean engineOn,
                        String emptyMessage, Function<Quote, String> problem, String working, String requesting, Instant updated) {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Listener listener;
    private final VBox noticeBox = new VBox();
    private final VBox topBox = new VBox();
    private final TextField addressField = new TextField();
    private final Label addressStatus = new Label();
    private final VBox tableBox = new VBox();
    private final Label message = new Label();
    private Sort sort = Sort.MOST_XMR;
    private final Set<String> expanded = new HashSet<>();
    private State state;
    private boolean narrow;

    public SwapOffersPane(Listener listener) {
        super(16);
        this.listener = listener;
        getStyleClass().add("offers-pane");

        message.getStyleClass().add("dash-message");
        message.setWrapText(true);
        message.setMinHeight(Region.USE_PREF_SIZE);
        message.setMaxWidth(Double.MAX_VALUE);
        message.setVisible(false);
        message.managedProperty().bind(message.visibleProperty());

        topBox.getStyleClass().addAll("ag-card", "review-card");
        topBox.setSpacing(0);
        tableBox.getStyleClass().addAll("ag-card", "offers-table");
        tableBox.setPadding(Insets.EMPTY);
        tableBox.setMinWidth(0);
        setMinWidth(0);

        addressField.setPromptText("4…");
        addressField.getStyleClass().add("offer-address");
        addressField.textProperty().addListener((observable, was, now) -> {
            updateAddressStatus();
            if(state != null) {
                renderTable();
            }
        });
        addressStatus.setWrapText(true);
        addressStatus.setMinHeight(Region.USE_PREF_SIZE);

        getChildren().addAll(noticeBox, topBox, message, tableBox);
        //Below about 700px the table folds: the maker and rate move under the amount
        widthProperty().addListener((observable, was, now) -> {
            boolean small = now.doubleValue() > 0 && now.doubleValue() < 700;
            if(small != narrow) {
                narrow = small;
                if(state != null) {
                    renderTable();
                }
            }
        });
    }

    /** The address as typed, trimmed */
    public String getAddress() {
        return addressField.getText() == null ? "" : addressField.getText().trim();
    }

    /** A failure or a note from starting a swap, shown above the table; null clears it */
    public void setMessage(String text) {
        boolean show = text != null && !text.isBlank();
        message.setText(show ? text : "");
        message.setVisible(show);
    }

    /** Draws what it is given, keeping the address, the sort and which offers are expanded */
    public void show(State state) {
        this.state = state;
        renderNotice();
        renderTop();
        renderTable();
    }

    private void renderNotice() {
        noticeBox.getChildren().clear();
        if(state.attention().isEmpty()) {
            return;
        }
        Label icon = label("!", "review-callout-icon");
        icon.setAlignment(Pos.CENTER);
        VBox text = new VBox(2, wrapped(SwapAttention.headline(state.attention()), "review-callout-title"), wrapped(SwapAttention.text(state.attention()), "review-fine"));
        HBox.setHgrow(text, Priority.ALWAYS);
        Button view = button("View recovery", "secondary-btn");
        view.setOnAction(event -> listener.viewRecovery(state.attention().get(0).swapId()));
        HBox notice = new HBox(14, icon, text, view);
        notice.setAlignment(Pos.CENTER_LEFT);
        notice.getStyleClass().add("review-callout");
        noticeBox.getChildren().add(notice);
    }

    private void renderTop() {
        topBox.getChildren().clear();
        if(!state.coinChosen()) {
            Label title = label("Choose a coin to swap", "review-heading");
            VBox text = new VBox(8, title, wrapped("Tick one coin in a wallet’s Badbank and press Swap to XMR. The whole coin is swapped, with no change.", "review-body"));
            HBox.setHgrow(text, Priority.ALWAYS);
            Button choose = button("Choose coin from Badbank", "primary-btn", "review-primary");
            choose.setOnAction(event -> listener.chooseCoin());
            HBox row = new HBox(24, text, choose);
            row.setAlignment(Pos.CENTER_LEFT);
            topBox.getChildren().add(row);
        } else {
            VBox send = new VBox(6, eyebrow("You send"), amount(BtcText.btc(state.coinSats()), "BTC"),
                    wrapped(state.coinProblem() != null ? "This coin cannot be swapped: " + state.coinProblem()
                            : state.walletName() + "’s Badbank coin, whole. " + BtcText.btc(state.swapSats()) + " BTC is swapped after the " + String.format(Locale.ROOT, "%,d", state.feeSats()) + " sat fee.", "review-body"));
            Button change = button("Choose a different coin", "offer-link");
            change.setOnAction(event -> listener.chooseCoin());
            send.getChildren().add(change);
            VBox receive = estimates();
            GridPane split = new GridPane();
            split.setHgap(28);
            ColumnConstraints left = new ColumnConstraints();
            left.setPercentWidth(45);
            ColumnConstraints right = new ColumnConstraints();
            right.setPercentWidth(55);
            split.getColumnConstraints().addAll(left, right);
            send.setMinWidth(0);
            receive.setMinWidth(0);
            split.add(send, 0, 0);
            split.add(receive, 1, 0);
            GridPane.setValignment(send, javafx.geometry.VPos.TOP);
            GridPane.setValignment(receive, javafx.geometry.VPos.TOP);
            topBox.getChildren().add(split);
        }

        Region divider = new Region();
        divider.getStyleClass().add("offer-divider");
        divider.setMinHeight(1);
        divider.setPrefHeight(1);
        VBox.setMargin(divider, new Insets(14, 0, 12, 0));
        topBox.getChildren().add(divider);

        //The label is permanent: a placeholder must not carry the whole explanation
        Label field = label("Receiving XMR address", "offer-field-label");
        Label help = wrapped("Where the XMR is sent. It is shown again for checking before you approve.", "review-fine");
        help.setAlignment(Pos.CENTER_RIGHT);
        help.setTextAlignment(TextAlignment.RIGHT);
        HBox labelRow = new HBox(16, field, help);
        labelRow.setAlignment(Pos.BASELINE_LEFT);
        HBox.setHgrow(help, Priority.ALWAYS);
        topBox.getChildren().addAll(labelRow, addressField, addressStatus);
        VBox.setMargin(addressField, new Insets(6, 0, 4, 0));
        updateAddressStatus();
    }

    /** What the user could receive, led by the most XMR with its deduction and, if that has one, the most with none */
    private VBox estimates() {
        VBox box = new VBox(6, eyebrow("Estimated XMR, up to"));
        List<Row> rows = SwapOffers.rows(state.shown(), state.swapSats(), state.market(), Sort.MOST_XMR);
        if(state.swapSats() <= 0 || rows.isEmpty()) {
            box.getChildren().add(wrapped(state.swapSats() <= 0 ? "—" : "No maker takes this amount right now.", "review-body"));
            return box;
        }
        SwapOffers.Summary summary = SwapOffers.summary(rows);
        Row most = summary.mostXmr();
        box.getChildren().add(amount(String.format(Locale.ROOT, "%.6f", most.xmr()), "XMR"));
        box.getChildren().add(wrapped(most.noDeduction() ? "Most XMR, with no cancellation deduction."
                : "Most XMR. The maker may keep " + Math.round(most.deductionRatio() * 100) + "% (" + BtcText.btc(most.deductionSats()) + " BTC) if the swap is cancelled.", "review-fine"));
        if(summary.mostWithNoDeduction() != null) {
            Row clean = summary.mostWithNoDeduction();
            Label figure = label(String.format(Locale.ROOT, "%.6f", clean.xmr()) + "  XMR", "offer-figure-small");
            VBox.setMargin(figure, new Insets(10, 0, 0, 0));
            box.getChildren().addAll(figure, wrapped("Most XMR with no cancellation deduction.", "review-fine"));
        }
        return box;
    }

    private void updateAddressStatus() {
        String address = getAddress();
        addressStatus.getStyleClass().removeAll("offer-status-ok", "offer-status-warn", "review-fine");
        addressStatus.getStyleClass().add("review-fine");
        addressField.getStyleClass().remove("offer-address-bad");
        if(address.isEmpty()) {
            addressStatus.setText("Required to review an offer.");
        } else if(SwapsToolController.isMainnetMoneroAddress(address)) {
            addressStatus.setText("Valid mainnet address.");
            addressStatus.getStyleClass().add("offer-status-ok");
        } else {
            addressStatus.setText("Mainnet addresses are 95 characters and start with 4 or 8. This one has " + address.length() + ".");
            addressStatus.getStyleClass().add("offer-status-warn");
            addressField.getStyleClass().add("offer-address-bad");
        }
    }

    private void renderTable() {
        tableBox.getChildren().clear();
        boolean coin = state.coinChosen() && state.swapSats() > 0;

        VBox head = new VBox(12);
        head.setPadding(new Insets(16, 20, 16, 20));
        Label reference = label("Reference only", "offer-ref-chip");
        Label marketLine = wrapped(state.marketText(), "review-body");
        HBox ref = new HBox(12, reference, marketLine);
        ref.setAlignment(Pos.CENTER_LEFT);
        ref.getStyleClass().add("offer-ref");
        HBox.setHgrow(marketLine, Priority.ALWAYS);

        ToggleGroup group = new ToggleGroup();
        HBox seg = new HBox(2);
        seg.getStyleClass().add("offer-seg");
        for(Sort option : Sort.values()) {
            ToggleButton button = new ToggleButton(option.getLabel());
            button.setToggleGroup(group);
            button.getStyleClass().add("offer-seg-btn");
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setSelected(option == sort);
            button.setOnAction(event -> {
                sort = option;
                renderTable();
            });
            seg.getChildren().add(button);
        }
        //A pressed segment stays pressed: choosing the sort it already has does nothing
        group.selectedToggleProperty().addListener((observable, was, now) -> {
            if(now == null && was != null) {
                group.selectToggle(was);
            }
        });
        Label count = label(state.shown().size() + " offer" + (state.shown().size() == 1 ? "" : "s")
                + (state.updated() == null ? "" : " · updated " + TIME.format(state.updated())), "review-fine");
        Button refresh = button("Refresh", "secondary-btn");
        refresh.setDisable(!state.engineOn());
        refresh.setOnAction(event -> listener.refresh());
        //Two groups that wrap as units, so a narrow window moves the count and Refresh below the sort control
        HBox sorting = new HBox(14, label("SORTED BY", "review-eyebrow"), seg);
        sorting.setAlignment(Pos.CENTER_LEFT);
        HBox status = new HBox(14, count, refresh);
        status.setAlignment(Pos.CENTER_LEFT);
        FlowPane bar = new FlowPane(28, 8, sorting, status);
        bar.setAlignment(Pos.CENTER_LEFT);

        head.getChildren().addAll(ref, bar);
        String hint = hint(coin);
        if(state.working() != null) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(16, 16);
            spinner.setMinSize(16, 16);
            spinner.getStyleClass().add("dash-spinner");
            HBox line = new HBox(10, spinner, wrapped(state.working(), "review-body"));
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("review-line");
            head.getChildren().add(line);
        } else if(hint != null) {
            Region dot = new Region();
            dot.getStyleClass().addAll("dash-dot", "offer-dot-info");
            dot.setMinSize(8, 8);
            dot.setPrefSize(8, 8);
            dot.setMaxSize(8, 8);
            HBox line = new HBox(10, dot, wrapped(hint, "review-body"));
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("review-line");
            head.getChildren().add(line);
        }
        tableBox.getChildren().add(head);

        List<Row> rows = SwapOffers.rows(state.shown(), coin ? state.swapSats() : 0, state.market(), sort);
        if(rows.isEmpty()) {
            Label empty = wrapped(state.emptyMessage() == null ? "No usable offers right now." : state.emptyMessage(), "review-body");
            empty.setPadding(new Insets(16, 20, 16, 20));
            tableBox.getChildren().add(empty);
        } else {
            tableBox.getChildren().add(headerRow());
            for(Row row : rows) {
                tableBox.getChildren().add(offerRow(row, coin));
                if(expanded.contains(row.quote().peerId())) {
                    tableBox.getChildren().add(details(row.quote()));
                }
            }
        }
        Label foot = wrapped((state.footer().isEmpty() ? "" : state.footer() + " ")
                + "Review offer contacts the maker over Tor and takes a few minutes, and nothing is locked until you approve. "
                + "XMR amounts are estimated from each maker’s rate; the maker confirms the final amount before you approve.", "review-fine");
        foot.setPadding(new Insets(14, 20, 14, 20));
        tableBox.getChildren().add(foot);
    }

    private String hint(boolean coin) {
        if(state.requesting() != null) {
            return null;
        }
        if(!state.coinChosen()) {
            return "Choose a coin to see what each offer would give you.";
        }
        if(!SwapsToolController.isMainnetMoneroAddress(getAddress())) {
            return "Enter your receiving XMR address above to review an offer.";
        }
        return null;
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setPadding(new Insets(0, 20, 0, 20));
        //A row adapts to the width it is given and never demands more: the table folds when that is under 700px, and
        //a row that insisted on its wide minimum would stop the page ever being given less
        grid.setMinWidth(0);
        ColumnConstraints amount = new ColumnConstraints();
        ColumnConstraints deduction = new ColumnConstraints();
        ColumnConstraints maker = new ColumnConstraints();
        //The buttons take whatever is left, never less than they need, and sit at the right
        ColumnConstraints action = new ColumnConstraints();
        action.setMinWidth(168);
        action.setPrefWidth(168);
        action.setMaxWidth(Double.MAX_VALUE);
        action.setHgrow(Priority.ALWAYS);
        if(narrow) {
            amount.setPercentWidth(38);
            deduction.setPercentWidth(30);
            maker.setPercentWidth(0);
            maker.setMinWidth(0);
            maker.setMaxWidth(0);
        } else {
            amount.setPercentWidth(28);
            deduction.setPercentWidth(24);
            maker.setPercentWidth(20);
        }
        grid.getColumnConstraints().addAll(amount, deduction, maker, action);
        return grid;
    }

    private Region headerRow() {
        GridPane header = grid();
        header.getStyleClass().add("offer-head");
        header.setPadding(new Insets(12, 20, 12, 20));
        header.add(label("ESTIMATED XMR", "review-eyebrow"), 0, 0);
        header.add(label("IF CANCELLED", "review-eyebrow"), 1, 0);
        if(!narrow) {
            header.add(label("MAKER AND RATE", "review-eyebrow"), 2, 0);
        }
        return header;
    }

    private Region offerRow(Row row, boolean coin) {
        Quote quote = row.quote();
        GridPane grid = grid();
        grid.getStyleClass().add("offer-row");
        if(row.mostXmr()) {
            grid.getStyleClass().add("offer-row-best");
        }
        grid.setPadding(new Insets(16, 20, 16, 20));

        //Primary: the estimated XMR, with the gaps to the best offer and to the market as one quiet line
        VBox amount = new VBox(3);
        amount.setMinWidth(0);
        if(coin && row.xmr() != null) {
            TextFlow flow = flowAmount(String.format(Locale.ROOT, "%.6f", row.xmr()), "XMR");
            amount.getChildren().add(flow);
            List<String> gaps = new java.util.ArrayList<>();
            if(row.behindMost() != null && row.behindMost() > 0) {
                gaps.add("−" + String.format(Locale.ROOT, "%.4f", row.behindMost()) + " vs the most");
            }
            if(row.versusMarket() != null) {
                gaps.add(SwapOffers.percent(row.versusMarket()) + " vs market");
            }
            if(!gaps.isEmpty()) {
                amount.getChildren().add(wrapped(String.join(" · ", gaps), "review-fine"));
            }
        } else {
            amount.getChildren().add(label("—", "offer-figure-dim"));
            amount.getChildren().add(wrapped(row.versusMarket() == null ? "Choose a coin" : "Choose a coin · " + SwapOffers.percent(row.versusMarket()) + " vs market", "review-fine"));
        }
        if(row.mostXmr()) {
            amount.getChildren().add(chip("Most XMR"));
        }
        if(narrow) {
            amount.getChildren().add(wrapped(quote.provider() + " · " + String.format(Locale.ROOT, "%.2f", row.xmrPerBtc()) + " XMR per BTC", "review-fine"));
        }

        //Primary: what the maker may keep if the swap is cancelled
        VBox deduction = new VBox(3);
        deduction.setMinWidth(0);
        if(row.noDeduction()) {
            HBox line = new HBox(8, label("✓", "offer-tick"), label("No deduction", "offer-deduction"));
            line.setAlignment(Pos.CENTER_LEFT);
            deduction.getChildren().addAll(line, wrapped("Minus network fees", "review-fine"));
        } else {
            deduction.getChildren().add(wrapped("Maker may keep " + Math.round(row.deductionRatio() * 100) + "%", "offer-deduction"));
            deduction.getChildren().add(wrapped(coin ? BtcText.btc(row.deductionSats()) + " BTC" : "of the locked BTC", "review-fine"));
        }
        if(row.bestRefund()) {
            deduction.getChildren().add(chip("Best refund"));
        }

        //Secondary: who and at what rate
        VBox maker = new VBox(3, wrapped(quote.provider(), "offer-maker"), wrapped(String.format(Locale.ROOT, "%.2f", row.xmrPerBtc()) + " XMR per BTC", "review-fine"));
        maker.setMinWidth(0);
        Tooltip.install(maker, new Tooltip(quote.peerId() + "\n" + quote.multiaddr()));

        HBox action = new HBox(8);
        action.setAlignment(Pos.CENTER_RIGHT);
        if(quote.peerId().equals(state.requesting())) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setPrefSize(16, 16);
            spinner.setMinSize(16, 16);
            spinner.getStyleClass().add("dash-spinner");
            action.getChildren().addAll(spinner, label("Contacting", "review-body"));
        } else {
            String problem = state.requesting() != null || state.working() != null ? "A swap is already being set up" : state.problem().apply(quote);
            Button review = button("Review offer", "offer-review");
            review.setDisable(problem != null);
            if(problem != null) {
                Tooltip.install(action, new Tooltip(problem));
            }
            review.setOnAction(event -> listener.review(quote));
            boolean open = expanded.contains(quote.peerId());
            Button caret = new Button(open ? "▴" : "▾");
            caret.getStyleClass().add("offer-caret");
            caret.setMinWidth(Region.USE_PREF_SIZE);
            Tooltip.install(caret, new Tooltip(open ? "Hide details" : "Show details"));
            caret.setOnAction(event -> {
                if(!expanded.remove(quote.peerId())) {
                    expanded.add(quote.peerId());
                }
                renderTable();
            });
            action.getChildren().addAll(review, caret);
        }

        grid.add(amount, 0, 0);
        grid.add(deduction, 1, 0);
        if(!narrow) {
            grid.add(maker, 2, 0);
        }
        grid.add(action, 3, 0);
        GridPane.setValignment(action, javafx.geometry.VPos.CENTER);
        return grid;
    }

    /** The details kept out of the way: protocol version matters only to someone looking for a problem */
    private Region details(Quote quote) {
        GridPane details = new GridPane();
        details.setHgap(16);
        details.setPadding(new Insets(12, 20, 14, 20));
        details.getStyleClass().add("offer-details");
        for(int i = 0; i < 4; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(25);
            details.getColumnConstraints().add(constraints);
        }
        details.add(detail("Maker id", quote.peerId().length() > 20 ? quote.peerId().substring(0, 12) + "…" + quote.peerId().substring(quote.peerId().length() - 4) : quote.peerId()), 0, 0);
        details.add(detail("Takes", BtcText.btc(quote.minSats()) + " to " + BtcText.btc(quote.maxSats()) + " BTC"), 1, 0);
        details.add(detail("Protocol version", quote.version() == null || quote.version().isBlank() ? "unknown" : "v" + quote.version()), 2, 0);
        details.add(detail("Reached over", "Tor"), 3, 0);
        return details;
    }

    private static VBox detail(String key, String value) {
        VBox box = new VBox(2, label(key, "review-fine"), wrapped(value, "offer-detail-value"));
        box.setMinWidth(0);
        return box;
    }

    private static javafx.scene.text.TextFlow flowAmount(String value, String unit) {
        javafx.scene.text.Text lead = new javafx.scene.text.Text(value);
        lead.getStyleClass().add("offer-amount");
        javafx.scene.text.Text unitText = new javafx.scene.text.Text("  " + unit);
        unitText.getStyleClass().add("review-amount-unit");
        return new javafx.scene.text.TextFlow(lead, unitText);
    }

    private static javafx.scene.text.TextFlow amount(String value, String unit) {
        javafx.scene.text.Text lead = new javafx.scene.text.Text(value);
        lead.getStyleClass().add("review-amount-lead");
        javafx.scene.text.Text unitText = new javafx.scene.text.Text("  " + unit);
        unitText.getStyleClass().add("review-amount-unit");
        return new javafx.scene.text.TextFlow(lead, unitText);
    }

    private static Label chip(String text) {
        Label chip = label(text, "offer-chip");
        VBox.setMargin(chip, new Insets(4, 0, 0, 0));
        return chip;
    }

    private static Label eyebrow(String text) {
        return label(text.toUpperCase(Locale.ROOT), "review-eyebrow");
    }

    private static Button button(String text, String... styles) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styles);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
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

    /** BTC to eight decimals, shared with the rest of the swap screens */
    static final class BtcText {
        static String btc(long sats) {
            return String.format(Locale.ROOT, "%.8f", sats / 100_000_000d);
        }
    }
}
