package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.AshigaruTerminal;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.event.VersionCheckStatusEvent;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.update.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings - Update. Finds new releases, and verifies one against the pinned release signing key
 * before the user is given anything to run.
 *
 * <p>The verification is shown link by link rather than reduced to a single verdict: the checks
 * that the release notes tell users to perform by hand are the same ones happening here, and seeing
 * them pass is the reason to trust the result.
 */
public class UpdatePreferencesController extends PreferencesDetailController {
    private static final Logger log = LoggerFactory.getLogger(UpdatePreferencesController.class);

    private static final int MAX_ATTESTATION_BYTES = 1024 * 1024;

    @FXML private UnlabeledToggleSwitch checkForUpdates;
    @FXML private Label currentVersionLabel;
    @FXML private Label latestVersionLabel;
    @FXML private Button checkNowBtn;

    @FXML private VBox updateBox;
    @FXML private Label updateTitleLabel;
    @FXML private ComboBox<ReleaseArtifactSelector.Candidate> artifactCombo;
    @FXML private Button downloadBtn;
    @FXML private Button cancelBtn;
    @FXML private ProgressBar downloadProgress;
    @FXML private Label progressLabel;

    @FXML private Label signatureStep;
    @FXML private Label commitmentStep;
    @FXML private Label versionStep;
    @FXML private Label artifactStep;
    @FXML private VBox resultBox;
    @FXML private Label resultTitleLabel;
    @FXML private Label resultDetailLabel;
    @FXML private Button installBtn;
    @FXML private Button revealBtn;

    private ReleaseFetcher.Release release;
    private File verifiedFile;
    private volatile boolean cancelRequested;

