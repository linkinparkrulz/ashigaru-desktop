package com.sparrowwallet.sparrow;

import com.beust.jcommander.JCommander;
import com.sparrowwallet.drongo.Network;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.sparrowwallet.sparrow.AppController.JPACKAGE_APP_PATH;

/**
 * Relaunches Ashigaru, used to move to another network: a running process cannot change network, because
 * wallets, configuration and the instance lock are all keyed to it.
 */
public final class AppRestart {
    private static final Logger log = LoggerFactory.getLogger(AppRestart.class);

    /** How long to wait for Tor to stop before starting the new process anyway. */
    static final int TOR_STOP_TIMEOUT_SECONDS = 15;

    private AppRestart() {
    }

    /**
     * Whether this process was started from an installed launcher that can be run again. When it was not
     * (a Gradle or IDE run), the user has to start Ashigaru themselves.
     */
    public static boolean canRelaunch() {
        return System.getProperty(JPACKAGE_APP_PATH) != null;
    }

    /**
     * The launcher arguments for the next process: everything this process was started with, except that
     * the network is left out unless one is given. An explicit {@code -n} would otherwise override the
     * network chosen in Settings.
     */
    public static List<String> relaunchParams(Args current, Network network) {
        Args next = new Args();
        next.dir = current.dir;
        next.level = current.level;
        next.network = network;
        return next.toParams();
    }

    /**
     * The arguments this process was started with.
     */
    public static Args currentArgs() {
        Args args = new Args();
        ProcessHandle.current().info().arguments()
                .ifPresent(argv -> JCommander.newBuilder()
                        .addObject(args)
                        .acceptUnknownOptions(true)
                        .build()
                        .parse(argv));
        return args;
    }

    /**
     * Stops the services, waits for Tor to stop, starts a new Ashigaru with the saved network, and exits this one.
     * Starting the new process any earlier risks both using Tor's data directory, which Tor will not allow.
     *
     * <p>The network must already be saved. If the new process cannot be started the application still exits,
     * since its services are stopped, and the user is told how to start it themselves.
     */
    public static void relaunch() {
        AppServices.get().stopServices()
                .orTimeout(TOR_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((result, throwable) -> {
                    if(throwable != null) {
                        log.warn("Tor did not report stopping, restarting anyway", throwable);
                    }
                    return null;
                })
                .thenRun(() -> Platform.runLater(() -> {
                    try {
                        start(relaunchParams(currentArgs(), null));
                    } catch(Exception e) {
                        log.error("Error restarting application", e);
                        AppServices.showErrorDialog("Could not restart", "Ashigaru could not start itself again: " + e.getMessage()
                                + "\n\nYour network choice is saved. Start Ashigaru again to use it.");
                    }

                    Platform.exit();
                }));
    }

    /**
     * Starts a new Ashigaru without waiting for this one, for callers (the terminal UI) that stop it themselves.
     */
    public static void start(List<String> params) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(getPath());
        cmd.addAll(params);

        ProcessBuilder builder = new ProcessBuilder(cmd);
        //An inherited network variable outranks the choice saved in Settings, so the new process must not see it
        builder.environment().remove(AshigaruTerminal.NETWORK_ENV_PROPERTY);
        builder.start();
    }

    public static String getPath() {
        final String jPackageAppPath = System.getProperty(JPACKAGE_APP_PATH);
        if(jPackageAppPath != null) {
            return jPackageAppPath;
        }

        final String userDir = Paths.get(System.getProperty("user.dir"), getAppName()).toString();
        log.warn("Jpackage app path not set, used user.dir ({}) for restart", userDir);
        return userDir;
    }

    private static String getAppName() {
        if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return "Ashigaru.bat";
        }
        return "Ashigaru";
    }
}
