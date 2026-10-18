package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.*;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class AshigaruGui extends Application {
    private static final Logger log = LoggerFactory.getLogger(AshigaruGui.class);
    private static AshigaruGui instance;

    private Stage mainStage;
    private AshigaruMainController mainController;

    // Mirrors SparrowTerminal.walletData — keyed by walletId, master wallets only
    private final Map<String, WalletForm> walletForms = new LinkedHashMap<>();

    // Dummy window used for event list construction (same pattern as SparrowTerminal)
    private static final Window DEFAULT_WINDOW = new Window() {};

    // -------------------------------------------------------------------------
    // JavaFX Application lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void init() throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("Exception in thread \"" + t.getName() + "\"", e));
        AppServices.initialize(this, new AshigaruInteractionServices());
        instance = this;
        EventManager.get().register(this);
        // Start each session with no discovered Dojo listings (covers an unclean prior shutdown);
        // discovery re-populates them once connected.
        Config.get().clearDiscoveredServers();
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.mainStage = stage;

        Font.loadFont(AppServices.class.getResourceAsStream("/font/RobotoMono-Regular.ttf"), 13);
        //Registers the family name "Nikkyou Sans" for the splash title. Loaded here rather than
        //lazily because the splash is built further down this same method and would otherwise
        //fall back to the default face without any error.
        Font.loadFont(AppServices.class.getResourceAsStream("/font/NikkyouSans.ttf"), 13);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("ashigaru-main.fxml"));
        Parent root = loader.load();
        mainController = loader.getController();

        //Size to the usable screen area (excluding panels/docks). Window managers pass the minimum size
        //on as a hint, and one larger than the usable area greys out maximize, refuses tiling and lets
        //the panel cover the bottom of the window, so neither the minimum nor the initial size may
        //exceed it. The sidebar scrolls instead of setting the minimum height.
        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root, Math.min(1100, visualBounds.getWidth()), Math.min(800, visualBounds.getHeight()));
        AppServices.addAshigaruStylesheets(scene.getStylesheets());

        stage.setTitle("Ashigaru Desktop " + AshigaruTerminal.APP_VERSION);
        stage.setMinWidth(Math.min(720, visualBounds.getWidth()));
        stage.setMinHeight(Math.min(520, visualBounds.getHeight()));
        stage.setScene(scene);

        // Set app icon (window + macOS Dock). Use the padded variant: the full-bleed circle logo
        // touches the canvas edges, so the OS renders it larger than neighbouring dock icons (which
        // follow the ~80% content / transparent-margin convention). Dock_ has that safe-area margin.
        try {
            Image icon = new Image(getClass().getResourceAsStream("/image/Ashigaru_Terminal_Logo_Dock.png"));
            stage.getIcons().add(icon);
            try {
                if (java.awt.Taskbar.isTaskbarSupported()) {
                    java.awt.Taskbar taskbar = java.awt.Taskbar.getTaskbar();
                    if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                        java.awt.image.BufferedImage awtIcon = javafx.embed.swing.SwingFXUtils.fromFXImage(icon, null);
                        taskbar.setIconImage(awtIcon);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not set taskbar icon", e);
            }
        } catch(Exception e) {
            log.warn("Could not load application icon", e);
        }

        // Show splash and hold main window until connected (or timeout)
        Stage splashStage = showSplash();
        AtomicBoolean shown = new AtomicBoolean(false);

        Runnable revealMain = () -> {
            if (shown.compareAndSet(false, true)) {
                Platform.runLater(() -> showMainWindow(splashStage, stage));
            }
        };

        AppServices.get().start();

        if (Config.get().getMode() != Mode.ONLINE) {
            setSplashStatus(splashStage, "Offline mode enabled. Opening workspace...");
            skipRemainingSplashSteps(splashStage);
            PauseTransition offlineDelay = new PauseTransition(Duration.seconds(3));
            offlineDelay.setOnFinished(e -> revealMain.run());
            offlineDelay.play();
        } else {
            setSplashStatus(splashStage, "Starting network services...");
            PauseTransition timeout = new PauseTransition(Duration.seconds(30));
            timeout.setOnFinished(e -> {
                setSplashStatus(splashStage, "Opening workspace while network connects...");
                revealMain.run();
            });

            AppServices.onlineProperty().addListener((obs, wasOnline, isOnline) -> {
                if (isOnline) {
                    setSplashStatus(splashStage, "Connected. Opening workspace...");
                    timeout.stop();
                    revealMain.run();
                }
            });

            timeout.play();
        }
    }

    private Stage showSplash() {
        try {
            FXMLLoader splashLoader = new FXMLLoader(getClass().getResource("ashigaru-splash.fxml"));
            Parent splashRoot = splashLoader.load();
            AshigaruSplashController splashCtrl = splashLoader.getController();

            Stage splashStage = new Stage();
            splashStage.initStyle(StageStyle.UNDECORATED);
            splashStage.setTitle("Ashigaru Desktop " + AshigaruTerminal.APP_VERSION);
            Scene splashScene = new Scene(splashRoot, 600, 400);
            AppServices.addAshigaruStylesheets(splashScene.getStylesheets());
            splashStage.setScene(splashScene);
            splashStage.setUserData(splashCtrl);
            splashStage.show();
            return splashStage;
        } catch (Exception e) {
            log.warn("Could not load splash screen", e);
            return null;
        }
    }

    private void setSplashStatus(Stage splashStage, String message) {
        if (splashStage == null) return;

        Object userData = splashStage.getUserData();
        if (userData instanceof AshigaruSplashController ctrl) {
            Platform.runLater(() -> ctrl.setStatus(message));
        }
    }

    private void skipRemainingSplashSteps(Stage splashStage) {
        if (splashStage == null) return;

        if (splashStage.getUserData() instanceof AshigaruSplashController ctrl) {
            ctrl.skipRemaining();
        }
    }

    private void showMainWindow(Stage splashStage, Stage mainStage) {
        if (splashStage != null) {
            Object userData = splashStage.getUserData();
            if (userData instanceof AshigaruSplashController ctrl) ctrl.stop();
            splashStage.close();
        }

        mainStage.show();
        //Must follow show(): JavaFX only centres the stage, and only knows its decorated size, once
        //it is on screen. Before that getX/getY/getHeight have nothing useful to clamp.
        AppServices.clampToScreen(mainStage);

        // Collect wallet files: recent list (preserves order) + full wallets-dir scan
        // so wallets appear in the dropdown even if never explicitly "opened" before.
        LinkedHashSet<File> toLoad = new LinkedHashSet<>();

        List<File> recentWalletFiles = Config.get().getRecentWalletFiles();
        if (recentWalletFiles != null) {
            recentWalletFiles.stream().filter(File::exists).forEach(toLoad::add);
        }

        File walletsDir = Storage.getWalletsDir();
        File[] dirFiles = walletsDir.listFiles();
        if (dirFiles != null) {
            Arrays.stream(dirFiles)
                    .filter(f -> f.isFile() && isWalletFile(f))
                    .sorted(Comparator.comparing(File::getName))
                    .forEach(toLoad::add);
        }

        for (File walletFile : toLoad) {
            mainController.addRecentWalletFile(walletFile);
        }

        // First-run guided tour, once the main window is visible and wallets are loaded.
        Platform.runLater(mainController::maybeShowFirstRunTour);
    }

    private static boolean isWalletFile(File file) {
        String name = file.getName();
        return name.endsWith(".mv.db") || name.endsWith(".json");
    }

    @Override
    public void stop() throws Exception {
        AppServices.get().stop();
        // Discovered Dojo listings are ephemeral: drop them on shutdown so they don't persist on disk
        // while closed and are re-discovered fresh next launch.
        Config.get().clearDiscoveredServers();
        Config.get().setAppWidth(mainStage.getWidth());
        Config.get().setAppHeight(mainStage.getHeight());
        mainStage.close();
        AshigaruTerminal.Instance inst = AshigaruTerminal.getSparrowInstance();
        if (inst != null) inst.freeLock();
    }

    // -------------------------------------------------------------------------
    // Static singleton accessor
    // -------------------------------------------------------------------------

    public static AshigaruGui get() {
        return instance;
    }

    public Stage getMainStage() {
        return mainStage;
    }

    public AshigaruMainController getMainController() {
        return mainController;
    }

    public Map<String, WalletForm> getWalletForms() {
        return walletForms;
    }

    // -------------------------------------------------------------------------
    // Wallet registration — mirrors SparrowTerminal.addWallet()
    // -------------------------------------------------------------------------

    public static void addWallet(Storage storage, Wallet wallet) {
        if (wallet.isNested() || wallet.isWhirlpoolChildWallet()) {
            // BIP47 payment-code wallets and Whirlpool child wallets (Premix/Postmix/Badbank)
            // all go into the master wallet's nested forms list
            String masterId = storage.getWalletId(wallet.getMasterWallet());
            WalletForm masterForm = instance.walletForms.get(masterId);
            if (masterForm != null) {
                // Avoid duplicates when called multiple times
                boolean alreadyAdded = masterForm.getNestedWalletForms().stream()
                        .anyMatch(f -> f.getWallet().equals(wallet));
                if (!alreadyAdded) {
                    WalletForm childForm = new WalletForm(storage, wallet);
                    EventManager.get().register(childForm);
                    masterForm.getNestedWalletForms().add(childForm);
                    if (wallet.isValid()) {
                        Platform.runLater(() -> childForm.refreshHistory(AppServices.getCurrentBlockHeight()));
                    }
                }

                publishOpenWalletsEvent();
            }
        } else {
            EventManager.get().post(new WalletOpeningEvent(storage, wallet));

            WalletForm walletForm = new WalletForm(storage, wallet);
            EventManager.get().register(walletForm);
            instance.walletForms.put(walletForm.getWalletId(), walletForm);

            publishOpenWalletsEvent();

            if (wallet.isValid()) {
                Platform.runLater(() -> walletForm.refreshHistory(AppServices.getCurrentBlockHeight()));
            }

            // Track recent wallet files
            Set<File> walletFiles = new LinkedHashSet<>();
            walletFiles.add(storage.getWalletFile());
            if (Config.get().getRecentWalletFiles() != null) {
                walletFiles.addAll(Config.get().getRecentWalletFiles().stream()
                        .limit(9).collect(Collectors.toList()));
            }
            Config.get().setRecentWalletFiles(
                    Config.get().isLoadRecentWallets() ? new ArrayList<>(walletFiles) : Collections.emptyList());
        }

        EventManager.get().post(new WalletOpenedEvent(storage, wallet));
    }

    public static void removeWallet(String walletId) {
        WalletForm form = instance.walletForms.remove(walletId);
        if (form != null) {
            EventManager.get().unregister(form);
            for (WalletForm nested : form.getNestedWalletForms()) {
                EventManager.get().unregister(nested);
            }
            // Mirror the cleanup WalletForm.walletTabsClosed() normally performs
            if (form.getWallet().isMasterWallet()) {
                form.getStorage().close();
            }
            if (form.getWallet().isValid()) {
                AppServices.clearTransactionHistoryCache(form.getWallet());
            }
        }

        // Sync AppServices.walletWindows — without this, getOpenWallets() returns a stale
        // entry for the deleted wallet, breaking all subsequent commands that call it.
        publishOpenWalletsEvent();
    }

    private static void publishOpenWalletsEvent() {
        List<WalletTabData> tabDataList = instance.walletForms.values().stream()
                .flatMap(form -> {
                    List<WalletForm> forms = new ArrayList<>();
                    forms.add(form);
                    forms.addAll(form.getNestedWalletForms());
                    return forms.stream();
                })
                .map(form -> new WalletTabData(TabData.TabType.WALLET, form))
                .collect(Collectors.toList());
        EventManager.get().post(new OpenWalletsEvent(DEFAULT_WINDOW, tabDataList));
    }

}
