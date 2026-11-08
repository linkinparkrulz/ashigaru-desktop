package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.swap.SwapReview;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Draws the swap screens with the app's real fonts and stylesheets, checks how they behave, and saves
 * screenshots. It needs a display (under Xvfb, with -Dprism.order=sw) so it only runs when the
 * environment variable SWAP_SCREENSHOTS names a folder for the pictures:
 *
 * <pre>SWAP_SCREENSHOTS=/tmp/shots xvfb-run -a ./gradlew :test --tests '*SwapScreenshotsTest*'</pre>
 */
@EnabledIfEnvironmentVariable(named = "SWAP_SCREENSHOTS", matches = ".+")
class SwapScreenshotsTest {
    //Shaped like a mainnet address (95 characters starting with 4) but not a real one
    static final String ADDRESS = "4q2Dkq6ozw8yQ3t9g3QygMp3xMYfnw6f2mr16unD8uzXkyQBS7As25AhCje1UdemiPgTYu21LugRY98q6ZvjEKvdgq2mtVY";

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch(IllegalStateException alreadyRunning) {
            started.countDown();
        }
        assertTrue(started.await(30, TimeUnit.SECONDS), "JavaFX did not start");
        Platform.setImplicitExit(false);
        onFx(() -> {
            for(String font : List.of("RobotoMono-Regular", "Roboto-Regular", "Roboto-Medium", "Roboto-Bold", "NikkyouSans")) {
                Font.loadFont(SwapScreenshotsTest.class.getResourceAsStream("/font/" + font + ".ttf"), 14);
            }
            return null;
        });
    }

    static <T> T onFx(Supplier<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.get());
            } catch(Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "JavaFX did not answer");
        if(failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    /** Shows the node in a window the size of the Swaps content area, lets it lay out, and saves a picture */
    static void shoot(Node node, double width, double height, String name) throws Exception {
        Path dir = Path.of(System.getenv("SWAP_SCREENSHOTS"));
        Files.createDirectories(dir);
        StackPane root = new StackPane(node);
        root.setStyle("-fx-background-color: #0E0F12;");
        AtomicReference<Stage> stage = new AtomicReference<>();
        onFx(() -> {
            Scene scene = new Scene(root, width, height);
            scene.getStylesheets().add(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/darktheme.css").toExternalForm());
            scene.getStylesheets().add(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/gui/ashigaru.css").toExternalForm());
            Stage s = new Stage();
            s.setScene(scene);
            s.show();
            stage.set(s);
            return null;
        });
        Thread.sleep(700);
        onFx(() -> {
            root.applyCss();
            root.layout();
            return null;
        });
        Thread.sleep(300);
        onFx(() -> {
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(stage.get().getScene().snapshot(null), null), "png", new File(dir.toFile(), name + ".png"));
            } catch(Exception e) {
                throw new IllegalStateException(e);
            }
            stage.get().close();
            return null;
        });
    }

    private static List<String> decisions;

    private static SwapReview.Decision recorder() {
        decisions = new ArrayList<>();
        return new SwapReview.Decision() {
            @Override
            public void confirm() {
                decisions.add("confirm");
            }

            @Override
            public void decline() {
                decisions.add("decline");
            }
        };
    }

    private static SwapReview commit(long amnestySats, boolean fullRefund) {
        //0.05 BTC coin, 1,240 sats fee, 2% deposit, 14.816324640000 XMR
        return SwapReview.commit(5_000_000, 4_998_760, 1_240, 14_816_324_640_000L, amnestySats, fullRefund, ADDRESS, "12D3KooW…b3f9",
                Instant.now().plusSeconds(161), recorder());
    }

    private static Button button(Node root, String text) {
        for(Node node : root.lookupAll(".button")) {
            if(node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        return null;
    }

    @Test
    void commitWithADepositShowsTheDeposit() throws Exception {
        SwapReviewPane pane = onFx(SwapReviewPane::new);
        onFx(() -> {
            pane.show(commit(99_975, false));
            return null;
        });
        assertNotNull(onFx(() -> pane.getBottom()), "the action bar is pinned in the commit stage");
        assertTrue(pane.expiryText().startsWith("Review expires in 2:"), pane.expiryText());
        shoot(pane, 845, 714, "review-commit-deposit-845");
        //Searching only works once the scroll pane has been shown, so it comes after the picture
        assertEquals(1, onFx(() -> pane.lookupAll(".review-callout").size()), "the deposit has its own callout");
        assertEquals(3, onFx(() -> pane.lookupAll(".review-subheading").size()), "three recovery sections");
        shoot(pane, 925, 714, "review-commit-deposit-925");
    }

    @Test
    void commitWithoutADepositHasNoCallout() throws Exception {
        SwapReviewPane pane = onFx(SwapReviewPane::new);
        onFx(() -> {
            pane.show(commit(0, false));
            return null;
        });
        shoot(pane, 845, 714, "review-commit-nodeposit-845");
        assertTrue(onFx(() -> pane.lookupAll(".review-hero").size()) > 0, "the search sees the page");
        assertEquals(0, onFx(() -> pane.lookupAll(".review-callout").size()), "no deposit, no callout");
    }

    @Test
    void approveAndRejectReportTheChoice() throws Exception {
        SwapReviewPane pane = onFx(SwapReviewPane::new);
        onFx(() -> {
            pane.show(commit(99_975, false));
            return null;
        });
        shoot(pane, 845, 714, "review-approve-845");
        Button approve = onFx(() -> button(pane, "Approve and publish BTC lock"));
        assertNotNull(approve);
        onFx(() -> {
            approve.fire();
            return null;
        });
        assertEquals(List.of("confirm"), decisions);
        //One press only: the buttons lock so a double click cannot answer twice
        assertTrue(approve.isDisabled());

        onFx(() -> {
            pane.show(commit(99_975, false));
            return null;
        });
        onFx(() -> {
            button(pane, "Reject").fire();
            return null;
        });
        assertEquals(List.of("decline"), decisions);
    }

    @Test
    void prepareStageHasNoPinnedBarAndSignsOnRequest() throws Exception {
        SwapReviewPane pane = onFx(SwapReviewPane::new);
        onFx(() -> {
            pane.show(SwapReview.prepare(5_000_000, 4_998_760, 1_240, true, recorder()));
            return null;
        });
        assertNull(onFx(() -> pane.getBottom()));
        shoot(pane, 845, 714, "review-prepare-845");
        Button sign = onFx(() -> button(pane, "Sign with wallet password"));
        assertNotNull(sign, "an encrypted wallet asks for its password");
        onFx(() -> {
            sign.fire();
            return null;
        });
        assertEquals(List.of("confirm"), decisions);

        onFx(() -> {
            pane.show(SwapReview.prepare(5_000_000, 4_998_760, 1_240, false, recorder()));
            return null;
        });
        shoot(pane, 845, 714, "review-prepare-nopassword-845");
        assertNotNull(onFx(() -> button(pane, "Sign the lock")), "an unencrypted wallet has no password to ask for");
    }

    @Test
    void theSwapsPageHostsTheReviewAndLetsGoWhenItLeaves() throws Exception {
        javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/gui/swaps.fxml"));
        Node page = onFx(() -> {
            try {
                return loader.load();
            } catch(java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        AtomicReference<Stage> stage = new AtomicReference<>();
        onFx(() -> {
            Scene scene = new Scene((javafx.scene.Parent)page, 925, 714);
            scene.getStylesheets().add(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/darktheme.css").toExternalForm());
            scene.getStylesheets().add(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/gui/ashigaru.css").toExternalForm());
            Stage s = new Stage();
            s.setScene(scene);
            s.show();
            stage.set(s);
            return null;
        });
        Thread.sleep(600);

        //While the page is on screen a review goes to it
        com.sparrowwallet.sparrow.swap.ReviewPresenter presenter = onFx(() -> com.sparrowwallet.sparrow.swap.SwapService.get().getReviewPresenter());
        assertNotNull(presenter, "the page registers while it is showing");
        Node main = onFx(() -> page.lookup("#mainView"));
        assertTrue(onFx(main::isVisible));
        onFx(() -> {
            presenter.present(commit(99_975, false));
            return null;
        });
        Thread.sleep(400);
        assertFalse(onFx(main::isVisible), "the page gives way to the review");
        assertEquals(1, onFx(() -> ((StackPane)page).getChildren().stream().filter(n -> n instanceof SwapReviewPane && n.isVisible()).count()));
        Path dir = Path.of(System.getenv("SWAP_SCREENSHOTS"));
        onFx(() -> {
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(stage.get().getScene().snapshot(null), null), "png", new File(dir.toFile(), "swaps-page-with-review-925.png"));
            } catch(Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        //Answered or expired, the page comes back
        onFx(() -> {
            presenter.dismiss();
            return null;
        });
        assertTrue(onFx(main::isVisible), "the page comes back");

        //Leaving the page lets the presenter go, so the next review is asked in a dialog
        onFx(() -> {
            stage.get().getScene().setRoot(new StackPane());
            return null;
        });
        Thread.sleep(200);
        assertNull(onFx(() -> com.sparrowwallet.sparrow.swap.SwapService.get().getReviewPresenter()));
        onFx(() -> {
            stage.get().close();
            return null;
        });
    }

    @Test
    void clearingStopsTheCountdown() throws Exception {
        SwapReviewPane pane = onFx(SwapReviewPane::new);
        onFx(() -> {
            pane.show(commit(99_975, false));
            pane.clear();
            return null;
        });
        assertNull(pane.expiryText());
    }

    // ---------------------------------------------------------------- dashboard

    private static final String SWAP_ID = "3f9a21c0-7b64-4d3e-9a10-0c2b7d1e4f55";
    private static final com.sparrowwallet.sparrow.swap.SwapDetails TERMS = new com.sparrowwallet.sparrow.swap.SwapDetails(4_998_760, 1_240,
            14_816_324_640_000L, 99_975, false, ADDRESS, "12D3KooW…b3f9");
    private static final Instant WAS = Instant.now().minusSeconds(12 * 60);

    private static com.sparrowwallet.sparrow.swap.SwapDashboard dashboard(com.sparrowwallet.sparrow.swap.SwapProgress.Status status,
                                                                          com.sparrowwallet.sparrow.swap.SwapTimelock timelock, boolean running) {
        return com.sparrowwallet.sparrow.swap.SwapDashboard.of(new com.sparrowwallet.sparrow.swap.SwapDashboard.Input(SWAP_ID, status, timelock, running,
                TERMS, false, Instant.now()));
    }

    private static final SwapDashboardPane.Connections HEALTHY = new SwapDashboardPane.Connections(List.of("Tor", "Bitcoin server", "Swap engine"), List.of());

    private SwapDashboardPane draw(com.sparrowwallet.sparrow.swap.SwapDashboard dashboard, SwapDashboardPane.Connections connections, boolean expandDetails) throws Exception {
        SwapDashboardPane pane = onFx(SwapDashboardPane::new);
        onFx(() -> {
            pane.show(SWAP_ID, dashboard, connections);
            return null;
        });
        return pane;
    }

    private static com.sparrowwallet.sparrow.swap.SwapProgress.Status at(com.sparrowwallet.sparrow.swap.SwapProgress.Step step, String detail) {
        return new com.sparrowwallet.sparrow.swap.SwapProgress.Status(step, detail, WAS, step == com.sparrowwallet.sparrow.swap.SwapProgress.Step.DONE);
    }

    private static com.sparrowwallet.sparrow.swap.SwapTimelock before(int blocks) {
        return new com.sparrowwallet.sparrow.swap.SwapTimelock(com.sparrowwallet.sparrow.swap.SwapTimelock.Phase.BEFORE_CANCEL, blocks, Instant.now());
    }

    private static com.sparrowwallet.sparrow.swap.SwapTimelock open(int blocks) {
        return new com.sparrowwallet.sparrow.swap.SwapTimelock(com.sparrowwallet.sparrow.swap.SwapTimelock.Phase.CANCEL_OPEN, blocks, Instant.now());
    }

    @Test
    void dashboardShowsEachStateAndOffersOneAction() throws Exception {
        record Case(String name, com.sparrowwallet.sparrow.swap.SwapDashboard dashboard, SwapDashboardPane.Connections connections) {}
        List<Case> cases = List.of(
                new Case("dash-1-lock-confirming", dashboard(at(com.sparrowwallet.sparrow.swap.SwapProgress.Step.LOCK_CONFIRM, "1 confirmation · 9c1f4a2e…77e0a1b3"), before(13), true), HEALTHY),
                new Case("dash-2-maker-confirming", dashboard(at(com.sparrowwallet.sparrow.swap.SwapProgress.Step.MAKER_CONFIRM, "6 of 10 confirmations"), before(9), true),
                        new SwapDashboardPane.Connections(List.of("Tor", "Bitcoin server"), List.of("The swap engine is off, so the swap is paused. Turn swaps on in Settings → Swaps."))),
                new Case("dash-3-recovery-automatic", dashboard(at(com.sparrowwallet.sparrow.swap.SwapProgress.Step.REFUND_WAIT, null), open(120), true), HEALTHY),
                new Case("dash-4-recovery-needs-you", dashboard(new com.sparrowwallet.sparrow.swap.SwapProgress.Status(com.sparrowwallet.sparrow.swap.SwapProgress.Step.STOPPED,
                        "The swap stopped.", WAS, true, com.sparrowwallet.sparrow.swap.SwapProgress.Step.MAKER_CONFIRM), open(140), false), HEALTHY),
                new Case("dash-5-recovery-not-yet", dashboard(new com.sparrowwallet.sparrow.swap.SwapProgress.Status(com.sparrowwallet.sparrow.swap.SwapProgress.Step.STOPPED,
                        "The swap stopped.", WAS, true, com.sparrowwallet.sparrow.swap.SwapProgress.Step.MAKER_CONFIRM), before(13), false), HEALTHY),
                new Case("dash-6-complete", dashboard(at(com.sparrowwallet.sparrow.swap.SwapProgress.Step.DONE, "XMR transaction 5e07b1d4…c29a7f60"), null, false), HEALTHY));
        for(Case c : cases) {
            SwapDashboardPane pane = draw(c.dashboard(), c.connections(), false);
            shoot(pane, 845, 1100, c.name() + "-845");
            //A long value such as the full address must wrap and never force the page wider than its window
            double minWidth = onFx(() -> pane.minWidth(-1));
            //713px is what Swaps gets at the smallest window (1200) with the Tools menu open
            assertTrue(minWidth <= 700, c.name() + " needs at least " + minWidth + "px");
        }

        //The one action is the banner's button, and it is only enabled when it can work
        List<com.sparrowwallet.sparrow.swap.SwapDashboard.Action> pressed = new ArrayList<>();
        SwapDashboardPane needsYou = draw(cases.get(3).dashboard(), HEALTHY, false);
        onFx(() -> {
            needsYou.setOnAction(pressed::add);
            needsYou.show(SWAP_ID, cases.get(3).dashboard(), HEALTHY);
            return null;
        });
        shoot(needsYou, 845, 1100, "dash-4-recovery-needs-you-again");
        Button cancel = onFx(() -> button(needsYou, "Cancel and refund"));
        assertNotNull(cancel);
        assertFalse(onFx(cancel::isDisabled));
        onFx(() -> {
            cancel.fire();
            return null;
        });
        assertEquals(List.of(com.sparrowwallet.sparrow.swap.SwapDashboard.Action.CANCEL_AND_REFUND), pressed);

        SwapDashboardPane notYet = draw(cases.get(4).dashboard(), HEALTHY, false);
        shoot(notYet, 845, 1100, "dash-5-recovery-not-yet-again");
        Button disabled = onFx(() -> button(notYet, "Cancel and refund"));
        assertTrue(onFx(disabled::isDisabled), "cancelling is not open yet, so the button says when and cannot be pressed");

        SwapDashboardPane automatic = draw(cases.get(2).dashboard(), HEALTHY, false);
        shoot(automatic, 845, 1100, "dash-3-recovery-automatic-again");
        assertNull(onFx(() -> button(automatic, "Cancel and refund")), "a running swap is refunded by Ashigaru itself, so there is no button");
    }

    @Test
    void theSwapsPageFollowsTheEnginesEventsToTheDashboard() throws Exception {
        javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/gui/swaps.fxml"));
        Node page = onFx(() -> {
            try {
                return loader.load();
            } catch(java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        SwapsToolController controller = loader.getController();
        AtomicReference<Stage> stage = new AtomicReference<>();
        onFx(() -> {
            Scene scene = new Scene((javafx.scene.Parent)page, 925, 800);
            scene.getStylesheets().add(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/darktheme.css").toExternalForm());
            scene.getStylesheets().add(SwapScreenshotsTest.class.getResource("/com/sparrowwallet/sparrow/gui/ashigaru.css").toExternalForm());
            Stage s = new Stage();
            s.setScene(scene);
            s.show();
            stage.set(s);
            return null;
        });
        Thread.sleep(500);

        String id = "aaaaaaaa-0000-4000-8000-000000000001";
        com.sparrowwallet.sparrow.swap.SwapService service = onFx(com.sparrowwallet.sparrow.swap.SwapService::get);
        //Exactly the shape the engine sends: a progress event, then a timelock change
        service.onNotification("event", com.google.gson.JsonParser.parseString("{\"event\":\"SwapProgress\",\"payload\":{\"channelName\":\"SwapProgress\","
                + "\"event\":{\"swap_id\":\"" + id + "\",\"event\":{\"type\":\"BtcLockTxInMempool\",\"content\":{\"btc_lock_txid\":\"9c1f4a2e0000000000000000000000000000000000000000000000000077e0a1b3\",\"btc_lock_confirmations\":1}}}}}").getAsJsonObject());
        service.onNotification("event", com.google.gson.JsonParser.parseString("{\"event\":\"TimelockChange\",\"payload\":{\"channelName\":\"TimelockChange\","
                + "\"event\":{\"swap_id\":\"" + id + "\",\"timelock\":{\"type\":\"None\",\"content\":{\"blocks_left\":13}}}}}").getAsJsonObject());
        Thread.sleep(400);
        onFx(() -> {
            controller.showSwapId(id);
            return null;
        });
        Thread.sleep(400);

        Node dash = onFx(() -> page.lookup("#dashboardBox"));
        assertTrue(onFx(dash::isVisible), "the dashboard shows for a swap that has reported progress");
        Node offers = onFx(() -> page.lookup("#offersView"));
        assertFalse(onFx(offers::isVisible), "while a swap runs it is all the page shows");
        assertEquals("Nothing for you to do", onFx(() -> ((javafx.scene.control.Label)page.lookup(".dash-banner-title")).getText()));
        //The countdown came from the timelock event: 13 blocks is about 2 h 10 min
        assertTrue(onFx(() -> page.lookupAll(".dash-body-text").stream().anyMatch(n -> n instanceof javafx.scene.control.Label l && l.getText().contains("about 2 h 10 min"))));
        Path dir = Path.of(System.getenv("SWAP_SCREENSHOTS"));
        onFx(() -> {
            try {
                ImageIO.write(SwingFXUtils.fromFXImage(stage.get().getScene().snapshot(null), null), "png", new File(dir.toFile(), "swaps-page-dashboard-925.png"));
            } catch(Exception e) {
                throw new IllegalStateException(e);
            }
            stage.get().close();
            return null;
        });
    }

    // ---------------------------------------------------------------- offers

    private static final double[] RATES = {297.35, 296.90, 296.40, 296.10, 295.60, 295.20, 294.70, 293.90, 292.50};
    private static final double[] DEPOSITS = {0.02, 0, 0, 0.05, 0, 0.02, 0, 0.10, 0};

    private static List<com.sparrowwallet.sparrow.swap.SwapEngine.Quote> makers() {
        List<com.sparrowwallet.sparrow.swap.SwapEngine.Quote> quotes = new ArrayList<>();
        for(int i = 0; i < RATES.length; i++) {
            String policy = DEPOSITS[i] == 0 ? "{\"type\":\"FullRefund\"}"
                    : "{\"type\":\"PartialRefund\",\"content\":{\"anti_spam_deposit_ratio\":" + DEPOSITS[i] + "}}";
            quotes.add(new com.sparrowwallet.sparrow.swap.SwapEngine.Quote("12D3KooWMaker" + i + "abcdefghijklmnopqrstuvwxyz", "/ip4/10.0.0." + (i + 1) + "/tcp/9939", "4.16.1",
                    (long)(1e8 / RATES[i]), 100_000, 100_000_000, policy));
        }
        return quotes;
    }

    private SwapOffersPane.State offersState(boolean coin, String requesting, String working, boolean attention, SwapOffersPane pane) {
        return new SwapOffersPane.State(
                attention ? com.sparrowwallet.sparrow.swap.SwapAttention.of(com.google.gson.JsonParser.parseString(
                        "[{\"swapId\":\"c20e9a17-0000-4000-8000-000000000000\",\"state\":\"waiting for cancel timelock expiration\",\"complete\":false,\"running\":false,\"past\":false}]").getAsJsonArray(), Instant.now()) : List.of(),
                coin, coin ? 5_000_000 : 0, coin ? "Main" : "", coin ? 4_998_760 : 0, 1_240, null, makers(),
                "Not shown: 3 offering more XMR than the market pays (makers that undercut the market refuse swaps)" + (coin ? ", 2 that cannot take 0.04998760 BTC" : "") + ", 1 running outdated software.",
                "Market 1 BTC ≈ 298.80 XMR (Kraken, over Tor). Not an offer you can take.", 1 / 298.80, true, null,
                quote -> !coin ? "Choose a Badbank coin first" : !SwapsToolController.isMainnetMoneroAddress(pane.getAddress()) ? "Enter your receiving XMR address first" : null,
                working, requesting, Instant.now());
    }

    private SwapOffersPane offers(boolean coin, String address, String requesting, String working, boolean attention, List<String> pressed) throws Exception {
        AtomicReference<SwapOffersPane> holder = new AtomicReference<>();
        SwapOffersPane pane = onFx(() -> {
            SwapOffersPane p = new SwapOffersPane(new SwapOffersPane.Listener() {
                @Override
                public void review(com.sparrowwallet.sparrow.swap.SwapEngine.Quote quote) {
                    pressed.add("review " + quote.peerId());
                }

                @Override
                public void chooseCoin() {
                    pressed.add("choose");
                }

                @Override
                public void viewRecovery(String swapId) {
                    pressed.add("recovery " + swapId);
                }

                @Override
                public void refresh() {
                    pressed.add("refresh");
                }
            });
            holder.set(p);
            return p;
        });
        onFx(() -> {
            pane.show(offersState(coin, requesting, working, attention, pane));
            return null;
        });
        if(address != null) {
            typeAddress(pane, address);
        }
        return pane;
    }

    /** Types into the pane's address field, which redraws the table as the real field would */
    private static void typeAddress(SwapOffersPane pane, String address) throws Exception {
        onFx(() -> {
            javafx.scene.control.TextField field = (javafx.scene.control.TextField)findOne(pane, javafx.scene.control.TextField.class);
            field.setText(address);
            return null;
        });
    }

    private static Node findOne(Node root, Class<? extends Node> type) {
        if(type.isInstance(root)) {
            return root;
        }
        if(root instanceof javafx.scene.Parent parent) {
            for(Node child : parent.getChildrenUnmodifiable()) {
                Node found = findOne(child, type);
                if(found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<Button> reviewButtons(Node root) {
        List<Button> buttons = new ArrayList<>();
        for(Node node : root.lookupAll(".offer-review")) {
            if(node instanceof Button button) {
                buttons.add(button);
            }
        }
        return buttons;
    }

    @Test
    void offersAreReadyToReviewOnceThereIsACoinAndAnAddress() throws Exception {
        List<String> pressed = new ArrayList<>();
        SwapOffersPane pane = offers(true, ADDRESS, null, null, true, pressed);
        shoot(pane, 845, 1500, "offers-ready-845");
        List<Button> buttons = onFx(() -> reviewButtons(pane));
        assertEquals(9, buttons.size(), "nine offers, nine ways to review one");
        assertTrue(buttons.stream().noneMatch(Button::isDisable));
        //The first is the one with the most XMR, and it is marked as such
        assertEquals(1, onFx(() -> pane.lookupAll(".offer-chip").stream().filter(n -> n instanceof javafx.scene.control.Label l && l.getText().equals("Most XMR")).count()));
        assertEquals(1, onFx(() -> pane.lookupAll(".offer-chip").stream().filter(n -> n instanceof javafx.scene.control.Label l && l.getText().equals("Best refund")).count()));
        //The unfinished swap comes first, with a way to its recovery
        Button recovery = onFx(() -> {
            for(Node node : pane.lookupAll(".button")) {
                if(node instanceof Button b && "View recovery".equals(b.getText())) {
                    return b;
                }
            }
            return null;
        });
        assertNotNull(recovery);
        onFx(() -> {
            recovery.fire();
            return null;
        });
        assertEquals(List.of("recovery c20e9a17-0000-4000-8000-000000000000"), pressed);

        onFx(() -> {
            buttons.get(1).fire();
            return null;
        });
        assertTrue(pressed.get(1).startsWith("review "));
        //Primary text is never shortened to fit
        assertEquals(0, onFx(() -> pane.lookupAll(".offer-review").stream().filter(n -> n instanceof Button b && b.getText().endsWith("…")).count()));
    }

    @Test
    void withoutACoinThereIsAButtonAndTheOffersCannotBeReviewed() throws Exception {
        List<String> pressed = new ArrayList<>();
        SwapOffersPane pane = offers(false, null, null, null, false, pressed);
        shoot(pane, 845, 1300, "offers-no-coin-845");
        Button choose = onFx(() -> {
            for(Node node : pane.lookupAll(".button")) {
                if(node instanceof Button b && "Choose coin from Badbank".equals(b.getText())) {
                    return b;
                }
            }
            return null;
        });
        assertNotNull(choose, "a clear action, not only instructional text");
        onFx(() -> {
            choose.fire();
            return null;
        });
        assertEquals(List.of("choose"), pressed);
        assertTrue(onFx(() -> reviewButtons(pane)).stream().allMatch(Button::isDisable));
        //With no coin the footer never mentions an amount
        assertEquals(0, onFx(() -> pane.lookupAll(".review-fine").stream().filter(n -> n instanceof javafx.scene.control.Label l && l.getText().contains("cannot take 0.")).count()));
    }

    @Test
    void typingAValidAddressEnablesTheOffers() throws Exception {
        SwapOffersPane pane = offers(true, null, null, null, false, new ArrayList<>());
        shoot(pane, 845, 1300, "offers-no-address-845");
        assertTrue(onFx(() -> reviewButtons(pane)).stream().allMatch(Button::isDisable), "no address, no review");
        typeAddress(pane, "4abc");
        assertTrue(onFx(() -> reviewButtons(pane)).stream().allMatch(Button::isDisable), "a short address is not enough");
        assertTrue(onFx(() -> pane.lookupAll(".offer-status-warn").stream().anyMatch(n -> n instanceof javafx.scene.control.Label l && l.getText().contains("This one has 4"))));
        typeAddress(pane, ADDRESS);
        assertTrue(onFx(() -> reviewButtons(pane)).stream().noneMatch(Button::isDisable), "a valid address enables every offer");
        assertEquals(ADDRESS, onFx(pane::getAddress));
    }

    @Test
    void sortingReordersAndDetailsExpand() throws Exception {
        SwapOffersPane pane = offers(true, ADDRESS, null, null, false, new ArrayList<>());
        shoot(pane, 845, 1500, "offers-sorted-845");
        java.util.function.Supplier<String> firstMaker = () -> {
            try {
                return onFx(() -> ((javafx.scene.control.Label)pane.lookupAll(".offer-maker").iterator().next()).getText());
            } catch(Exception e) {
                throw new IllegalStateException(e);
            }
        };
        String byXmr = firstMaker.get();
        assertEquals("10.0.0.1:9939", byXmr, "most XMR first");
        onFx(() -> {
            for(Node node : pane.lookupAll(".offer-seg-btn")) {
                if(node instanceof javafx.scene.control.ToggleButton b && b.getText().equals("Lowest deduction")) {
                    b.fire();
                }
            }
            return null;
        });
        assertEquals("10.0.0.2:9939", firstMaker.get(), "no deduction first, then the most XMR");

        assertEquals(0, onFx(() -> pane.lookupAll(".offer-details").size()));
        onFx(() -> {
            for(Node node : pane.lookupAll(".offer-caret")) {
                ((Button)node).fire();
                break;
            }
            return null;
        });
        assertEquals(1, onFx(() -> pane.lookupAll(".offer-details").size()), "protocol version and limits are one click away");
        shoot(pane, 845, 1500, "offers-details-845");
    }

    @Test
    void whileASwapIsBeingSetUpOtherOffersAreHeld() throws Exception {
        String maker = makers().get(1).peerId();
        SwapOffersPane pane = offers(true, ADDRESS, maker, "Setting up the swap with the maker. This takes a few minutes, and nothing is locked yet.", false, new ArrayList<>());
        shoot(pane, 845, 1500, "offers-requesting-845");
        List<Button> buttons = onFx(() -> reviewButtons(pane));
        assertEquals(8, buttons.size(), "the maker being contacted shows a spinner in place of its button");
        assertTrue(buttons.stream().allMatch(Button::isDisable));
    }

    @Test
    void theTableFoldsWhenThereIsNotRoomForAllColumns() throws Exception {
        SwapOffersPane pane = offers(true, ADDRESS, null, null, false, new ArrayList<>());
        shoot(pane, 640, 1700, "offers-narrow-640");
        double minWidth = onFx(() -> pane.minWidth(-1));
        if(minWidth > 640) {
            //Say which part is too wide, so the fix goes where the problem is
            StringBuilder widest = new StringBuilder();
            onFx(() -> {
                describeWide(pane, "", widest);
                return null;
            });
            fail("folded offers need " + minWidth + "px; wide parts:\n" + widest);
        }
    }

    private static void describeWide(Node node, String indent, StringBuilder out) {
        double min = node.minWidth(-1);
        if(min > 560) {
            out.append(indent).append(node.getClass().getSimpleName()).append(' ').append(node.getStyleClass()).append(" min=").append(Math.round(min)).append('\n');
            if(node instanceof javafx.scene.Parent parent) {
                for(Node child : parent.getChildrenUnmodifiable()) {
                    describeWide(child, indent + "  ", out);
                }
            }
        }
    }
}
