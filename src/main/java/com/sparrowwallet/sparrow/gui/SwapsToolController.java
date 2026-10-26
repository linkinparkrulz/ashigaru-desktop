package com.sparrowwallet.sparrow.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import com.sparrowwallet.sparrow.swap.SwapProgress;
import com.sparrowwallet.sparrow.swap.SwapService;
import com.sparrowwallet.sparrow.swap.XmrPrice;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tools - Swaps. Shows the market and the makers' offers for the Badbank coin chosen in the wallet
 * view, takes an offer, and follows swaps through to the end. The engine itself lives in
 * {@link SwapService}, so leaving this page never stops a swap.
 */
public class SwapsToolController implements Initializable {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @FXML private Label engineStatus;
    @FXML private Label bestRate;
    @FXML private Label onlineMakers;
    @FXML private Label totalLiquidity;
    @FXML private Label marketLabel;
    @FXML private Label versusMarket;
    @FXML private Label priceSource;
    @FXML private LineChart<Number, Number> priceChart;
    @FXML private NumberAxis timeAxis;
    @FXML private Label spendAmount;
    @FXML private Label spendDetail;
    @FXML private Label expectedReturn;
    @FXML private Label returnDetail;
    @FXML private TextField xmrAddressField;
    @FXML private Button refreshBtn;
    @FXML private TableView<Quote> offersTable;
    @FXML private Label offersPlaceholder;
    @FXML private Label messageLabel;
    @FXML private TableView<JsonObject> swapsTable;
    @FXML private VBox progressCard;
    @FXML private Label progressSwapId;
    @FXML private Label progressElapsed;
    @FXML private Label progressStep;
    @FXML private Label progressDoing;
    @FXML private Label progressDetail;
    @FXML private Label progressNext;
    @FXML private Label progressSlow;
    @FXML private FlowPane stepList;

    private final ObservableList<Quote> quotes = FXCollections.observableArrayList();
    private final ObservableList<JsonObject> swaps = FXCollections.observableArrayList();
    private XmrPrice.Snapshot price;
    //Fee and swap amount for the chosen coin, worked out when the page opens or the coin changes
    private long lockFee;
    private long swapSats;
    private boolean taking;
    private Timeline refresher;
    //Ticks the card's "on this step for" time
    private Timeline ticker;
    //The swap shown in the card: the one picked in the table, otherwise the latest started
    private String shownSwapId;

    private final Runnable swapListener = () -> {
        refreshSwaps();
        updateProgress();
    };
    private final ChangeListener<Boolean> runningListener = (observable, oldValue, running) -> {
        if(running) {
            refreshAll();
        }
        updateState();
    };
    private final ChangeListener<SwapService.Coin> coinListener = (observable, oldValue, coin) -> updateCoin();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        SwapService service = SwapService.get();
        engineStatus.textProperty().bind(service.statusProperty());
        service.addSwapListener(swapListener);
        service.runningProperty().addListener(runningListener);
        service.coinProperty().addListener(coinListener);

