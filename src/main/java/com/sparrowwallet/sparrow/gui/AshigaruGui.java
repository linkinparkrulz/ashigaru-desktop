package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.*;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
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

        stage.setTitle("Ashigaru Terminal " + AshigaruTerminal.APP_VERSION);
        stage.setMinWidth(800);
        stage.setMinHeight(540);
        stage.setScene(scene);
        stage.show();

        // Restore recently opened wallets
        List<File> recentWalletFiles = Config.get().getRecentWalletFiles();
        if (recentWalletFiles != null) {
            for (File walletFile : recentWalletFiles) {
                if (walletFile.exists()) {
                    mainController.openWalletFile(walletFile);
                }
            }
        }

        AppServices.get().start();
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
        if (wallet.isNested()) {
            String masterId = storage.getWalletId(wallet.getMasterWallet());
            WalletForm masterForm = instance.walletForms.get(masterId);
            if (masterForm != null) {
                WalletForm childForm = new WalletForm(storage, wallet);
                EventManager.get().register(childForm);
                masterForm.getNestedWalletForms().add(childForm);
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

    // -------------------------------------------------------------------------
    // Global event subscriptions
    // -------------------------------------------------------------------------

    @Subscribe
    public void walletOpened(WalletOpenedEvent event) {
        if (event.getWallet().isMasterWallet()) {
            Platform.runLater(() -> {
                if (mainController != null) mainController.refreshWalletList();
            });
        }
    }
}
