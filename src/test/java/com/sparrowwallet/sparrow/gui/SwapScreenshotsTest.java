package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.swap.SwapReview;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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
    static final String ADDRESS = "4qRsDBoPDju5bT9fVyMjsF7VfVRbqMKuDbwoZHP33BdRRFZP3mjo9jP3f53MyMqbFRRVohPyPDFRD5ZBoQXfo7FPHRwym";

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
}