        setUpOffersTable();
        setUpSwapsTable();
        timeAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number millis) {
                return HOUR.format(Instant.ofEpochMilli(millis.longValue()));
            }

            @Override
            public Number fromString(String string) {
                return 0;
            }
        });
        xmrAddressField.textProperty().addListener((observable, oldValue, newValue) -> offersTable.refresh());
        //An empty chart only shows meaningless axes: keep it hidden until there are prices to draw
        priceChart.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(priceChart.getData()));
        priceChart.managedProperty().bind(priceChart.visibleProperty());

        //Refresh while the page is shown; stop, and let go of the service, when it is closed
        refresher = new Timeline(new KeyFrame(Duration.seconds(20), event -> refreshQuotes()));
        refresher.setCycleCount(Timeline.INDEFINITE);
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateProgress()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        swapsTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, swap) -> {
            if(swap != null) {
                shownSwapId = swap.get("swapId").getAsString();
                updateProgress();
            }
        });
        engineStatus.sceneProperty().addListener((observable, oldScene, scene) -> {
            if(scene == null) {
                close();
            }
        });

        updateCoin();
        updateState();
        refreshAll();
        refreshPrice();
        refresher.play();
        ticker.play();
        updateProgress();
    }

    private void close() {
        SwapService service = SwapService.get();
        refresher.stop();
        ticker.stop();
        service.removeSwapListener(swapListener);
        service.runningProperty().removeListener(runningListener);
        service.coinProperty().removeListener(coinListener);
        engineStatus.textProperty().unbind();
    }

    private void setUpOffersTable() {
        TableColumn<Quote, String> rate = column("Rate", quote -> {
            String text = String.format(Locale.ROOT, "%.4f XMR", xmrPerBtc(quote));
            if(price != null) {
                text += String.format(Locale.ROOT, "  (%+.1f%%)", XmrPrice.offerVersusMarket(quote.priceSats(), price.btcPerXmr()));
            }
            return text;
        });
        rate.setPrefWidth(170);
        TableColumn<Quote, String> size = column("Min / Max size", quote -> btc(quote.minSats()) + " – " + btc(quote.maxSats()) + " BTC");
        size.setPrefWidth(190);

        TableColumn<Quote, Quote> depth = new TableColumn<>("Liquidity");
        depth.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        depth.setCellFactory(column -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();

            @Override
            protected void updateItem(Quote quote, boolean empty) {
                super.updateItem(quote, empty);
                if(empty || quote == null) {
                    setGraphic(null);
                    return;
                }
                long largest = quotes.stream().mapToLong(Quote::maxSats).max().orElse(1);
                bar.setProgress((double)quote.maxSats() / Math.max(1, largest));
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.getStyleClass().add("swaps-depth");
                setGraphic(bar);
            }
        });
        depth.setPrefWidth(140);
        TableColumn<Quote, String> maker = column("Maker", quote -> quote.peerId().substring(Math.max(0, quote.peerId().length() - 8)));
        maker.setPrefWidth(100);
        TableColumn<Quote, String> status = column("Status", this::offerStatus);
        status.setPrefWidth(150);

        TableColumn<Quote, Quote> action = new TableColumn<>("");
        action.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        action.setCellFactory(column -> new TableCell<>() {
            private final Button take = new Button("Take");

            {
                take.getStyleClass().add("primary-btn");
                take.setMinWidth(Button.USE_PREF_SIZE);
                take.setOnAction(event -> onTake(getItem()));
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(Quote quote, boolean empty) {
                super.updateItem(quote, empty);
                if(empty || quote == null) {
                    setGraphic(null);
                    return;
                }
                //Offers that cannot fit the coin are off; anything else missing is explained on click
                take.setDisable(taking || (swapSats > 0 && !fits(quote, swapSats)));
                setGraphic(take);
            }
        });
        action.setPrefWidth(90);

        offersTable.getColumns().setAll(List.of(rate, size, depth, maker, status, action));
        offersTable.setItems(quotes);
    }

    private void setUpSwapsTable() {
        TableColumn<JsonObject, String> id = column("Swap", swap -> swap.get("swapId").getAsString());
        id.setPrefWidth(280);
        TableColumn<JsonObject, String> state = column("State", swap -> swap.get("state").getAsString());
        state.setPrefWidth(220);
        TableColumn<JsonObject, String> progress = column("", swap -> swap.get("complete").getAsBoolean() ? "Finished"
                : swap.get("running").getAsBoolean() ? "Running" : "Stopped");
        progress.setPrefWidth(90);

        TableColumn<JsonObject, JsonObject> action = new TableColumn<>("");
        action.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        action.setCellFactory(column -> new TableCell<>() {
            private final Button refund = new Button("Refund");

            {
                refund.getStyleClass().add("secondary-btn");
                refund.setMinWidth(Button.USE_PREF_SIZE);
                refund.setOnAction(event -> onRefund(getItem()));
            }

            @Override
            protected void updateItem(JsonObject swap, boolean empty) {
                super.updateItem(swap, empty);
                //A running swap refunds by itself once its timelock allows; a stopped, unfinished one is refunded here
                boolean canRefund = !empty && swap != null && !swap.get("complete").getAsBoolean() && !swap.get("running").getAsBoolean();
                setGraphic(canRefund ? refund : null);
            }
        });
        action.setPrefWidth(100);
        swapsTable.getColumns().setAll(List.of(id, state, progress, action));
        swapsTable.setItems(swaps);
    }

    private static <S> TableColumn<S, String> column(String title, java.util.function.Function<S, String> value) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setSortable(false);
        return column;
    }

    private void updateCoin() {
        SwapService.Coin coin = SwapService.get().coinProperty().get();
        if(coin == null) {
            swapSats = 0;
            spendAmount.setText("—");
            spendDetail.setText("To swap, tick one coin in a wallet's Badbank and press Swap to XMR. The whole coin is swapped, with no change.");
        } else {
            try {
                long[] feeAndAmount = SwapService.get().lockFeeAndAmount();
                lockFee = feeAndAmount[0];
                swapSats = feeAndAmount[1];
                spendAmount.setText(btc(coin.outpoint().getValue()) + " BTC");
                spendDetail.setText("One coin from " + coin.walletName() + "'s Badbank. After the " + lockFee
                        + " sats network fee, " + btc(swapSats) + " BTC is swapped.");
            } catch(Exception e) {
                swapSats = 0;
                spendAmount.setText(btc(coin.outpoint().getValue()) + " BTC");
                spendDetail.setText("This coin cannot be swapped: " + e.getMessage());
            }
        }
        updateReturn();
        offersTable.refresh();
    }

    private void updateReturn() {
        Quote best = bestFittingOffer();
        if(swapSats == 0 || best == null) {
            expectedReturn.setText("—");
            returnDetail.setText(swapSats == 0 ? "" : "No maker takes this amount right now.");
        } else {
            expectedReturn.setText(String.format(Locale.ROOT, "%.6f XMR", swapSats / (double)best.priceSats()));
            returnDetail.setText("At the best offer for this amount. The maker confirms the final amount, shown before you approve.");
        }
    }

    private void updateState() {
        boolean running = SwapService.get().runningProperty().get();
        refreshBtn.setDisable(!running);
        offersPlaceholder.setText(running ? "No makers yet. They are discovered over a minute or two after swaps start."
                : "Swaps are not running. Turn them on in Settings → Swaps.");
        if(!running) {
            quotes.clear();
            updateStats();
        }
    }

    private void refreshAll() {
        refreshQuotes();
        refreshSwaps();
    }

    @FXML
    private void onRefresh() {
        refreshAll();
        refreshPrice();
    }

    private void refreshQuotes() {
        if(!SwapService.get().runningProperty().get()) {
            return;
        }
        SwapService.get().listQuotes(list -> {
            quotes.setAll(list.stream().sorted((a, b) -> Long.compare(a.priceSats(), b.priceSats())).toList());
            updateStats();
            updateReturn();
            offersTable.refresh();
        }, e -> messageLabel.setText("Could not list offers: " + e.getMessage()));
    }

    private void refreshSwaps() {
        if(!SwapService.get().runningProperty().get()) {
            return;
        }
        SwapService.get().listSwaps(list -> {
            swaps.clear();
            for(JsonElement element : list) {
                swaps.add(0, element.getAsJsonObject());
            }
        }, e -> {});
    }

    private void refreshPrice() {
        if(AppServices.getTorProxy() == null) {
            priceSource.setText("Price unavailable until Tor is running");
            return;
        }
        Task<XmrPrice.Snapshot> task = new Task<>() {
            @Override
            protected XmrPrice.Snapshot call() throws Exception {
                return XmrPrice.fetch();
            }
        };
        task.setOnSucceeded(event -> {
            price = task.getValue();
            priceSource.setText("from " + price.source() + ", over Tor");
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            for(double[] point : price.history()) {
                series.getData().add(new XYChart.Data<>(point[0], Math.round(point[1] * 100_000_000d)));
            }
            priceChart.getData().setAll(List.of(series));
            updateStats();
            offersTable.refresh();
        });
        task.setOnFailed(event -> priceSource.setText("Price unavailable: " + task.getException().getMessage()));
        Thread thread = new Thread(task, "XmrPrice");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateStats() {
        if(quotes.isEmpty()) {
            bestRate.setText("—");
            onlineMakers.setText(SwapService.get().runningProperty().get() ? "0" : "—");
            totalLiquidity.setText("—");
            versusMarket.setText("—");
            versusMarket.getStyleClass().removeAll("swaps-good", "swaps-bad");
            return;
        }
        Quote best = quotes.get(0);
        bestRate.setText(String.format(Locale.ROOT, "1 BTC → %.3f XMR", xmrPerBtc(best)));
        onlineMakers.setText(String.valueOf(quotes.size()));
        totalLiquidity.setText(btc(quotes.stream().mapToLong(Quote::maxSats).sum()) + " BTC");
        versusMarket.getStyleClass().removeAll("swaps-good", "swaps-bad");
        if(price == null) {
            versusMarket.setText("—");
        } else {
            double percent = XmrPrice.offerVersusMarket(best.priceSats(), price.btcPerXmr());
            versusMarket.setText(String.format(Locale.ROOT, "%+.1f%%", percent));
            versusMarket.getStyleClass().add(percent >= 0 ? "swaps-good" : "swaps-bad");
        }
    }

    private Quote bestFittingOffer() {
        return quotes.stream().filter(quote -> fits(quote, swapSats)).findFirst().orElse(null);
    }

    private static boolean fits(Quote quote, long sats) {
        return sats >= quote.minSats() && sats <= quote.maxSats();
    }

    private String offerStatus(Quote quote) {
        if(swapSats == 0) {
            return "Online";
        }
        if(!fits(quote, swapSats)) {
            return swapSats < quote.minSats() ? "Coin too small" : "Coin too large";
        }
        return "Fits this coin";
    }

    /** Why this offer cannot be taken now, or null */
    private String offerProblem(Quote quote) {
        if(SwapService.get().coinProperty().get() == null || swapSats == 0) {
            return "Choose a Badbank coin first: tick one in a wallet's Badbank and press Swap to XMR.";
        }
        if(!fits(quote, swapSats)) {
            return "This maker takes " + btc(quote.minSats()) + " to " + btc(quote.maxSats()) + " BTC; this coin swaps " + btc(swapSats) + " BTC.";
        }
        long maxSats = Config.get().getSwapMaxSats();
        if(swapSats > maxSats) {
            return "Swaps are limited to " + btc(maxSats) + " BTC. Change the maximum in Settings → Swaps.";
        }
        if(!isMainnetMoneroAddress(xmrAddressField.getText().trim())) {
            return "Enter the XMR address that receives the swap: a mainnet address, 95 characters, starting with 4 or 8.";
        }
        if(SwapService.get().isSwapActive()) {
            return "A swap is already running; one at a time.";
        }
        return null;
    }

    private void onTake(Quote quote) {
        if(quote == null) {
            return;
        }
        String problem = offerProblem(quote);
        if(problem != null) {
            messageLabel.setText(problem);
            return;
        }
        taking = true;
        offersTable.refresh();
        messageLabel.setText("Checking that every Monero node answers through Tor…");
        SwapService.get().checkNodes(check -> {
            List<String> unreachable = SwapService.unreachable(check);
            if(!unreachable.isEmpty()) {
                taking = false;
                offersTable.refresh();
                messageLabel.setText("Not started: " + (unreachable.size() == 1 ? "a Monero node" : unreachable.size() + " Monero nodes")
                        + " did not answer. Untick it in Settings → Swaps (keeping at least two public nodes) or add your own node, then try again.\n"
                        + String.join("\n", unreachable));
                return;
            }
            messageLabel.setText("Setting up the swap with the maker…");
            SwapService.get().startSwap(quote, xmrAddressField.getText().trim(), swapId -> {
                taking = false;
                offersTable.refresh();
                shownSwapId = swapId;
                messageLabel.setText("");
                updateProgress();
                scrollToTop();
                refreshSwaps();
            }, e -> {
                taking = false;
                offersTable.refresh();
                messageLabel.setText("Could not start the swap: " + e.getMessage());
            });
        }, e -> {
            taking = false;
            offersTable.refresh();
            messageLabel.setText("Could not check the Monero nodes: " + e.getMessage());
        });
    }

    /** Shows the card for the chosen swap, or the latest one, with where it is and what comes next */
    private void updateProgress() {
        SwapService service = SwapService.get();
        String swapId = shownSwapId != null ? shownSwapId : service.getCurrentSwapId();
        SwapProgress.Status status = service.getProgress(swapId);
        boolean show = swapId != null && status != null;
        progressCard.setVisible(show);
        progressCard.setManaged(show);
        if(!show) {
            return;
        }

        SwapProgress.Step step = status.step();
        progressSwapId.setText("Swap " + swapId.substring(0, Math.min(8, swapId.length())));
        Instant now = Instant.now();
        long seconds = java.time.Duration.between(status.since(), now).getSeconds();
        progressElapsed.setText(status.finished() ? "" : "on this step for " + elapsed(seconds));
        progressStep.setText(step.getTitle());
        boolean stopped = step == SwapProgress.Step.STOPPED;
        setText(progressDoing, stopped ? null : step.getDoing());
        setText(progressDetail, status.detail());
        setText(progressNext, step.getNext().isEmpty() ? null : step.getNext());
        boolean slow = status.isSlow(now);
        progressSlow.setVisible(slow);
        progressSlow.setManaged(slow);
        if(slow) {
            progressSlow.setText(step == SwapProgress.Step.NODES
                    ? "This is taking longer than usual: a Monero node may be unreachable through Tor. Tools → Logs → Swap engine says which; if one keeps failing, untick it in Settings → Swaps. Nothing is locked yet."
                    : "This is taking longer than usual (normally under " + step.getUsual().toMinutes() + " minutes). Tools → Logs → Swap engine shows what it is waiting for.");
        }

        List<SwapProgress.Step> path = step.ordinal() >= SwapProgress.Step.REFUND_WAIT.ordinal() && step != SwapProgress.Step.WORKING && !stopped
                ? List.of(SwapProgress.Step.REFUND_WAIT, SwapProgress.Step.REFUNDING, SwapProgress.Step.REFUNDED)
                : SwapProgress.MAIN_STEPS;
        int current = path.indexOf(step);
        stepList.getChildren().clear();
        for(int i = 0; i < path.size(); i++) {
            Label chip = new Label((i < current || (i == current && status.finished()) ? "✓ " : "") + (i + 1) + ". " + path.get(i).getTitle());
            chip.getStyleClass().add("swaps-step");
            if(i < current || (i == current && status.finished())) {
                chip.getStyleClass().add("swaps-step-done");
            } else if(i == current) {
                chip.getStyleClass().add("swaps-step-current");
            }
            stepList.getChildren().add(chip);
        }
    }

    private static void setText(Label label, String text) {
        boolean show = text != null && !text.isBlank();
        label.setText(show ? text : "");
        label.setVisible(show);
        label.setManaged(show);
    }

    private static String elapsed(long seconds) {
        if(seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    /** The card sits at the top: bring it into view after Take */
    private void scrollToTop() {
        javafx.scene.Node node = progressCard;
        while(node != null && !(node instanceof ScrollPane)) {
            node = node.getParent();
        }
        if(node instanceof ScrollPane scroll) {
            scroll.setVvalue(0);
        }
    }

    private void onRefund(JsonObject swap) {
        if(swap == null) {
            return;
        }
        String swapId = swap.get("swapId").getAsString();
        messageLabel.setText("Cancelling and refunding " + swapId + "…");
        SwapService.get().refund(swapId, state -> messageLabel.setText("Refund published to Badbank."),
                e -> messageLabel.setText("Could not refund yet: " + e.getMessage()));
    }

    private static double xmrPerBtc(Quote quote) {
        return 100_000_000d / quote.priceSats();
    }

    static boolean isMainnetMoneroAddress(String address) {
        //Standard (4) and subaddress (8) mainnet addresses; the engine checks the network again
        return address.length() == 95 && (address.charAt(0) == '4' || address.charAt(0) == '8')
                && address.chars().allMatch(c -> "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".indexOf(c) >= 0);
    }

    /** Asks before quitting while a swap runs. Returns true to quit. */
    static boolean confirmQuitDuringSwap(Window owner) {
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "A swap is still running. If Ashigaru is closed, the swap pauses: the maker can still finish its side, "
                        + "and if the swap was cancelled the refund must be claimed in time. Reopen Ashigaru soon - with "
                        + "swaps on, it picks unfinished swaps back up.\n\nQuit anyway?",
                ButtonType.CANCEL, ButtonType.YES);
        alert.setTitle("Swap running");
        alert.setHeaderText("A swap is running");
        alert.initOwner(owner);
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
        return alert.showAndWait().filter(button -> button == ButtonType.YES).isPresent();
    }

    private static String btc(long sats) {
        return String.format(Locale.ROOT, "%.8f", sats / 100_000_000d);
    }
}
