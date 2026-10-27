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
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tools - Swaps. Shows the offers that can take the Badbank coin chosen in the wallet view, takes
 * one, and follows the swap through to the end. While a swap runs the page shows only that swap.
 * The engine itself lives in {@link SwapService}, so leaving this page never stops a swap.
 */
public class SwapsToolController implements Initializable {
    @FXML private Label engineStatus;
    @FXML private VBox offersView;
    @FXML private Label offersTitle;
    @FXML private Label marketLine;
    @FXML private FlowPane offerCards;
    @FXML private Label offersEmpty;
    @FXML private Label offersFooter;
    @FXML private Label spendAmount;
    @FXML private Label spendDetail;
    @FXML private Label expectedReturn;
    @FXML private Label returnDetail;
    @FXML private TextField xmrAddressField;
    @FXML private Button refreshBtn;
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
    @FXML private ProgressBar stepProgress;
    @FXML private Label stepCount;
    @FXML private HBox progressDone;

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
    //A finished swap the user has gone back to offers from
    private String dismissedSwapId;

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

        setUpSwapsTable();
        offersView.managedProperty().bind(offersView.visibleProperty());

        //Refresh while the page is shown; stop, and let go of the service, when it is closed
        refresher = new Timeline(new KeyFrame(Duration.seconds(20), event -> refreshQuotes()));
        refresher.setCycleCount(Timeline.INDEFINITE);
        ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateProgress()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        swapsTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, swap) -> {
            if(swap != null) {
                shownSwapId = swap.get("swapId").getAsString();
                dismissedSwapId = null;
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

    /** Rebuilds the offer cards: only offers that can take this coin, best rate first */
    private void renderOffers() {
        Double market = price == null ? null : price.btcPerXmr();
        Offers offers = Offers.of(quotes, swapSats, market);
        offerCards.getChildren().clear();
        for(Quote quote : offers.shown()) {
            offerCards.getChildren().add(offerCard(quote, quote == offers.bestRate(), quote == offers.bestRefund()));
        }

        boolean running = SwapService.get().runningProperty().get();
        offersTitle.setText(swapSats > 0 ? "Offers for this coin" : "Offers");
        String empty = !offers.shown().isEmpty() ? null
                : !running ? "Swaps are not running. Turn them on in Settings → Swaps."
                : quotes.isEmpty() ? "No makers yet. They are found over a minute or two after swaps start."
                : swapSats > 0 ? "No maker can take this coin right now." : "No usable offers right now.";
        offersEmpty.setText(empty == null ? "" : empty);
        offersEmpty.setVisible(empty != null);
        offersEmpty.setManaged(empty != null);

        String footer = offers.footer(market != null);
        offersFooter.setText(footer);
        offersFooter.setVisible(!footer.isEmpty());
        offersFooter.setManaged(!footer.isEmpty());
        updateReturn(offers.bestRate());
    }

    private Region offerCard(Quote quote, boolean bestRate, boolean bestRefund) {
        VBox card = new VBox();
        card.getStyleClass().addAll("ag-card", "swaps-offer");
        if(bestRate) {
            card.getStyleClass().add("swaps-offer-best");
        }

        HBox badges = new HBox(6);
        if(bestRate) {
            badges.getChildren().add(badge("BEST RATE"));
        }
        if(bestRefund) {
            badges.getChildren().add(badge("BEST REFUND"));
        }
        badges.setMinHeight(18);

        Label rate = new Label(String.format(Locale.ROOT, "1 BTC → %.2f XMR", xmrPerBtc(quote)));
        rate.getStyleClass().add("swaps-offer-rate");
        List<String> about = new ArrayList<>();
        if(price != null) {
            //Negative: less XMR than at the market price, as every honest maker charges a spread
            about.add(String.format(Locale.ROOT, "%+.1f%% vs market", XmrPrice.offerVersusMarket(quote.priceSats(), price.btcPerXmr())));
        }
        if(quote.version() != null && !quote.version().isBlank()) {
            about.add("v" + quote.version());
        }
        Label versus = new Label(String.join("  ·  ", about));
        versus.getStyleClass().add("card-faint");

        Label provider = new Label(quote.provider());
        provider.getStyleClass().add("field-value");
        provider.setTooltip(new Tooltip(quote.peerId() + "\n" + quote.multiaddr()));

        double ratio = quote.refundRatio();
        Label refund = new Label(ratio == 0 ? "Full refund if cancelled"
                : String.format(Locale.ROOT, "If cancelled, the maker may keep %.0f%% as a deposit", ratio * 100));
        refund.getStyleClass().add("card-faint");
        refund.setWrapText(true);

        Button take = new Button("Take");
        take.getStyleClass().add("primary-btn");
        take.setMinWidth(Button.USE_PREF_SIZE);
        take.setDisable(taking);
        take.setOnAction(event -> onTake(quote));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(spacer, take);
        actions.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(badges, rate, versus, provider, refund, actions);
        return card;
    }

    private static Label badge(String text) {
        Label badge = new Label(text);
        badge.getStyleClass().add("swaps-badge");
        return badge;
    }

    /**
     * The offers worth showing for a coin, and how many were left out and why. Outdated makers, offers
     * that cannot take the coin and offers below the market are never shown: none of them can
     * complete a swap.
     */
    record Offers(List<Quote> shown, Quote bestRate, Quote bestRefund, int outdated, int wrongSize, int belowMarket) {
        static Offers of(List<Quote> quotes, long swapSats, Double marketBtcPerXmr) {
            List<Quote> shown = new ArrayList<>();
            int outdated = 0, wrongSize = 0, belowMarket = 0;
            for(Quote quote : quotes) {
                if(quote.isIncompatible()) {
                    outdated++;
                } else if(quote.isBelowMarket(marketBtcPerXmr)) {
                    belowMarket++;
                } else if(quote.maxSats() <= 0 || (swapSats > 0 && !fits(quote, swapSats))) {
                    wrongSize++;
                } else {
                    shown.add(quote);
                }
            }
            shown.sort(Comparator.comparingLong(Quote::priceSats));
            Quote bestRate = shown.isEmpty() ? null : shown.get(0);
            //Lowest deposit the maker may keep; the better rate wins a tie
            Quote bestRefund = shown.stream().min(Comparator.comparingDouble(Quote::refundRatio)).orElse(null);
            return new Offers(List.copyOf(shown), bestRate, bestRefund, outdated, wrongSize, belowMarket);
        }

        String footer(boolean marketKnown) {
            List<String> hidden = new ArrayList<>();
            if(belowMarket > 0) {
                hidden.add(belowMarket + " priced below the market (makers that undercut the market refuse the swap)");
            }
            if(wrongSize > 0) {
                hidden.add(wrongSize + " that cannot take this amount");
            }
            if(outdated > 0) {
                hidden.add(outdated + " running outdated software");
            }
            String text = hidden.isEmpty() ? "" : "Not shown: " + String.join(", ", hidden) + ".";
            if(!marketKnown && (shown.size() + wrongSize) > 0) {
                text = (text.isEmpty() ? "" : text + " ") + "Fake offers below the market are filtered once the market price has loaded.";
            }
            return text;
        }
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
        renderOffers();
    }

    private void updateReturn(Quote best) {
        if(swapSats == 0 || best == null) {
            expectedReturn.setText("—");
            returnDetail.setText(swapSats == 0 ? "" : "No maker takes this amount right now.");
        } else {
            expectedReturn.setText(String.format(Locale.ROOT, "%.6f XMR", swapSats / (double)best.priceSats()));
            returnDetail.setText("At the best rate offered for this coin. The maker confirms the final amount, shown before you approve.");
        }
    }

    private void updateState() {
        boolean running = SwapService.get().runningProperty().get();
        refreshBtn.setDisable(!running);
        if(!running) {
            quotes.clear();
        }
        renderOffers();
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
            quotes.setAll(list);
            renderOffers();
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
            marketLine.setText("Market price unavailable until Tor is running");
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
            marketLine.setText(String.format(Locale.ROOT, "Market: 1 BTC → %.2f XMR (%s, over Tor)", 1 / price.btcPerXmr(), price.source()));
            renderOffers();
        });
        task.setOnFailed(event -> marketLine.setText("Market price unavailable: " + task.getException().getMessage()));
        Thread thread = new Thread(task, "XmrPrice");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean fits(Quote quote, long sats) {
        return sats >= quote.minSats() && sats <= quote.maxSats();
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
        renderOffers();
        messageLabel.setText("Checking that every Monero node answers through Tor…");
        SwapService.get().checkNodes(check -> {
            List<String> unreachable = SwapService.unreachable(check);
            if(!unreachable.isEmpty()) {
                taking = false;
                renderOffers();
                messageLabel.setText("Not started: " + (unreachable.size() == 1 ? "a Monero node" : unreachable.size() + " Monero nodes")
                        + " did not answer. Untick it in Settings → Swaps (keeping at least two public nodes) or add your own node, then try again.\n"
                        + String.join("\n", unreachable));
                return;
            }
            messageLabel.setText("Setting up the swap with the maker…");
            SwapService.get().startSwap(quote, xmrAddressField.getText().trim(), swapId -> {
                taking = false;
                renderOffers();
                shownSwapId = swapId;
                messageLabel.setText("");
                updateProgress();
                scrollToTop();
                refreshSwaps();
            }, e -> {
                taking = false;
                renderOffers();
                messageLabel.setText("Could not start the swap: " + e.getMessage());
            });
        }, e -> {
            taking = false;
            renderOffers();
            messageLabel.setText("Could not check the Monero nodes: " + e.getMessage());
        });
    }

    /**
     * Shows the card for the chosen swap, or the latest one, with where it is and what comes next.
     * While that swap is unfinished, or until the user goes back from a finished one, the card is
     * all the page shows.
     */
    private void updateProgress() {
        SwapService service = SwapService.get();
        String swapId = shownSwapId != null ? shownSwapId : service.getCurrentSwapId();
        SwapProgress.Status status = swapId == null || swapId.equals(dismissedSwapId) ? null : service.getProgress(swapId);
        boolean show = status != null;
        progressCard.setVisible(show);
        progressCard.setManaged(show);
        offersView.setVisible(!show);
        if(!show) {
            return;
        }

        SwapProgress.Step step = status.step();
        progressSwapId.setText("YOUR SWAP  ·  " + swapId.substring(0, Math.min(8, swapId.length())));
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

        boolean refundPath = step.ordinal() >= SwapProgress.Step.REFUND_WAIT.ordinal() && step != SwapProgress.Step.WORKING && !stopped;
        List<SwapProgress.Step> path = refundPath
                ? List.of(SwapProgress.Step.REFUND_WAIT, SwapProgress.Step.REFUNDING, SwapProgress.Step.REFUNDED)
                : SwapProgress.MAIN_STEPS;
        int current = path.indexOf(step);
        boolean showBar = current >= 0;
        stepProgress.setVisible(showBar);
        stepCount.setVisible(showBar);
        if(showBar) {
            int done = current + (status.finished() ? 1 : 0);
            stepProgress.setProgress((double)Math.max(done, 1) / path.size());
            stepCount.setText((refundPath ? "Refund step " : "Step ") + (current + 1) + " of " + path.size());
        }

        progressDone.setVisible(status.finished());
        progressDone.setManaged(status.finished());
    }

    @FXML
    private void onBackToOffers() {
        SwapService service = SwapService.get();
        dismissedSwapId = shownSwapId != null ? shownSwapId : service.getCurrentSwapId();
        shownSwapId = null;
        swapsTable.getSelectionModel().clearSelection();
        updateProgress();
        refreshAll();
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
