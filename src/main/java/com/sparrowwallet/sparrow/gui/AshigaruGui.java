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
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
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
        AppServices.initialize(this);
        instance = this;
        EventManager.get().register(this);
    }

    @Override
    public void start(Stage stage) throws Exception {
        this.mainStage = stage;

        Font.loadFont(AppServices.class.getResourceAsStream("/font/RobotoMono-Regular.ttf"), 13);

        FXMLLoader loader = new FXMLLoader(getClass().getResource("ashigaru-main.fxml"));
        Parent root = loader.load();
        mainController = loader.getController();

        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(getClass().getResource("ashigaru.css").toExternalForm());

        stage.setTitle("Ashigaru " + AshigaruTerminal.APP_VERSION);
        stage.setMinWidth(800);
        stage.setMinHeight(540);
        stage.setScene(scene);

        // Set app icon
        try {
            Image icon = new Image(getClass().getResourceAsStream("/image/Ashigaru_Terminal_Logo_Circle.png"));
            stage.getIcons().add(icon);
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
            PauseTransition offlineDelay = new PauseTransition(Duration.seconds(3));
            offlineDelay.setOnFinished(e -> revealMain.run());
            offlineDelay.play();
        } else {
            PauseTransition timeout = new PauseTransition(Duration.seconds(30));
            timeout.setOnFinished(e -> revealMain.run());

            AppServices.onlineProperty().addListener((obs, wasOnline, isOnline) -> {
                if (isOnline) {
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
            Scene splashScene = new Scene(splashRoot, 600, 400);
            splashScene.getStylesheets().add(getClass().getResource("ashigaru.css").toExternalForm());
            splashStage.setScene(splashScene);
            splashStage.setUserData(splashCtrl);
            splashStage.show();
            return splashStage;
        } catch (Exception e) {
            log.warn("Could not load splash screen", e);
            return null;
        }
    }

    private void showMainWindow(Stage splashStage, Stage mainStage) {
        if (splashStage != null) {
            Object userData = splashStage.getUserData();
            if (userData instanceof AshigaruSplashController ctrl) ctrl.stop();
            splashStage.close();
        }

        mainStage.show();

        List<File> recentWalletFiles = Config.get().getRecentWalletFiles();
        if (recentWalletFiles != null) {
            for (File walletFile : recentWalletFiles) {
                if (walletFile.exists()) {
                    mainController.openWalletFile(walletFile);
                }
            }
        }
    }

    @Override
    public void stop() throws Exception {
        AppServices.get().stop();
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
            }
        } else {
            EventManager.get().post(new WalletOpeningEvent(storage, wallet));

            WalletForm walletForm = new WalletForm(storage, wallet);
            EventManager.get().register(walletForm);
            instance.walletForms.put(walletForm.getWalletId(), walletForm);

            List<WalletTabData> tabDataList = instance.walletForms.values().stream()
                    .map(form -> new WalletTabData(TabData.TabType.WALLET, form))
                    .collect(Collectors.toList());
            EventManager.get().post(new OpenWalletsEvent(DEFAULT_WINDOW, tabDataList));

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
        List<WalletTabData> tabDataList = instance.walletForms.values().stream()
                .map(f -> new WalletTabData(TabData.TabType.WALLET, f))
                .collect(Collectors.toList());
        EventManager.get().post(new OpenWalletsEvent(DEFAULT_WINDOW, tabDataList));
    }

}