    @Override
    public void initializeView(Config config) {
        currentVersionLabel.setText(AshigaruTerminal.APP_VERSION + AshigaruTerminal.APP_VERSION_SUFFIX);

        checkForUpdates.setSelected(config.isUpdateCheckEnabled());
        checkForUpdates.selectedProperty().addListener((observable, oldValue, newValue) -> {
            config.setUpdateCheckConsent(newValue);
            //Starts or stops the scheduled check to match
            EventManager.get().post(new VersionCheckStatusEvent(newValue));
        });

        artifactCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(ReleaseArtifactSelector.Candidate candidate) {
                if(candidate == null) {
                    return "";
                }
                String size = candidate.size() > 0 ? "  (" + (candidate.size() / 1024 / 1024) + " MB)" : "";
                return candidate.name() + size + (candidate.suggested() ? "  - matches this system" : "");
            }

            @Override
            public ReleaseArtifactSelector.Candidate fromString(String string) {
                return null;
            }
        });

        //A check may already have run in the background before this pane was opened
        ReleaseFetcher.Release known = UpdateCheckService.getLatestRelease();
        if(known != null) {
            showRelease(known);
        } else if(!config.isUpdateCheckEnabled()) {
            latestVersionLabel.setText("Not checked - automatic checks are off");
        }
    }

    @FXML
    private void onCheckNow() {
        checkNowBtn.setDisable(true);
        latestVersionLabel.setText("Checking…");

        Task<ReleaseFetcher.Release> task = new Task<>() {
            @Override
            protected ReleaseFetcher.Release call() throws Exception {
                return ReleaseFetcher.fetchLatestRelease();
            }
        };

        task.setOnSucceeded(event -> {
            checkNowBtn.setDisable(false);
            showRelease(task.getValue());
        });

        task.setOnFailed(event -> {
            checkNowBtn.setDisable(false);
            Throwable e = task.getException();
            log.info("Could not check for updates: " + (e == null ? "unknown error" : e.getMessage()));
            latestVersionLabel.setText("Could not reach GitHub" + (e == null ? "" : ": " + e.getMessage()));
        });

        runTask(task);
    }

    private void showRelease(ReleaseFetcher.Release release) {
        this.release = release;
        if(release == null) {
            latestVersionLabel.setText("No release found");
            return;
        }

        String version = ReleaseVerifier.stripLeadingV(release.tag());
        latestVersionLabel.setText(release.tag());

        if(!UpdateCheckService.isNewer(version)) {
            updateTitleLabel.setText("You are up to date");
            setVisible(updateBox, false);
            return;
        }

        List<ReleaseArtifactSelector.Candidate> candidates = ReleaseArtifactSelector.select(release.assets());
        if(candidates.isEmpty()) {
            updateTitleLabel.setText("Ashigaru " + version + " is available, but has no package for this system");
            setVisible(updateBox, true);
            downloadBtn.setDisable(true);
            return;
        }

        artifactCombo.setItems(FXCollections.observableArrayList(candidates));
        candidates.stream().filter(ReleaseArtifactSelector.Candidate::suggested).findFirst()
                .ifPresentOrElse(c -> artifactCombo.getSelectionModel().select(c),
                        () -> artifactCombo.getSelectionModel().selectFirst());

        updateTitleLabel.setText("Ashigaru " + version + " is available");
        downloadBtn.setDisable(false);
        resetChain();
        setVisible(updateBox, true);
    }

    @FXML
    private void onDownload() {
        ReleaseArtifactSelector.Candidate candidate = artifactCombo.getSelectionModel().getSelectedItem();
        if(candidate == null || release == null) {
            return;
        }

        resetChain();
        cancelRequested = false;
        verifiedFile = null;
        downloadBtn.setDisable(true);
        setVisible(cancelBtn, true);
        setVisible(downloadProgress, true);
        setVisible(progressLabel, true);
        downloadProgress.setProgress(0);
        progressLabel.setText("Starting download…");

        File destination = new File(updatesDir(), candidate.name());

        Task<ReleaseVerifier.Result> task = new Task<>() {
            @Override
            protected ReleaseVerifier.Result call() throws Exception {
                ReleaseAsset sums = requireAsset(ReleaseTrust.SUMS_ASSET);
                ReleaseAsset message = requireAsset(ReleaseTrust.MESSAGE_ASSET);
                ReleaseAsset signature = requireAsset(ReleaseTrust.SIGNATURE_ASSET);

                byte[] sumsBytes = ReleaseFetcher.fetchBytes(sums.url(), MAX_ATTESTATION_BYTES);
                String messageText = new String(ReleaseFetcher.fetchBytes(message.url(), MAX_ATTESTATION_BYTES), StandardCharsets.UTF_8);
                String signatureText = new String(ReleaseFetcher.fetchBytes(signature.url(), MAX_ATTESTATION_BYTES), StandardCharsets.UTF_8);

                ReleaseFetcher.download(candidate.url(), destination.toPath(), candidate.size(), (read, total) -> {
                    if(total > 0) {
                        double fraction = (double)read / total;
                        Platform.runLater(() -> {
                            downloadProgress.setProgress(fraction);
                            progressLabel.setText((read / 1024 / 1024) + " MB of " + (total / 1024 / 1024) + " MB");
                        });
                    }
                    return !cancelRequested;
                });

                Platform.runLater(() -> progressLabel.setText("Verifying…"));
                return ReleaseVerifier.verify(messageText, signatureText, sumsBytes, destination,
                        candidate.name(), release.tag(), AshigaruTerminal.APP_VERSION);
            }
        };

        task.setOnSucceeded(event -> {
            downloadBtn.setDisable(false);
            setVisible(cancelBtn, false);
            setVisible(downloadProgress, false);
            setVisible(progressLabel, false);
            applyResult(task.getValue(), destination);
        });

        task.setOnFailed(event -> {
            downloadBtn.setDisable(false);
            setVisible(cancelBtn, false);
            setVisible(downloadProgress, false);
            setVisible(progressLabel, false);
            deleteQuietly(destination);

            Throwable e = task.getException();
            if(e instanceof ReleaseFetcher.CancelledException) {
                showResult(false, "Download cancelled", null);
            } else {
                log.warn("Could not download the update", e);
                showResult(false, "Could not download the update",
                        e == null ? null : e.getMessage());
            }
        });

        runTask(task);
    }

    private ReleaseAsset requireAsset(String name) throws Exception {
        ReleaseAsset asset = release.asset(name);
        if(asset == null) {
            throw new IllegalStateException("This release does not publish " + name
                    + ", so it cannot be verified. Download it from " + ReleaseTrust.RELEASES_PAGE_URL + " instead.");
        }
        return asset;
    }

    @FXML
    private void onCancel() {
        cancelRequested = true;
        progressLabel.setText("Cancelling…");
    }

    private void applyResult(ReleaseVerifier.Result result, File file) {
        List<Label> steps = List.of(signatureStep, commitmentStep, versionStep, artifactStep);
        ReleaseVerifier.Step[] all = ReleaseVerifier.Step.values();

        if(result.verified()) {
            for(int i = 0; i < all.length; i++) {
                markStep(steps.get(i), all[i], true);
            }

            verifiedFile = file;
            showResult(true, "Verified - signed by the Ashigaru release key", file.getAbsolutePath());
            setVisible(installBtn, launchesInstaller());
            setVisible(revealBtn, true);
            return;
        }

        //Everything before the failing link did pass, so show how far the chain got
        int failedIndex = result.failedAt() == null ? 0 : result.failedAt().ordinal();
        for(int i = 0; i < all.length; i++) {
            if(i < failedIndex) {
                markStep(steps.get(i), all[i], true);
            } else if(i == failedIndex) {
                markStep(steps.get(i), all[i], false);
            }
        }

        //A file that failed verification is never left where it could be run by accident
        deleteQuietly(file);
        showResult(false, "This download could not be verified, and has been deleted", result.detail());
    }

    private void markStep(Label label, ReleaseVerifier.Step step, boolean passed) {
        label.setText((passed ? "✓  " : "✗  ") + step.getDescription());
        label.getStyleClass().removeAll("verifier-step-pass", "verifier-step-fail");
        label.getStyleClass().add(passed ? "verifier-step-pass" : "verifier-step-fail");
    }

    private void resetChain() {
        List<Label> steps = List.of(signatureStep, commitmentStep, versionStep, artifactStep);
        ReleaseVerifier.Step[] all = ReleaseVerifier.Step.values();
        for(int i = 0; i < all.length; i++) {
            Label label = steps.get(i);
            label.getStyleClass().removeAll("verifier-step-pass", "verifier-step-fail");
            label.setText("•  " + all[i].getDescription());
        }

        setVisible(resultBox, false);
        setVisible(installBtn, false);
        setVisible(revealBtn, false);
        verifiedFile = null;
    }

    private void showResult(boolean success, String title, String detail) {
        resultBox.getStyleClass().removeAll("verifier-result-success", "verifier-result-error");
        resultBox.getStyleClass().add(success ? "verifier-result-success" : "verifier-result-error");
        resultTitleLabel.setText(title);
        resultDetailLabel.setText(detail == null ? "" : detail);
        setVisible(resultBox, true);
    }

    @FXML
    private void onInstall() {
        if(verifiedFile == null) {
            return;
        }

        try {
            Desktop.getDesktop().open(verifiedFile);
            //The running app cannot replace itself, so it steps aside for the installer
            Platform.exit();
        } catch(Exception e) {
            log.warn("Could not launch the installer", e);
            showResult(false, "Could not start the installer", "Open it yourself from " + verifiedFile.getAbsolutePath());
            setVisible(revealBtn, true);
        }
    }

    @FXML
    private void onReveal() {
        if(verifiedFile == null) {
            return;
        }

        try {
            AppServices.get().getApplication().getHostServices().showDocument(verifiedFile.getParentFile().toURI().toString());
        } catch(Exception e) {
            log.warn("Could not open the download folder", e);
            showResult(true, "Verified", "The file is at " + verifiedFile.getAbsolutePath());
        }
    }

    @FXML
    private void onOpenReleasePage() {
        try {
            AppServices.get().getApplication().getHostServices().showDocument(ReleaseTrust.RELEASES_PAGE_URL);
        } catch(Exception e) {
            log.warn("Could not open the releases page", e);
        }
    }

    /**
     * Windows and macOS hand the verified file to the OS; Linux only reveals it, because the new
     * AppImage replaces the old one wherever the user keeps it, which the app cannot know.
     */
    private static boolean launchesInstaller() {
        return ReleaseArtifactSelector.currentFamily() == ReleaseArtifactSelector.Family.WINDOWS
                || ReleaseArtifactSelector.currentFamily() == ReleaseArtifactSelector.Family.MACOS;
    }

    private static File updatesDir() {
        return new File(Storage.getSparrowHome(), "updates");
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch(Exception e) {
            log.warn("Could not delete " + file.getAbsolutePath(), e);
        }
    }

    private static void setVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static void runTask(Task<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }
}
