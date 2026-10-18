package com.sparrowwallet.sparrow.terminal;

import com.beust.jcommander.JCommander;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.dialogs.ActionListDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.FileDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialogBuilder;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.crypto.InvalidPasswordException;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.Args;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.StorageEvent;
import com.sparrowwallet.sparrow.event.TimedEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.terminal.preferences.GeneralDialog;
import com.sparrowwallet.sparrow.terminal.preferences.ServerStatusDialog;
import com.sparrowwallet.sparrow.terminal.preferences.ServerTypeDialog;
import com.sparrowwallet.sparrow.terminal.wallet.Bip39Dialog;
import com.sparrowwallet.sparrow.terminal.wallet.LoadWallet;
import com.sparrowwallet.sparrow.terminal.wallet.WatchOnlyDialog;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.ButtonType;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sparrowwallet.sparrow.AppController.JPACKAGE_APP_PATH;
import static com.sparrowwallet.sparrow.AppServices.showErrorDialog;
import static java.util.Objects.nonNull;

public class MasterActionListBox extends Panel {
    private static final Logger log = LoggerFactory.getLogger(MasterActionListBox.class);

    public static final int MAX_RECENT_WALLETS = 10;

    public MasterActionListBox(SparrowTerminal sparrowTerminal) {
        super(new BorderLayout());

        final ActionListBox actionListBox = new ActionListBox(new TerminalSize(21, 4));

        actionListBox.addItem("Wallets", () -> {
            ActionListDialogBuilder builder = new ActionListDialogBuilder();
            builder.setTitle("Wallets");
            if(Config.get().getRecentWalletFiles() != null) {
                for(int i = 0; i < Config.get().getRecentWalletFiles().size() && i < MAX_RECENT_WALLETS; i++) {
                    File recentWalletFile = Config.get().getRecentWalletFiles().get(i);
                    if(!recentWalletFile.exists()) {
                        continue;
                    }

                    Storage storage = new Storage(recentWalletFile);

                    Optional<Wallet> optWallet = AppServices.get().getOpenWallets().entrySet().stream()
                            .filter(entry -> entry.getValue().getWalletFile().equals(recentWalletFile)).map(Map.Entry::getKey)
                            .map(wallet -> wallet.isMasterWallet() ? wallet : wallet.getMasterWallet()).findFirst();
                    if(optWallet.isPresent()) {
                        Wallet wallet = optWallet.get();
                        Storage existingStorage = AppServices.get().getOpenWallets().get(wallet);
                        builder.addAction(storage.getWalletName(null) + "*", () -> openLoadedWallet(existingStorage, optWallet.get()));
                    } else {
                        builder.addAction(storage.getWalletName(null), new LoadWallet(storage));
                    }
                }
            }
            builder.addAction("Open Wallet...", () -> SparrowTerminal.get().getGuiThread().invokeLater(MasterActionListBox::openWallet));
            builder.addAction("Create / restore wallet...", () -> SparrowTerminal.get().getGuiThread().invokeLater(MasterActionListBox::createWallet));
            builder.addAction("Delete Wallet...", () -> SparrowTerminal.get().getGuiThread().invokeLater(MasterActionListBox::deleteWallet));
            builder.addAction("", () -> {});
            builder.addAction("<Main Menu>", () ->{});
            builder.setCanCancel(false);
            builder.build().showDialog(SparrowTerminal.get().getGui());
        });

        actionListBox.addItem("Preferences", () -> new ActionListDialogBuilder()
                .setTitle("Preferences")
                .addAction("General", () -> {
                    GeneralDialog generalDialog = new GeneralDialog();
                    generalDialog.showDialog(sparrowTerminal.getGui());
                })
                .addAction("Server", () -> {
                    if(Config.get().hasServer()) {
                        ServerStatusDialog serverStatusDialog = new ServerStatusDialog();
                        serverStatusDialog.showDialog(sparrowTerminal.getGui());
                    } else {
                        ServerTypeDialog serverTypeDialog = new ServerTypeDialog();
                        serverTypeDialog.showDialog(sparrowTerminal.getGui());
                    }
                })
                .build()
                .showDialog(sparrowTerminal.getGui()));

        actionListBox.addItem("Quit", () -> sparrowTerminal.getGui().getMainWindow().close());
        final Network restartNetwk = Network.MAINNET == Network.get() ? Network.TESTNET4 : Network.MAINNET;
        actionListBox.addItem("Restart in " + restartNetwk.toDisplayString(), () -> restartIn(sparrowTerminal, restartNetwk));

        actionListBox.setTheme(new CustomActionListBoxTheme());
        setTheme(actionListBox.getTheme());
        addComponent(actionListBox, BorderLayout.Location.CENTER);
    }

