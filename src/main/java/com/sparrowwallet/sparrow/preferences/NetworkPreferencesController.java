package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppRestart;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.NetworkSettings;
import com.sparrowwallet.sparrow.io.WhirlpoolEndpointValidator;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;

/**
 * Settings - Network. Switches between networks (which restarts Ashigaru, since a running process is tied to one),
 * and sets the Whirlpool server each network mixes with.
 *
 * <p>These live in {@link NetworkSettings}, in the Ashigaru home folder, rather than in {@link Config}: the configuration
 * file belongs to one network, so it can neither record which network to start on nor hold both networks' servers.
 */
public class NetworkPreferencesController extends PreferencesDetailController {
    private static final Logger log = LoggerFactory.getLogger(NetworkPreferencesController.class);

    @FXML private Label activeNetworkLabel;
    @FXML private ComboBox<Network> networkCombo;
    @FXML private Button switchBtn;

    @FXML private TextField mainnetCoordinator;
    @FXML private TextField mainnetOutputReg;
    @FXML private TextField mainnetKey;
    @FXML private Button mainnetBrowse;
    @FXML private Button mainnetSave;
    @FXML private Button mainnetReset;
    @FXML private Label mainnetStatus;

    @FXML private TextField testnetCoordinator;
    @FXML private TextField testnetOutputReg;
    @FXML private TextField testnetKey;
    @FXML private Button testnetBrowse;
    @FXML private Button testnetSave;
    @FXML private Button testnetReset;
    @FXML private Label testnetStatus;

    @Override
    public void initializeView(Config config) {
        Network active = Network.get();
        activeNetworkLabel.setText(active.toDisplayString());

        networkCombo.setItems(FXCollections.observableArrayList(NetworkSettings.SELECTABLE_NETWORKS));
        networkCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Network network) {
                return network == null ? "" : network.toDisplayString();
            }

            @Override
            public Network fromString(String string) {
                return null;
            }
        });
        //A network launched with -n outside the offered list (signet, testnet3) has no entry to show as current
        networkCombo.setValue(NetworkSettings.SELECTABLE_NETWORKS.contains(active) ? active : null);
        switchBtn.setDisable(true);
        networkCombo.valueProperty().addListener((observable, oldValue, newValue) -> switchBtn.setDisable(newValue == null || newValue == active));

        new WhirlpoolSection(Network.MAINNET, mainnetCoordinator, mainnetOutputReg, mainnetKey, mainnetBrowse, mainnetSave, mainnetReset, mainnetStatus).initialize();
        new WhirlpoolSection(Network.TESTNET4, testnetCoordinator, testnetOutputReg, testnetKey, testnetBrowse, testnetSave, testnetReset, testnetStatus).initialize();
    }

    @FXML
    private void onSwitch() {
        Network target = networkCombo.getValue();
        if(target == null || target == Network.get()) {
            return;
        }

        boolean toTestnet = target != Network.MAINNET;
        String content = "Ashigaru will close and reopen on " + target.toDisplayString() + ", with that network's own wallets and settings. "
                + "Your " + Network.get().toDisplayString() + " wallets are kept, and come back when you switch again.\n\n"
                + "Any mixing in progress stops while Ashigaru restarts."
                + (toTestnet ? "\n\nTestnet coins have no value." : "\n\nMainnet uses real bitcoin.");
        Optional<ButtonType> choice = AppServices.showAlertDialog("Switch to " + target.toDisplayString() + "?", content,
                Alert.AlertType.CONFIRMATION, ButtonType.CANCEL, ButtonType.OK);
        if(choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }

        NetworkSettings settings = NetworkSettings.get();
        settings.setNetwork(target);
        settings.flush();

        if(!AppRestart.canRelaunch()) {
            //Running from source or an IDE: there is no installed launcher to start again
            AppServices.showSuccessDialog("Network saved", "Ashigaru will open on " + target.toDisplayString()
                    + " the next time it starts. Close it and start it again to switch now.");
            return;
        }

        switchBtn.setDisable(true);
        networkCombo.setDisable(true);
        AppRestart.relaunch();
    }

    /**
     * The entry fields for one network's Whirlpool server.
     */
    private static class WhirlpoolSection {
        private final Network network;
        private final TextField coordinator;
        private final TextField outputReg;
        private final TextField key;
        private final Button browse;
        private final Button save;
        private final Button reset;
        private final Label status;

        WhirlpoolSection(Network network, TextField coordinator, TextField outputReg, TextField key, Button browse, Button save, Button reset, Label status) {
            this.network = network;
            this.coordinator = coordinator;
            this.outputReg = outputReg;
            this.key = key;
            this.browse = browse;
            this.save = save;
            this.reset = reset;
            this.status = status;
        }

        void initialize() {
            show(NetworkSettings.get().getWhirlpool(network));

            browse.setOnAction(event -> {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Select the coordinator's blind signature public key");
                fileChooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("PEM files", "*.pem", "*.pub", "*.key"),
                        new FileChooser.ExtensionFilter("All Files", org.controlsfx.tools.Platform.getCurrent().equals(org.controlsfx.tools.Platform.UNIX) ? "*" : "*.*")
                );

                Stage window = new Stage();
                AppServices.moveToActiveWindowScreen(window, 800, 450);
                File file = fileChooser.showOpenDialog(window);
                if(file != null) {
                    key.setText(file.getAbsolutePath());
                }
            });

            save.setOnAction(event -> onSave());
            reset.setOnAction(event -> onReset());
        }

        private void onSave() {
            Optional<String> problem = WhirlpoolEndpointValidator.validate(coordinator.getText(), outputReg.getText(), key.getText());
            if(problem.isPresent()) {
                setStatus(problem.get(), true);
                return;
            }

            NetworkSettings settings = NetworkSettings.get();
            NetworkSettings.WhirlpoolEndpoint endpoint = new NetworkSettings.WhirlpoolEndpoint(coordinator.getText(), outputReg.getText(), key.getText());
            settings.setWhirlpool(network, endpoint);
            settings.flush();

            show(settings.getWhirlpool(network));
            setStatus(endpoint.isEmpty() ? "Using the built-in server." : "Saved." + restartNote(), false);
        }

        private void onReset() {
            NetworkSettings settings = NetworkSettings.get();
            settings.setWhirlpool(network, null);
            settings.flush();

            show(null);
            setStatus("Using the built-in server." + restartNote(), false);
        }

        /** A wallet that is already mixing keeps the server it started with; one that is not picks up the change. */
        private String restartNote() {
            return network == Network.get() ? " Wallets already mixing keep their current server until mixing is stopped and started again." : "";
        }

        private void show(NetworkSettings.WhirlpoolEndpoint endpoint) {
            coordinator.setText(endpoint == null ? "" : endpoint.getCoordinatorUrl());
            outputReg.setText(endpoint == null || endpoint.getOutputRegUrlOverride() == null ? "" : endpoint.getOutputRegUrlOverride());
            key.setText(endpoint == null || endpoint.getBlindSignatureKeyPath() == null ? "" : endpoint.getBlindSignatureKeyPath());
        }

        private void setStatus(String message, boolean error) {
            status.setText(message);
            status.getStyleClass().removeAll("verifier-step-fail");
            if(error) {
                status.getStyleClass().add("verifier-step-fail");
            }
        }
    }
}
