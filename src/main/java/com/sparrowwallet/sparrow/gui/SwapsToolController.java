package com.sparrowwallet.sparrow.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.swap.ReviewPresenter;
import com.sparrowwallet.sparrow.swap.SwapAttention;
import com.sparrowwallet.sparrow.swap.SwapCopy;
import com.sparrowwallet.sparrow.swap.SwapDashboard;
import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import com.sparrowwallet.sparrow.swap.SwapProgress;
import com.sparrowwallet.sparrow.swap.SwapReview;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Tools - Swaps. Shows the offers that can take the Badbank coin chosen in the wallet view, takes
 * one, and follows the swap through to the end. While a swap runs the page shows only that swap.
 * The engine itself lives in {@link SwapService}, so leaving this page never stops a swap.
 */
public class SwapsToolController implements Initializable {
    @FXML private StackPane swapsRoot;
    @FXML private VBox mainView;
    @FXML private Label engineStatus;
    @FXML private VBox offersView;
    @FXML private VBox offersBox;
    @FXML private TableView<JsonObject> swapsTable;
    @FXML private VBox dashboardBox;

    private final ObservableList<Quote> quotes = FXCollections.observableArrayList();
    private final ObservableList<JsonObject> swaps = FXCollections.observableArrayList();
    private XmrPrice.Snapshot price;
    //Fee and swap amount for the chosen coin, worked out when the page opens or the coin changes
    private long lockFee;
    private long swapSats;
    //The offers screen and what it is shown besides the quotes
    private SwapOffersPane offersPane;
    private List<SwapAttention> attention = List.of();
    private String marketText = "Market price unavailable until Tor is running";
    private Instant quotesUpdated;
    private String coinProblem;
    //While a swap is being set up: what is happening, and with which maker
    private String working;
    private String requesting;
    private Timeline refresher;
    //Ticks the card's "on this step for" time
    private Timeline ticker;
    //The swap shown in the card: the one picked in the table, otherwise the latest started
    private String shownSwapId;
    //A finished swap the user has gone back to offers from
    private String dismissedSwapId;
    //The dashboard is drawn again only when something it shows has changed
    private SwapDashboardPane dashboardPane;
    private SwapDashboard lastDashboard;
    private SwapDashboardPane.Connections lastConnections;
    private String lastDashboardSwap;
    //Swaps being cancelled and refunded now: the engine's call blocks until the refund is published
    private final Set<String> refunding = new HashSet<>();