    private void restartIn(SparrowTerminal sparrowTerminal, final Network networkToRestart) {

        final Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                final Args args = new Args();
                ProcessHandle.current().info().arguments()
                        .ifPresent(argv -> JCommander.newBuilder()
                                .addObject(args)
                                .acceptUnknownOptions(true)
                                .build()
                                .parse(argv));

                if (networkToRestart == Network.TESTNET4)
                    args.network = Network.RESTART;
                else
                    args.network = networkToRestart;

                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(getPath());
                    cmd.addAll(args.toParams());
                    final ProcessBuilder builder = new ProcessBuilder(cmd);
                    builder.start();
                } catch(Exception e) {
                    log.error("Error restarting application", e);
                    throw e;
                }
                return null;
            }

            @Override
            protected void succeeded() {
                log.info("Application restarted");
                sparrowTerminal.getGui().getMainWindow().close();
            }

            @Override
            protected void failed() {
                log.error("Error restarting application", getException());
                sparrowTerminal.getGui().getMainWindow().close();
            }
        };
        new Thread(task).start();
    }

    private static String getPath() {
        final String jPackageAppPath = System.getProperty(JPACKAGE_APP_PATH);
        if(nonNull(jPackageAppPath)) {
            return jPackageAppPath;
        } else {
            final String userDir = Paths.get(System.getProperty("user.dir"), getAppName()).toString();
            log.warn("Jpackage app path not set, used user.dir ({}) for restart", userDir);
            return userDir;
        }
    }

    private static String getAppName() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return "Ashigaru.bat";
        }
        return "Ashigaru";
    }

    private static void openLoadedWallet(Storage storage, Wallet wallet) {
        if(SparrowTerminal.get().isLocked(storage)) {
            String walletId = storage.getWalletId(wallet);

            TextInputDialogBuilder builder = new TextInputDialogBuilder().setTitle("Wallet Password");
            builder.setDescription("Enter the wallet password:");
            builder.setPasswordInput(true);

            String password = builder.build().showDialog(SparrowTerminal.get().getGui());
            if(password != null) {
                Platform.runLater(() -> {
                    Storage.KeyDerivationService keyDerivationService = new Storage.KeyDerivationService(storage, new SecureString(password), true);
                    keyDerivationService.setOnSucceeded(workerStateEvent -> {
                        EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.END, "Done"));
                        keyDerivationService.getValue().clear();
                        SparrowTerminal.get().unlockWallet(storage);
                        SparrowTerminal.get().getGuiThread().invokeLater(() -> LoadWallet.getOpeningDialog(storage, wallet).showDialog(SparrowTerminal.get().getGui()));
                    });
                    keyDerivationService.setOnFailed(workerStateEvent -> {
                        EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.END, "Failed"));
                        if(keyDerivationService.getException() instanceof InvalidPasswordException) {
                            showErrorDialog("Invalid Password", "The wallet password was invalid.");
                        } else {
                            log.error("Error deriving wallet key", keyDerivationService.getException());
                        }
                    });
                    EventManager.get().post(new StorageEvent(walletId, TimedEvent.Action.START, "Decrypting wallet..."));
                    keyDerivationService.start();
                });
            }
        } else {
            SparrowTerminal.get().getGuiThread().invokeLater(() -> LoadWallet.getOpeningDialog(storage, wallet).showDialog(SparrowTerminal.get().getGui()));
        }
    }

    private static void openWallet() {
        FileDialogBuilder openBuilder = new FileDialogBuilder().setTitle("Open Wallet");
        openBuilder.setShowHiddenDirectories(true);
        openBuilder.setSelectedFile(Storage.getWalletsDir());
        File file = openBuilder.build().showDialog(SparrowTerminal.get().getGui());
        if(file != null) {
            LoadWallet loadWallet = new LoadWallet(new Storage(file));
            SparrowTerminal.get().getGuiThread().invokeLater(loadWallet);
        }
    }

    private static void deleteWallet() {
        FileDialogBuilder openBuilder = new FileDialogBuilder().setTitle("Delete Wallet");
        openBuilder.setShowHiddenDirectories(true);
        openBuilder.setSelectedFile(Storage.getWalletsDir());
        File file = openBuilder.build().showDialog(SparrowTerminal.get().getGui());
        if(file != null) {
            Storage storage = new Storage(file);
            try {
                Optional<ButtonType> optResponse = AppServices.showWarningDialog("Delete " + storage.getWalletFile().getName(), "Are you sure you want to delete this wallet?", ButtonType.CANCEL, ButtonType.OK);
                if(optResponse.isPresent() && optResponse.get().equals(ButtonType.OK))
                    deleteStorage(storage, false);
                else
                    return;
            } catch (Exception ignored) {
                showErrorDialog("Error deleting wallet", "Could not delete " + storage.getWalletFile().getName()  + ". Please delete this file manually.");
            }
        }
    }

    private static void deleteStorage(Storage storage, boolean deleteBackups) {
        if(storage.isClosed()) {
            Platform.runLater(() -> {
                Storage.DeleteWalletService deleteWalletService = new Storage.DeleteWalletService(storage, deleteBackups);
                deleteWalletService.setDelay(Duration.seconds(3));
                deleteWalletService.setPeriod(Duration.hours(1));
                deleteWalletService.setOnSucceeded(event -> {
                    deleteWalletService.cancel();
                    if(!deleteWalletService.getValue()) {
                        showErrorDialog("Error deleting wallet", "Could not delete " + storage.getWalletFile().getName()  + ". Please delete this file manually.");
                    }
                });
                deleteWalletService.setOnFailed(event -> {
                    deleteWalletService.cancel();
                    showErrorDialog("Error deleting wallet", "Could not delete " + storage.getWalletFile().getName()  + ". Please delete this file manually.");
                });
                deleteWalletService.start();
            });
        } else {
            Platform.runLater(() -> deleteStorage(storage, deleteBackups));
        }
    }

    private static void createWallet() {
        TextInputDialogBuilder newWalletNameBuilder = new TextInputDialogBuilder();
        newWalletNameBuilder.setTitle("Create Wallet");
        newWalletNameBuilder.setDescription("Enter a name for the wallet");
        newWalletNameBuilder.setValidator(content -> content.isEmpty() ? "Please enter a name" : (Storage.walletExists(content) ? "Wallet already exists" : null));
        String walletName = newWalletNameBuilder.build().showDialog(SparrowTerminal.get().getGui());

        if(walletName != null) {
            ActionListDialogBuilder newBuilder = new ActionListDialogBuilder();
            newBuilder.setTitle("Create Wallet");
            newBuilder.setDescription("Choose the type of wallet");
            newBuilder.addAction("Hot Wallet", () -> {
                Bip39Dialog bip39Dialog = new Bip39Dialog(walletName);
                bip39Dialog.showDialog(SparrowTerminal.get().getGui());
            });
            newBuilder.addAction("Watch Only", () -> {
                WatchOnlyDialog watchOnlyDialog = new WatchOnlyDialog(walletName);
                watchOnlyDialog.showDialog(SparrowTerminal.get().getGui());
            });
            newBuilder.build().showDialog(SparrowTerminal.get().getGui());
        }
    }
}
