package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.AppServices;
import javafx.scene.layout.HBox;
import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The startup splash.
 *
 * <p>The application already broadcasts what it is doing while starting - Tor bootstrap, server
 * connection, wallet history - so this listens rather than showing an indeterminate bar regardless.
 * That matters most during Tor bootstrap, which is the slow part of a cold start and the point at
 * which a bar that never moves reads as a hang.
 */
public class AshigaruSplashController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(AshigaruSplashController.class);

    /** Tor reports bootstrap progress in its own log lines; this is the only part worth surfacing. */
    private static final Pattern BOOTSTRAPPED = Pattern.compile("Bootstrapped (\\d{1,3})%");

    private enum State {
        PENDING("•", null),
        ACTIVE("▸", "splash-step-active"),
        DONE("✓", "splash-step-done"),
        SKIPPED("–", null),
        FAILED("✗", "splash-step-failed");

        private final String marker;
        private final String styleClass;

        State(String marker, String styleClass) {
            this.marker = marker;
            this.styleClass = styleClass;
        }
    }

    @FXML private VBox stepBox;
    @FXML private Label torStep;
    @FXML private Label serverStep;
    @FXML private Label walletsStep;
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;
    @FXML private HBox failureActions;

    private Runnable onOpenNow;

    /** What "Open without waiting" does: the caller reveals the main window straight away. */
    public void setOnOpenNow(Runnable onOpenNow) {
        this.onOpenNow = onOpenNow;
    }

    private void showFailureActions(boolean show) {
        failureActions.setVisible(show);
        failureActions.setManaged(show);
    }

    @FXML
    private void onOpenNow() {
        if(onOpenNow != null) {
            onOpenNow.run();
        }
    }

    @FXML
    private void onRetryTor() {
        if(AppServices.get().restartTor()) {
            showFailureActions(false);
            setStep(torStep, "Restarting Tor", State.ACTIVE);
            statusLabel.setText("Restarting Tor\u2026");
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        } else {
            statusLabel.setText("Tor could not be restarted from here. Open the app and check Settings, Server.");
        }
    }

    private volatile boolean stopped;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setStep(torStep, "Tor", State.PENDING);
        setStep(serverStep, "Connecting to server", State.PENDING);
        setStep(walletsStep, "Loading wallets", State.PENDING);
        EventManager.get().register(this);
    }

    /**
     * Called when the main window takes over. Unregistering matters: the splash stage is closed
     * immediately afterwards, and a subscriber left registered would keep posting into discarded
     * nodes for the life of the application.
     */
    public void stop() {
        if(!stopped) {
            stopped = true;
            try {
                EventManager.get().unregister(this);
            } catch(Exception e) {
                log.debug("Could not unregister splash from the event bus", e);
            }
        }
    }

    public void setStatus(String message) {
        onFx(() -> statusLabel.setText(message));
    }

    @Subscribe
    public void torStatus(TorStatusEvent event) {
        if(event instanceof TorFailedStatusEvent) {
            onFx(() -> {
                setStep(torStep, "Tor failed to start", State.FAILED);
                statusLabel.setText(event.getStatus());
                progressBar.setProgress(0);
                showFailureActions(true);
            });
            return;
        }

        if(event instanceof TorReadyStatusEvent) {
            onFx(() -> {
                setStep(torStep, "Tor ready", State.DONE);
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            });
            return;
        }

        if(event instanceof TorExternalStatusEvent) {
            onFx(() -> setStep(torStep, "Using the running Tor instance", State.DONE));
            return;
        }

        if(event instanceof TorBootStatusEvent) {
            onFx(() -> setStep(torStep, "Starting Tor", State.ACTIVE));
            return;
        }

        //Everything else on this event is a raw message relayed from the Tor manager, including
        //debug lines. Only the bootstrap percentage is worth showing; the rest would turn the
        //status line into a flicker of internals.
        Matcher matcher = BOOTSTRAPPED.matcher(event.getStatus() == null ? "" : event.getStatus());
        if(matcher.find()) {
            int percent = Math.min(100, Integer.parseInt(matcher.group(1)));
            onFx(() -> {
                setStep(torStep, "Bootstrapping Tor (" + percent + "%)", percent >= 100 ? State.DONE : State.ACTIVE);
                progressBar.setProgress(percent / 100.0);
            });
        }
    }

    @Subscribe
    public void connectionStart(ConnectionStartEvent event) {
        onFx(() -> {
            setStep(serverStep, "Connecting to server", State.ACTIVE);
            statusLabel.setText(event.getStatus());
        });
    }

    @Subscribe
    public void connected(ConnectionEvent event) {
        onFx(() -> {
            setStep(serverStep, "Connected at block " + event.getBlockHeight(), State.DONE);
            progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        });
    }

    @Subscribe
    public void connectionFailed(ConnectionFailedEvent event) {
        onFx(() -> {
            setStep(serverStep, "Could not connect to server", State.FAILED);
            progressBar.setProgress(0);
        });
    }

    /** Cormorant reports a real percentage while bitcoind syncs or rescans. */
    @Subscribe
    public void cormorantStatus(CormorantStatusEvent event) {
        int percent = -1;
        if(event instanceof CormorantSyncStatusEvent syncStatus) {
            percent = syncStatus.getProgress();
        } else if(event instanceof CormorantScanStatusEvent scanStatus) {
            percent = scanStatus.getProgress();
        }

        int progress = percent;
        onFx(() -> {
            setStep(serverStep, event.getStatus(), State.ACTIVE);
            statusLabel.setText(event.getStatus());
            progressBar.setProgress(progress >= 0 && progress <= 100 ? progress / 100.0 : ProgressBar.INDETERMINATE_PROGRESS);
        });
    }

    @Subscribe
    public void walletHistoryStatus(WalletHistoryStatusEvent event) {
        if(event instanceof WalletHistoryFinishedEvent) {
            onFx(() -> setStep(walletsStep, "Wallets loaded", State.DONE));
        } else if(event instanceof WalletHistoryFailedEvent) {
            onFx(() -> setStep(walletsStep, "Could not load wallet history", State.FAILED));
        } else {
            onFx(() -> setStep(walletsStep, "Loading wallets", State.ACTIVE));
        }
    }

    /** Marks any step still pending as skipped, for paths that never reach them (offline mode). */
    public void skipRemaining() {
        onFx(() -> {
            skipIfPending(torStep, "Tor not required");
            skipIfPending(serverStep, "Offline");
            skipIfPending(walletsStep, "No wallets to load");
        });
    }

    private void skipIfPending(Label label, String text) {
        if(label != null && label.getText() != null && label.getText().startsWith(State.PENDING.marker)) {
            setStep(label, text, State.SKIPPED);
        }
    }

    private void setStep(Label label, String text, State state) {
        if(label == null) {
            return;
        }

        label.setText(state.marker + "  " + text);
        label.getStyleClass().removeAll("splash-step-active", "splash-step-done", "splash-step-failed");
        if(state.styleClass != null) {
            label.getStyleClass().add(state.styleClass);
        }
    }

    /**
     * Events arrive off the FX thread. Updates are dropped once stopped, so a late event cannot
     * touch nodes belonging to a closed stage.
     */
    private void onFx(Runnable action) {
        if(stopped) {
            return;
        }

        Platform.runLater(() -> {
            if(!stopped) {
                try {
                    action.run();
                } catch(Exception e) {
                    log.debug("Could not update splash", e);
                }
            }
        });
    }
}
