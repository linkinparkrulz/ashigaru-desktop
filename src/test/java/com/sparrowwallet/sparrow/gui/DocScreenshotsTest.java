package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AshigaruTerminal;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.testutil.SampleWallets;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.stage.Window;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Takes the pictures in docs/images by running the real application (its real main window, menus and dialogs) under a virtual display, with a
 * throw-away home folder and a made-up wallet, and capturing the screen. Run it with a 1280x800 display:
 *
 * <pre>DOC_SCREENSHOTS=/tmp/shots xvfb-run -a -s "-screen 0 1280x800x24" ./gradlew :test --tests '*DocScreenshotsTest*'</pre>
 *
 * It only runs when DOC_SCREENSHOTS names a folder, so the normal test run does not start the application.
 */
@EnabledIfEnvironmentVariable(named = "DOC_SCREENSHOTS", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocScreenshotsTest {
    private static final Path out = Path.of(System.getenv("DOC_SCREENSHOTS"));

    /** Runs on the JavaFX thread and waits for the result */
    private static <T> T fx(Supplier<T> work) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
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

    private static void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /** Saves the whole virtual screen */
    private void shot(String name) throws Exception {
        shot(name, () -> {});
    }

    /** {@code lastMinute} runs just before the picture, for what the screen's own timers would otherwise undo */
    private void shot(String name, ThrowingRunnable lastMinute) throws Exception {
        com.sparrowwallet.sparrow.EventManager.get().post(new com.sparrowwallet.sparrow.event.ConnectionEvent(List.of("Sample server", "1.4"), "", 903_950, null,
                java.util.Map.of(), java.util.Set.of(), 1.0));
        pause(900);
        //The sample server and coordinator do not exist, so what the real start-up reported to the top bar is replaced by a healthy state
        com.sparrowwallet.sparrow.whirlpool.CoordinatorStatus.get().startupSucceeded();
        fx(() -> {
            try {
                java.lang.reflect.Method refresh = AshigaruMainController.class.getDeclaredMethod("refreshLights");
                refresh.setAccessible(true);
                refresh.invoke(AshigaruGui.get().getMainController());
            } catch(ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        pause(300);
        lastMinute.run();
        if(swapsOn) {
            fx(() -> {
                try {
                    java.lang.reflect.Method light = AshigaruMainController.class.getDeclaredMethod("setLight", javafx.scene.control.Label.class, String.class, String.class, String.class);
                    light.setAccessible(true);
                    light.invoke(null, byId("swapLightLabel"), "Swaps \u00b7 on", null, "connected");
                } catch(ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                return null;
            });
        }
        pause(150);
        Path file = out.resolve(name + ".png");
        Process process = new ProcessBuilder("import", "-window", "root", file.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        assertTrue(Files.size(file) > 5_000, name);
    }

    private static File walletFile;
    /** True while the Swaps screens are drawn: the engine is not really running here, so the top bar light is set to what a running engine shows */
    private static boolean swapsOn;

    private static File prepareHome(Path home) throws Exception {
        System.setProperty(AshigaruTerminal.APP_HOME_PROPERTY, home.toString());
        Files.createDirectories(home.resolve("wallets"));

        Wallet wallet = SampleWallets.whirlpoolWallet("Sample");
        Date now = new Date();
        SampleWallets.fund(wallet, 0, 5_000_000, 903_100, new Date(now.getTime() - 9 * 86_400_000L), "Exchange withdrawal");
        SampleWallets.fund(wallet, 1, 1_250_000, 903_950, new Date(now.getTime() - 2 * 86_400_000L), "Salary");
        Wallet postmix = wallet.getChildWallet(StandardAccount.WHIRLPOOL_POSTMIX);
        SampleWallets.fund(postmix, 0, 1_000_000, 903_500, new Date(now.getTime() - 5 * 86_400_000L), null);
        SampleWallets.fund(postmix, 1, 1_000_000, 903_500, new Date(now.getTime() - 5 * 86_400_000L), null);
        SampleWallets.fund(postmix, 2, 5_000_000, 903_500, new Date(now.getTime() - 5 * 86_400_000L), null);

        //Saved the way the app saves a new wallet that has no password
        Storage storage = new Storage(Storage.getWalletFile(wallet.getName()));
        storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
        storage.saveWallet(wallet);
        for(Wallet child : wallet.getChildWallets()) {
            storage.saveWallet(child);
        }
        return storage.getWalletFile();
    }


    /** The node with this fx:id in any open window, newest window first */
    private static Node byId(String id) {
        List<Window> windows = new java.util.ArrayList<>(Window.getWindows());
        java.util.Collections.reverse(windows);
        for(Window window : windows) {
            if(window.isShowing() && window.getScene() != null) {
                Node node = window.getScene().getRoot().lookup("#" + id);
                if(node != null) {
                    return node;
                }
            }
        }
        return null;
    }

    private static <T extends Node> T need(String id) {
        @SuppressWarnings("unchecked") T node = (T)byId(id);
        assertNotNull(node, "no node with id " + id);
        return node;
    }

    private static void fire(String id) throws Exception {
        fx(() -> {
            Node node = need(id);
            if(node instanceof ButtonBase button) {
                button.fire();
            } else {
                throw new AssertionError(id + " is not a button");
            }
            return null;
        });
    }

    /** For buttons that open a dialog with showAndWait, which does not return until the dialog is closed */
    private static void fireAndLeave(String id) throws Exception {
        Platform.runLater(() -> {
            Node node = byId(id);
            if(node instanceof ButtonBase button) {
                button.fire();
            }
        });
        pause(1500);
    }

    /** The newest showing window other than the main one, or the main window when there is none */
    private static Window topWindow() {
        List<Window> windows = new java.util.ArrayList<>(Window.getWindows());
        java.util.Collections.reverse(windows);
        Stage main = AshigaruGui.get().getMainStage();
        return windows.stream().filter(w -> w != main && w.isShowing() && w.getScene() != null).findFirst().orElse(main);
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        if(node instanceof Parent parent) {
            for(Node child : parent.getChildrenUnmodifiable()) {
                collect(child, into);
            }
        }
    }

    /** Presses the button with this text (case does not matter) in the newest dialog, without waiting for the dialog to close */
    private static void fireText(String text) throws Exception {
        Platform.runLater(() -> {
            List<Node> all = new java.util.ArrayList<>();
            collect(topWindow().getScene().getRoot(), all);
            ButtonBase button = all.stream().filter(n -> n instanceof ButtonBase b && text.equalsIgnoreCase(b.getText().trim())).map(n -> (ButtonBase)n).findFirst()
                    .orElseThrow(() -> new AssertionError("no button " + text));
            button.fire();
        });
        pause(1500);
    }

    private static void typeFirstInput(String text) throws Exception {
        fx(() -> {
            List<Node> all = new java.util.ArrayList<>();
            collect(topWindow().getScene().getRoot(), all);
            TextInputControl field = all.stream().filter(n -> n instanceof TextInputControl && n.isVisible()).map(n -> (TextInputControl)n).findFirst()
                    .orElseThrow(() -> new AssertionError("no input"));
            field.setText(text);
            return null;
        });
    }

    /** Clicks a card-like node by the text of a label inside it */
    private static void clickLabel(String text) throws Exception {
        Platform.runLater(() -> {
            List<Node> all = new java.util.ArrayList<>();
            collect(topWindow().getScene().getRoot(), all);
            Node label = all.stream().filter(n -> n instanceof javafx.scene.control.Labeled l && l.getText() != null && text.equalsIgnoreCase(l.getText().trim())).findFirst()
                    .orElseThrow(() -> new AssertionError("no label " + text));
            for(Node up = label; up != null; up = up.getParent()) {
                if(up instanceof ButtonBase button) {
                    button.fire();
                    return;
                }
            }
            javafx.event.Event.fireEvent(label, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, false, null));
        });
        pause(1500);
    }

    /** Presses the first button (or card) whose text or label contains one of these, in order of preference, else the first that is not Cancel */
    private static boolean advance(String... preferred) throws Exception {
        boolean[] done = {false};
        fx(() -> {
            Window window = topWindow();
            if(window == AshigaruGui.get().getMainStage()) {
                return null;
            }
            List<Node> all = new java.util.ArrayList<>();
            collect(window.getScene().getRoot(), all);
            List<ButtonBase> buttons = all.stream().filter(n -> n instanceof ButtonBase && n.isVisible() && !n.isDisabled()).map(n -> (ButtonBase)n).toList();
            for(String want : preferred) {
                for(ButtonBase button : buttons) {
                    List<Node> inside = new java.util.ArrayList<>();
                    collect(button, inside);
                    boolean match = inside.stream().anyMatch(n -> n instanceof javafx.scene.control.Labeled l && l.getText() != null && l.getText().toLowerCase().contains(want.toLowerCase()));
                    if(match) {
                        button.fire();
                        done[0] = true;
                        return null;
                    }
                }
            }
            for(ButtonBase button : buttons) {
                if(!"Cancel".equalsIgnoreCase(button.getText())) {
                    button.fire();
                    done[0] = true;
                    return null;
                }
            }
            return null;
        });
        pause(1800);
        return done[0];
    }

    /** Types one dice roll, a digit per box, into the visible boxes of the newest dialog */
    private static void roll(String digits) throws Exception {
        fx(() -> {
            List<Node> all = new java.util.ArrayList<>();
            collect(topWindow().getScene().getRoot(), all);
            List<TextInputControl> boxes = all.stream().filter(n -> n instanceof TextInputControl && n.isVisible()).map(n -> (TextInputControl)n).toList();
            assertEquals(digits.length(), boxes.size(), "dice boxes");
            for(int i = 0; i < boxes.size(); i++) {
                boxes.get(i).setText(digits.substring(i, i + 1));
            }
            return null;
        });
        pause(400);
    }

    /** Lists the window's title, labels and buttons, to see what a dialog offers */
    private static void describe(String where) throws Exception {
        String text = fx(() -> {
            Window window = topWindow();
            List<Node> all = new java.util.ArrayList<>();
            collect(window.getScene().getRoot(), all);
            StringBuilder b = new StringBuilder("== " + where + " (" + window.getClass().getSimpleName() + ")\n");
            for(Node n : all) {
                if(n.isVisible() && n instanceof javafx.scene.control.Labeled l && l.getText() != null && !l.getText().isBlank()) {
                    b.append(n instanceof ButtonBase ? "  [button] " : "  [label] ").append(l.getText().replace('\n', ' ')).append(n.getId() != null ? "  #" + n.getId() : "").append('\n');
                } else if(n.isVisible() && n instanceof TextInputControl t) {
                    b.append("  [input] ").append(t.getPromptText()).append("  #").append(n.getId()).append('\n');
                }
            }
            return b.toString();
        });
        System.out.println("DESCRIBE " + text);
    }

    private static void type(String id, String text) throws Exception {
        fx(() -> {
            TextInputControl field = need(id);
            field.setText(text);
            return null;
        });
    }

    /** Closes every window but the main one */
    private static void closeDialogs() throws Exception {
        fx(() -> {
            Stage main = AshigaruGui.get().getMainStage();
            for(Window window : new java.util.ArrayList<>(Window.getWindows())) {
                if(window != main && window.isShowing()) {
                    window.hide();
                }
            }
            return null;
        });
    }

    private final java.util.List<String> failures = new java.util.ArrayList<>();

    /** One picture; a failure is noted and the rest are still taken */
    private void step(String name, ThrowingRunnable work) {
        step(name, work, () -> {});
    }

    private void step(String name, ThrowingRunnable work, ThrowingRunnable lastMinute) {
        try {
            work.run();
            shot(name, lastMinute);
        } catch(Throwable t) {
            failures.add(name + ": " + t);
            t.printStackTrace();
            try {
                closeDialogs();
            } catch(Exception ignored) {
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @BeforeAll
    static void startTheApp() throws Exception {
        System.setProperty("prism.order", "sw");
        Path home = Files.createTempDirectory("ashigaru-doc-home");
        walletFile = prepareHome(home);
        Files.createDirectories(out);

        Config config = Config.get();
        config.setFirstRun(false);
        config.setTourShown(true);
        config.setUpdateCheckConsent(false);
        config.setServerType(com.sparrowwallet.sparrow.net.ServerType.ELECTRUM_SERVER);
        config.setElectrumServer(new com.sparrowwallet.sparrow.io.Server("tcp://sample-server.example.org:50001", "Sample server (mainnet)"));

        //The icon font is registered by the module system in the real app; here the toolkit is started first so it can be registered by hand
        Platform.startup(() -> {});
        fx(() -> {
            org.controlsfx.glyphfont.GlyphFontRegistry.register(new com.sparrowwallet.sparrow.glyphfont.FontAwesome5());
            return null;
        });

        Thread app = new Thread(() -> AshigaruTerminal.main(new String[] {"--dir", home.toString(), "-n", "mainnet"}), "app");
        app.setDaemon(true);
        app.start();

        //Touching JavaFX classes on this thread before the toolkit runs would break it, so wait for it with runLater first
        long deadline = System.currentTimeMillis() + 90_000;
        boolean up = false;
        while(!up && System.currentTimeMillis() < deadline) {
            try {
                Platform.runLater(() -> {});
                up = true;
            } catch(IllegalStateException notYet) {
                pause(500);
            }
        }
        assertTrue(up, "JavaFX did not start");
        Stage stage = null;
        while(System.currentTimeMillis() < deadline) {
            stage = fx(() -> {
                AshigaruGui gui = AshigaruGui.get();
                return gui == null || gui.getMainStage() == null || !gui.getMainStage().isShowing() ? null : gui.getMainStage();
            });
            if(stage != null) {
                break;
            }
            pause(500);
        }
        assertNotNull(stage, "the main window did not appear");
        pause(3000);

    }

    /** Before a wallet is open: the first things a new user sees */
    @Test
    @Order(1)
    void firstRun() throws Exception {
        step("welcome", () -> {});

        step("tour-1", () -> {
            fire("toolsBtn");
            pause(800);
            fire("tourButton");
            pause(500);
            fireAndLeave("startTourBtn");
        });
        step("tour-3", () -> {
            fireText("Next");
            fireText("Next");
        });
        closeDialogs();

        step("create-wallet-name", () -> {
            fireAndLeave("createWalletBtn");
        });
        typeFirstInput("Savings");
        fireText("Continue");
        step("create-wallet-type", () -> {});
        advance("hot wallet");
        step("create-wallet-length", () -> {});
        advance("24 words");
        advance("generate new");
        advance("ok");
        step("create-wallet-passphrase", () -> {});
        advance("yes, i have dice");
        String[] rolls = {"31425", "26134", "54213", "12645", "43561", "65324"};
        for(int word = 1; word <= rolls.length; word++) {
            roll(rolls[word - 1]);
            if(word == 3) {
                shot("create-wallet-dice");
            }
            fireText("Add word");
        }
        step("create-wallet-diceware", () -> {});
        closeDialogs();

        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** A made-up wallet with coins, shown the way a connected app shows it */
    @Test
    @Order(2)
    void withAWallet() throws Exception {
        closeDialogs();
        fx(() -> {
            AshigaruGui.get().getMainController().setPendingSelectFile(walletFile);
            AshigaruGui.get().getMainController().openWalletFile(walletFile);
            return null;
        });
        pause(8000);

        step("main", () -> fire("depositBtn"));
        step("utxos-selected", () -> fire("selectAllUtxosBtn"));
        fire("selectAllUtxosBtn");
        step("receive", () -> fireAndLeave("receiveBtn"));
        closeDialogs();
        step("postmix", () -> fire("postmixBtn"));
        step("whirlpool", () -> fire("whirlpoolViewBtn"));
        fire("depositBtn");
        step("wallet-menu", () -> fx(() -> {
            ((MenuButton)need("walletMenuBtn")).show();
            return null;
        }));
        fx(() -> {
            ((MenuButton)need("walletMenuBtn")).hide();
            return null;
        });
        step("pair-sentinel", () -> {
            Platform.runLater(() -> {
                MenuButton menu = need("walletMenuBtn");
                menu.getItems().stream().filter(item -> "sentinelPairBtn".equals(item.getId())).findFirst().orElseThrow().fire();
            });
            pause(2500);
        });
        closeDialogs();

        step("tools-sign", () -> {
            fire("toolsBtn");
            pause(800);
            fire("bip47SignerButton");
            pause(600);
            type("messageArea", "I control this payment code.");
            fire("signBtn");
        });
        step("tools-verifier", () -> {
            String block = fx(() -> ((TextInputControl)need("blockArea")).getText());
            String code = fx(() -> ((TextInputControl)need("paymentCodeField")).getText());
            fire("bip47VerifierButton");
            pause(600);
            type("paymentCodeField", code);
            type("signedBlockArea", block);
            fire("verifyBtn");
        });
        for(String[] page : new String[][] {{"generalPrefsBtn", "settings-general"}, {"updatePrefsBtn", "settings-update"},
                {"swapsPrefsBtn", "settings-swaps"}}) {
            step(page[1], () -> {
                if(page[0].equals("generalPrefsBtn")) {
                    fire("settingsBtn");
                    pause(800);
                }
                fire(page[0]);
            });
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static final String ADDRESS = "4q2Dkq6ozw8yQ3t9g3QygMp3xMYfnw6f2mr16unD8uzXkyQBS7As25AhCje1UdemiPgTYu21LugRY98q6ZvjEKvdgq2mtVY";
    private static final String SWAP_ID = "3f9a21c0-7b64-4d3e-9a10-0c2b7d1e4f55";

    /** Shows or hides a node the screen's own logic would normally decide, leaving a bound property alone */
    private static void display(Node node, boolean show) {
        if(!node.visibleProperty().isBound()) {
            node.setVisible(show);
        }
        if(!node.managedProperty().isBound()) {
            node.setManaged(show);
        }
    }

    private static <T extends Node> T find(Class<T> type) {
        List<Node> all = new java.util.ArrayList<>();
        collect(AshigaruGui.get().getMainStage().getScene().getRoot(), all);
        return all.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow(() -> new AssertionError("no " + type.getSimpleName()));
    }

    /** Nine made-up makers, the way the offers list shows them */
    private static List<com.sparrowwallet.sparrow.swap.SwapEngine.Quote> makers() {
        double[] rates = {297.35, 296.90, 296.40, 296.10, 295.60, 295.20};
        double[] deposits = {0.02, 0, 0, 0.05, 0, 0.02};
        List<com.sparrowwallet.sparrow.swap.SwapEngine.Quote> quotes = new java.util.ArrayList<>();
        for(int i = 0; i < rates.length; i++) {
            String policy = deposits[i] == 0 ? "{\"type\":\"FullRefund\"}"
                    : "{\"type\":\"PartialRefund\",\"content\":{\"anti_spam_deposit_ratio\":" + deposits[i] + "}}";
            quotes.add(new com.sparrowwallet.sparrow.swap.SwapEngine.Quote("12D3KooWMaker" + i + "abcdefghijklmnopqrstuvwxyz", "/ip4/10.0.0." + (i + 1) + "/tcp/9939", "5.0.4",
                    (long)(1e8 / rates[i]), 100_000, 100_000_000, policy));
        }
        return quotes;
    }

    private static void setSwapStatus() {
        try {
            java.lang.reflect.Field status = com.sparrowwallet.sparrow.swap.SwapService.class.getDeclaredField("status");
            status.setAccessible(true);
            ((javafx.beans.property.ReadOnlyStringWrapper)status.get(com.sparrowwallet.sparrow.swap.SwapService.get()))
                    .set("On. Using 2 public Monero nodes, which must agree before a swap releases your BTC.");
        } catch(ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The Swaps tool, with a Badbank coin chosen and the maker list filled in, on the real screen */
    @Test
    @Order(3)
    void swaps() throws Exception {
        Config.get().setSwapsEnabled(true);
        java.lang.reflect.Field available = com.sparrowwallet.sparrow.swap.SwapService.class.getDeclaredField("available");
        available.setAccessible(true);
        available.set(com.sparrowwallet.sparrow.swap.SwapService.get(), Boolean.TRUE);
        swapsOn = true;
        fire("toolsBtn");
        pause(800);
        fire("swapsButton");
        pause(1500);
        step("swap-offers", () -> {}, () -> fx(() -> {
            setSwapStatus();
            SwapOffersPane offers = find(SwapOffersPane.class);
            offers.show(new SwapOffersPane.State(List.of(), true, 5_000_000, "Sample", 4_998_760, 1_240, null, makers(),
                    "Not shown: 3 offering more XMR than the market pays (makers that undercut the market refuse swaps), 1 running outdated software.",
                    "Market 1 BTC \u2248 298.80 XMR (Kraken, over Tor). Not an offer you can take.", 1 / 298.80, true, null, quote -> null, null, null,
                    java.time.Instant.now()));
            for(Node node : offers.lookupAll(".text-field")) {
                if(node instanceof javafx.scene.control.TextField field) {
                    field.setText(ADDRESS);
                    break;
                }
            }
            return null;
        }));

        step("swap-progress", () -> {}, () -> fx(() -> {
            setSwapStatus();
            SwapDashboardPane dashboard = find(SwapDashboardPane.class);
            com.sparrowwallet.sparrow.swap.SwapDetails terms = new com.sparrowwallet.sparrow.swap.SwapDetails(4_998_760, 1_240, 14_816_324_640_000L, 99_975, false, ADDRESS,
                    "12D3KooW\u2026b3f9");
            java.time.Instant since = java.time.Instant.now().minusSeconds(29 * 60);
            com.sparrowwallet.sparrow.swap.SwapProgress.Status status = new com.sparrowwallet.sparrow.swap.SwapProgress.Status(
                    com.sparrowwallet.sparrow.swap.SwapProgress.Step.MAKER_LOCK, "4 confirmations \u00b7 0ad8c9aa\u2026eff81c7d", since, false);
            dashboard.show(SWAP_ID, com.sparrowwallet.sparrow.swap.SwapDashboard.of(new com.sparrowwallet.sparrow.swap.SwapDashboard.Input(SWAP_ID, status,
                    new com.sparrowwallet.sparrow.swap.SwapTimelock(com.sparrowwallet.sparrow.swap.SwapTimelock.Phase.BEFORE_CANCEL, 26, java.time.Instant.now()), true, terms, false,
                    java.time.Instant.now())), new SwapDashboardPane.Connections(List.of("Tor", "Bitcoin server", "Swap engine"), List.of()));
            display(dashboard.getParent(), true);
            display(byId("offersView"), false);
            return null;
        }));

        step("swap-review", () -> {}, () -> fx(() -> {
            SwapReviewPane review = find(SwapReviewPane.class);
            review.show(com.sparrowwallet.sparrow.swap.SwapReview.commit(5_000_000, 4_998_760, 1_240, 14_816_324_640_000L, 99_975, false, ADDRESS, "12D3KooW\u2026b3f9",
                    java.time.Instant.now().plusSeconds(81), new com.sparrowwallet.sparrow.swap.SwapReview.Decision() {
                        @Override
                        public void confirm() {
                        }

                        @Override
                        public void decline() {
                        }
                    }));
            for(Node sibling : review.getParent().getChildrenUnmodifiable()) {
                sibling.setVisible(sibling == review);
            }
            return null;
        }));

        step("swap-price-warning", () -> {
            Platform.runLater(() -> PriceWarningDialog.show(AshigaruGui.get().getMainStage(), new com.sparrowwallet.sparrow.swap.SwapTermsCheck.Result(
                    com.sparrowwallet.sparrow.swap.SwapTermsCheck.Verdict.WARN, com.sparrowwallet.sparrow.swap.SwapTermsCheck.Reason.LESS_XMR, 3.4,
                    14_816_324_640_000L, 14_312_000_000_000L), 60));
            pause(2000);
        });
        closeDialogs();
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
