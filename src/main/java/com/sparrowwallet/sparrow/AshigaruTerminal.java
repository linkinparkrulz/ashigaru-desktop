package com.sparrowwallet.sparrow;

import com.beust.jcommander.JCommander;
import com.sparrowwallet.drongo.Drongo;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.event.RequestConnectEvent;
import com.sparrowwallet.sparrow.instance.InstanceException;
import com.sparrowwallet.sparrow.instance.InstanceList;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.PublicElectrumServer;
import com.sparrowwallet.sparrow.net.ServerType;
import com.sparrowwallet.sparrow.terminal.SparrowTerminal;
import com.sun.javafx.application.PlatformImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class AshigaruTerminal {
    public static final String APP_ID = "com.sparrowwallet.sparrow";
    public static final String APP_NAME = "Sparrow";
    public static final String APP_VERSION = "1.0.0";
    public static final String APP_VERSION_SUFFIX = "";
    public static final String APP_HOME_PROPERTY = "sparrow.home";
    public static final String NETWORK_ENV_PROPERTY = "SPARROW_NETWORK";

    private static Instance instance;

    public static void main(String[] argv) {
        Args args = new Args();
        JCommander jCommander = JCommander.newBuilder().addObject(args).programName(APP_NAME.toLowerCase(Locale.ROOT)).acceptUnknownOptions(true).build();
        jCommander.parse(argv);
        if(args.help) {
            jCommander.usage();
            System.exit(0);
        }

        if(args.version) {
            System.out.println("Sparrow Wallet " + APP_VERSION);
            System.exit(0);
        }

        if(args.level != null) {
            Drongo.setRootLogLevel(args.level);
        }

        if(args.dir != null) {
            System.setProperty(APP_HOME_PROPERTY, args.dir);
            getLogger().info("Using configured Sparrow home folder of " + args.dir);
        }

        if(args.network != null) {
            if (args.network == Network.RESTART)
                Network.set(Network.TESTNET4);
            else
                Network.set(args.network);
        } else {
            String envNetwork = System.getenv(NETWORK_ENV_PROPERTY);
            if(envNetwork != null) {
                try {
                    Network.set(Network.valueOf(envNetwork.toUpperCase(Locale.ROOT)));
                } catch(Exception e) {
                    getLogger().warn("Invalid " + NETWORK_ENV_PROPERTY + " property: " + envNetwork);
                }
            }
        }

        File testnetFlag = new File(Storage.getSparrowHome(), "network-" + Network.TESTNET.getName());
        if(testnetFlag.exists()) {
            Network.set(Network.TESTNET);
        }


        File testnet4Flag = new File(Storage.getSparrowHome(), "network-" + Network.TESTNET4.getName());
        if(testnet4Flag.exists()) {
            Network.set(Network.TESTNET4);
        }

        File signetFlag = new File(Storage.getSparrowHome(), "network-" + Network.SIGNET.getName());
        if(signetFlag.exists()) {
            Network.set(Network.SIGNET);
        }

        if(Network.get() != Network.MAINNET) {
            getLogger().info("Using " + Network.get() + " configuration");
        }

        if (Config.get().isFirstRun()) {
            setDefaultServer();
        }

        List<String> fileUriArguments = jCommander.getUnknownOptions();

        try {
            instance = new Instance(fileUriArguments);
            instance.acquireLock(); //If fileUriArguments is not empty, will exit app after sending fileUriArguments if lock cannot be acquired
        } catch(InstanceException e) {
            getLogger().error("Could not access application lock", e);
        }

        if(!fileUriArguments.isEmpty()) {
            AppServices.parseFileUriArguments(fileUriArguments);
        }

        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        if(args.terminal) {
            Interface.set(Interface.TERMINAL);
        } else {
            Interface.set(Interface.DESKTOP);
        }

        try {
            if(Interface.get() == Interface.TERMINAL) {
                PlatformImpl.setTaskbarApplication(false);
                Drongo.removeRootLogAppender("STDOUT");
                com.sun.javafx.application.LauncherImpl.launchApplication(SparrowTerminal.class, SparrowWalletPreloader.class, argv);
            } else {
                com.sun.javafx.application.LauncherImpl.launchApplication(com.sparrowwallet.sparrow.gui.AshigaruGui.class, SparrowWalletPreloader.class, argv);
            }
        } catch(UnsupportedOperationException e) {
            Drongo.removeRootLogAppender("STDOUT");
            getLogger().error("Unable to launch application", e);
            System.out.println("No display detected. This application has not been built to run on a headless (no display) system.");

            try {
                if(instance != null) {
                    instance.freeLock();
                }
            } catch(InstanceException instanceException) {
                getLogger().error("Unable to free instance lock", e);
            }
        }
    }

    private static void setDefaultServer() {
        if (Network.get() == Network.TESTNET4) {
            Config.get().setMode(Mode.ONLINE);
            Config.get().setServerType(ServerType.PUBLIC_ELECTRUM_SERVER);
            Config.get().setPublicElectrumServer(PublicElectrumServer.TEST4_ARANGUREN_CLEARNET.getServer());
            EventManager.get().post(new RequestConnectEvent());
            Config.get().setFirstRun(false);
            return;
        }
        if (Network.get() == Network.MAINNET) {
            Config.get().setMode(Mode.ONLINE);
            Config.get().setServerType(ServerType.PUBLIC_ELECTRUM_SERVER);
            Config.get().setPublicElectrumServer(PublicElectrumServer.FOUNDATION_DEVICES.getServer());
            EventManager.get().post(new RequestConnectEvent());
            Config.get().setFirstRun(false);
        }
    }

    public static Instance getSparrowInstance() {
        return instance;
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(AshigaruTerminal.class);
    }

    public static class Instance extends InstanceList {
        private final List<String> fileUriArguments;

        public Instance(List<String> fileUriArguments) {
            super(AshigaruTerminal.APP_ID + "." + Network.get(), !fileUriArguments.isEmpty());
            this.fileUriArguments = fileUriArguments;
        }

        @Override
        protected void receiveMessageList(List<String> messageList) {
            if(messageList != null && !messageList.isEmpty()) {
                AppServices.parseFileUriArguments(messageList);
                AppServices.openFileUriArguments(null);
            }
        }

        @Override
        protected List<String> sendMessageList() {
            return fileUriArguments;
        }

        @Override
        protected void beforeExit() {
            getLogger().info("Opening files/URIs in already running instance, exiting...");
        }
    }
}