    //The final review, drawn over the page while the swap asks for a decision
    private SwapReviewPane reviewPane;
    private final ReviewPresenter presenter = new ReviewPresenter() {
        @Override
        public boolean isShowing() {
            return swapsRoot.getScene() != null && swapsRoot.getScene().getWindow() != null && swapsRoot.getScene().getWindow().isShowing();
        }

        @Override
        public void present(SwapReview review) {
            showReview(review);
        }

        @Override
        public void dismiss() {
            hideReview();
        }
    };

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
        offersPane = new SwapOffersPane(new SwapOffersPane.Listener() {
            @Override
            public void review(Quote quote) {
                onTake(quote);
            }

            @Override
            public void chooseCoin() {
                AshigaruGui.get().getMainController().openBadbank();
            }

            @Override
            public void viewRecovery(String swapId) {
                showSwapId(swapId);
            }

            @Override
            public void refresh() {
                onRefresh();
            }
        });
        offersBox.getChildren().add(offersPane);
        offersView.managedProperty().bind(offersView.visibleProperty());
        mainView.managedProperty().bind(mainView.visibleProperty());
        dashboardBox.managedProperty().bind(dashboardBox.visibleProperty());
        dashboardPane = new SwapDashboardPane();
        dashboardPane.setOnAction(this::onDashboardAction);
        dashboardBox.getChildren().add(dashboardPane);
        reviewPane = new SwapReviewPane();
        reviewPane.setVisible(false);
        reviewPane.managedProperty().bind(reviewPane.visibleProperty());
        swapsRoot.getChildren().add(reviewPane);
        service.setReviewPresenter(presenter);

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
        //A review still on this page moves to a dialog, so the request is not left waiting
        service.clearReviewPresenter(presenter);
        reviewPane.clear();
        service.removeSwapListener(swapListener);
        service.runningProperty().removeListener(runningListener);
        service.coinProperty().removeListener(coinListener);
        engineStatus.textProperty().unbind();
    }

    private void showReview(SwapReview review) {
        reviewPane.show(review);
        mainView.setVisible(false);
        reviewPane.setVisible(true);
    }

    private void hideReview() {
        reviewPane.clear();
        reviewPane.setVisible(false);
        mainView.setVisible(true);
        updateProgress();
    }

    /** Draws the offers screen: only offers that can take this coin, compared by what they would give */
    private void renderOffers() {
        Double market = price == null ? null : price.btcPerXmr();
        Offers offers = Offers.of(quotes, swapSats, market);
        SwapService.Coin coin = SwapService.get().coinProperty().get();
        boolean running = SwapService.get().runningProperty().get();
        String empty = !offers.shown().isEmpty() ? null
                : !running ? "Swaps are not running. Turn them on in Settings → Swaps."
                : quotes.isEmpty() ? "No makers yet. They are found over a minute or two after swaps start."
                : offers.outdated() > 0 && offers.outdated() == quotes.size() ? "Every maker found still runs eigenwallet 4 or older, which this version of Ashigaru cannot swap with. "
                        + "eigenwallet 5 changed the protocol and both sides must run it. Makers are upgrading, so try again later."
                : offers.newer() > 0 && offers.newer() == quotes.size() ? "Every maker found runs newer swap software than this version of Ashigaru can swap with yet. "
                        + "Ashigaru needs an update before it can swap with those makers."
                : swapSats > 0 ? "No maker can take this coin right now." : "No usable offers right now.";
        offersPane.show(new SwapOffersPane.State(attention, coin != null, coin == null ? 0 : coin.outpoint().getValue(), coin == null ? "" : coin.walletName(),
                swapSats, lockFee, coinProblem, offers.shown(), offers.footer(market != null, swapSats > 0 ? btc(swapSats) : null), marketText, market, running,
                empty, this::offerProblem, working, requesting, quotesUpdated));
    }

    /**
     * The offers worth showing for a coin, and how many were left out and why. Outdated makers, offers
     * that cannot take the coin and offers below the market are never shown: none of them can
     * complete a swap.
     */
    record Offers(List<Quote> shown, Quote bestRate, Quote bestRefund, int outdated, int wrongSize, int belowMarket, int soldOut, int newer) {
        static Offers of(List<Quote> quotes, long swapSats, Double marketBtcPerXmr) {
            List<Quote> shown = new ArrayList<>();
            int outdated = 0, wrongSize = 0, belowMarket = 0, soldOut = 0, newer = 0;
            for(Quote quote : quotes) {
                if(quote.isTooNew()) {
                    newer++;
                } else if(quote.isOutdated()) {
                    outdated++;
                } else if(quote.isBelowMarket(marketBtcPerXmr)) {
                    belowMarket++;
                } else if(quote.maxSats() <= 0) {
                    soldOut++;
                } else if(swapSats > 0 && !fits(quote, swapSats)) {
                    wrongSize++;
                } else {
                    shown.add(quote);
                }
            }
            shown.sort(Comparator.comparingLong(Quote::priceSats));
            Quote bestRate = shown.isEmpty() ? null : shown.get(0);
            //Lowest deposit the maker may keep; the better rate wins a tie
            Quote bestRefund = shown.stream().min(Comparator.comparingDouble(Quote::refundRatio)).orElse(null);
            return new Offers(List.copyOf(shown), bestRate, bestRefund, outdated, wrongSize, belowMarket, soldOut, newer);
        }

        /**
         * Why some offers are not in the table. "Below the market" in the engine's terms means a maker offering
         * more XMR than the market pays, which no honest maker does, so that is how it is put. The amount
         * only appears once a coin is chosen, so with none it is never mentioned.
         */
        String footer(boolean marketKnown, String amountBtc) {
            List<String> hidden = new ArrayList<>();
            if(belowMarket > 0) {
                hidden.add(belowMarket + " offering more XMR than the market pays (makers that undercut the market refuse swaps)");
            }
            if(wrongSize > 0) {
                hidden.add(wrongSize + " that cannot take " + (amountBtc == null ? "this amount" : amountBtc + " BTC"));
            }
            if(soldOut > 0) {
                hidden.add(soldOut + " with no XMR to sell right now");
            }
            if(outdated > 0) {
                hidden.add(outdated + " running eigenwallet 4 or older, which cannot swap with this version (eigenwallet 5 changed the protocol)");
            }
            if(newer > 0) {
                hidden.add(newer + " running newer swap software than this version of Ashigaru supports");
            }
            String text = hidden.isEmpty() ? "" : "Not shown: " + String.join(", ", hidden) + ".";
            if(!marketKnown && (shown.size() + wrongSize) > 0) {
                text = (text.isEmpty() ? "" : text + " ") + "Offers promising more XMR than the market pays are filtered out once the market price has loaded.";
            }
            return text;
        }
    }

    private void setUpSwapsTable() {
        TableColumn<JsonObject, String> started = column("Started", swap -> {
            Instant when = startedAt(swap);
            return when == null ? "" : STARTED.format(when);
        });
        started.setPrefWidth(140);
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
            private final Button refund = new Button("View recovery");

            {
                refund.getStyleClass().add("secondary-btn");
                refund.setMinWidth(Button.USE_PREF_SIZE);
                refund.setOnAction(event -> showSwap(getItem()));
            }

            @Override
            protected void updateItem(JsonObject swap, boolean empty) {
                super.updateItem(swap, empty);
                //A running swap refunds by itself once its timelock allows; a stopped, unfinished one is cancelled from its
                //recovery screen, which this opens. A past identity's swap is only shown: no engine holds its database to act on it
                boolean canRefund = !empty && swap != null && !swap.get("complete").getAsBoolean() && !swap.get("running").getAsBoolean()
                        && !(swap.has("past") && swap.get("past").getAsBoolean());
                setGraphic(canRefund ? refund : null);
            }
        });
        action.setPrefWidth(130);
        swapsTable.getColumns().setAll(List.of(started, id, state, progress, action));
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
        coinProblem = null;
        swapSats = 0;
        if(coin != null) {
            try {
                long[] feeAndAmount = SwapService.get().lockFeeAndAmount();
                lockFee = feeAndAmount[0];
                swapSats = feeAndAmount[1];
            } catch(Exception e) {
                coinProblem = e.getMessage();
            }
        }
        renderOffers();
    }

    private void updateState() {
        if(!SwapService.get().runningProperty().get()) {
            quotes.clear();
        }
        renderOffers();
    }

    private void refreshAll() {
        refreshQuotes();
        refreshSwaps();
    }

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
            quotesUpdated = Instant.now();
            renderOffers();
        }, e -> offersPane.setMessage("Could not list offers: " + e.getMessage()));
    }

    private void refreshSwaps() {
        if(!SwapService.get().runningProperty().get()) {
            return;
        }
        SwapService.get().listSwaps(list -> {
            List<JsonObject> all = new ArrayList<>();
            for(JsonElement element : list) {
                all.add(element.getAsJsonObject());
            }
            //Newest first; a swap without a start date goes last
            all.sort(Comparator.comparing((JsonObject swap) -> startedAt(swap), Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
            swaps.setAll(all);
            //A swap that holds BTC and is not running comes before any new offer
            attention = SwapAttention.of(list, Instant.now());
            renderOffers();
        }, e -> {});
    }

    private void refreshPrice() {
        if(AppServices.getTorProxy() == null) {
            marketText = "Market price unavailable until Tor is running";
            renderOffers();
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
            marketText = String.format(Locale.ROOT, "Market 1 BTC ≈ %.2f XMR (%s, over Tor). Not an offer you can take.", 1 / price.btcPerXmr(), price.source());
            renderOffers();
        });
        task.setOnFailed(event -> {
            marketText = "Market price unavailable: " + task.getException().getMessage();
            renderOffers();
        });
        Thread thread = new Thread(task, "XmrPrice");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean fits(Quote quote, long sats) {
        return sats >= quote.minSats() && sats <= quote.maxSats();
    }

    /** Why this offer cannot be reviewed now, or null. Shown as the reason on the disabled button. */
    private String offerProblem(Quote quote) {
        if(SwapService.get().coinProperty().get() == null || swapSats == 0) {
            return "Choose a Badbank coin first";
        }
        if(!fits(quote, swapSats)) {
            return "This maker takes " + btc(quote.minSats()) + " to " + btc(quote.maxSats()) + " BTC; this coin swaps " + btc(swapSats) + " BTC.";
        }
        long maxSats = Config.get().getSwapMaxSats();
        if(swapSats > maxSats) {
            return "Swaps are limited to " + btc(maxSats) + " BTC. Change the maximum in Settings → Swaps.";
        }
        if(!isMainnetMoneroAddress(offersPane.getAddress())) {
            return "Enter your receiving XMR address first";
        }
        if(SwapService.get().isSwapActive()) {
            return "A swap is already running; one at a time.";
        }
        return null;
    }

    private void endTaking() {
        working = null;
        requesting = null;
        renderOffers();
    }

    private void onTake(Quote quote) {
        if(quote == null) {
            return;
        }
        String problem = offerProblem(quote);
        if(problem != null) {
            offersPane.setMessage(problem);
            return;
        }
        offersPane.setMessage(null);
        requesting = quote.peerId();
        working = "Checking that every Monero node answers through Tor…";
        renderOffers();
        SwapService.get().checkNodes(check -> {
            List<String> unreachable = SwapService.unreachable(check);
            if(!unreachable.isEmpty()) {
                endTaking();
                offersPane.setMessage("Not started: " + (unreachable.size() == 1 ? "a Monero node" : unreachable.size() + " Monero nodes")
                        + " did not answer. Untick it in Settings → Swaps (keeping at least two public nodes) or add your own node, then try again.\n"
                        + String.join("\n", unreachable));
                return;
            }
            working = "Setting up the swap with the maker. If Ashigaru asks for your wallet password, it is to prove to the maker that you own the coin and to sign the lock; "
                    + "nothing is locked or sent until you approve the final terms. The setup takes a few minutes.";
            renderOffers();
            SwapService.get().startSwap(quote, offersPane.getAddress(), swapId -> {
                endTaking();
                shownSwapId = swapId;
                offersPane.setMessage(null);
                updateProgress();
                scrollToTop();
                refreshSwaps();
            }, e -> {
                endTaking();
                offersPane.setMessage("Could not start the swap: " + e.getMessage());
            });
        }, e -> {
            endTaking();
            offersPane.setMessage("Could not check the Monero nodes: " + e.getMessage());
        });
    }

    /**
     * Shows the dashboard for the chosen swap, or the latest one: where it is, whether the user must act,
     * where the funds are, and what happens if it stalls. While that swap is unfinished, or until the user
     * goes back from a finished one, the dashboard is all the page shows. It is worked out by
     * {@link SwapDashboard} and drawn again only when something it shows has changed.
     */
    private void updateProgress() {
        SwapService service = SwapService.get();
        String swapId = shownSwapId != null ? shownSwapId : service.getCurrentSwapId();
        SwapProgress.Status status = swapId == null || swapId.equals(dismissedSwapId) ? null : service.getProgress(swapId);
        boolean show = status != null;
        dashboardBox.setVisible(show);
        offersView.setVisible(!show);
        if(!show) {
            lastDashboard = null;
            lastDashboardSwap = null;
            return;
        }

        SwapDashboard dashboard = SwapDashboard.of(new SwapDashboard.Input(swapId, status, service.getTimelock(swapId), service.isSwapRunning(swapId),
                service.getDetails(swapId), refunding.contains(swapId), Instant.now()));
        SwapDashboardPane.Connections connections = connections(service);
        if(!swapId.equals(lastDashboardSwap) || !dashboard.equals(lastDashboard) || !connections.equals(lastConnections)) {
            dashboardPane.show(swapId, dashboard, connections);
            lastDashboard = dashboard;
            lastConnections = connections;
            lastDashboardSwap = swapId;
        }
    }

    /** The services a swap depends on, as far as Ashigaru can tell, and what is wrong with the ones that are down */
    private static SwapDashboardPane.Connections connections(SwapService service) {
        List<String> working = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if(AppServices.getTorProxy() != null) {
            working.add("Tor");
        } else {
            problems.add("Tor is not running: the swap cannot reach makers or Monero nodes until it is.");
        }
        if(AppServices.isConnected()) {
            working.add("Bitcoin server");
        } else {
            problems.add("Not connected to a Bitcoin server: a lock cannot be published or confirmed until you are.");
        }
        if(service.runningProperty().get()) {
            working.add("Swap engine");
        } else {
            problems.add("The swap engine is off, so the swap is paused. Turn swaps on in Settings → Swaps.");
        }
        return new SwapDashboardPane.Connections(working, problems);
    }

    private void onDashboardAction(SwapDashboard.Action action) {
        switch(action) {
            case BACK_TO_OFFERS -> onBackToOffers();
            case CANCEL_AND_REFUND -> {
                SwapService service = SwapService.get();
                refund(shownSwapId != null ? shownSwapId : service.getCurrentSwapId());
            }
        }
    }

    @FXML
    private void onBackToOffers() {
        SwapService service = SwapService.get();
        dismissedSwapId = shownSwapId != null ? shownSwapId : service.getCurrentSwapId();
        shownSwapId = null;
        swapsTable.getSelectionModel().clearSelection();
        dashboardPane.setMessage(null);
        updateProgress();
        refreshAll();
    }

    /** The card sits at the top: bring it into view after Take */
    private void scrollToTop() {
        javafx.scene.Node node = dashboardBox;
        while(node != null && !(node instanceof ScrollPane)) {
            node = node.getParent();
        }
        if(node instanceof ScrollPane scroll) {
            scroll.setVvalue(0);
        }
    }

    /**
     * Cancels and refunds a swap that is not running. The engine publishes the cancel and then the refund
     * and blocks until done, so the dashboard shows it as under way; a failure is shown under the banner.
     */
    private void refund(String swapId) {
        if(swapId == null) {
            return;
        }
        refunding.add(swapId);
        dashboardPane.setMessage(null);
        updateProgress();
        SwapService.get().refund(swapId, state -> {
            refunding.remove(swapId);
            refreshSwaps();
            updateProgress();
        }, e -> {
            refunding.remove(swapId);
            dashboardPane.setMessage("Could not cancel and refund yet: " + e.getMessage());
            lastDashboard = null;
            updateProgress();
        });
    }

    /** Opens a swap's dashboard from the table */
    private void showSwap(JsonObject swap) {
        if(swap != null) {
            showSwapId(swap.get("swapId").getAsString());
        }
    }

    void showSwapId(String swapId) {
        shownSwapId = swapId;
        dismissedSwapId = null;
        updateProgress();
    }

    private static final java.time.format.DateTimeFormatter STARTED =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());
    //eigenwallet stores when a swap started as the time crate prints it, in UTC: "2026-10-30 6:41:05.123 +00:00:00"
    private static final java.util.regex.Pattern ENTERED_AT =
            java.util.regex.Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2}) (\\d{1,2}):(\\d{2}):(\\d{2})");

    /** When the swap started, or null when the engine did not say */
    static Instant startedAt(JsonObject swap) {
        if(!swap.has("startedAt") || swap.get("startedAt").isJsonNull()) {
            return null;
        }
        java.util.regex.Matcher m = ENTERED_AT.matcher(swap.get("startedAt").getAsString());
        if(!m.find()) {
            return null;
        }
        return java.time.LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))).toInstant(java.time.ZoneOffset.UTC);
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
                        + "and nothing cancels or refunds your BTC until Ashigaru runs again. Reopen it soon. With swaps on, "
                        + "it picks unfinished swaps back up and, if one fails, cancels and refunds it for you. A refund must "
                        + "be published within " + SwapCopy.refundWindow() + " of the cancel confirming.\n\nQuit anyway?",
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
