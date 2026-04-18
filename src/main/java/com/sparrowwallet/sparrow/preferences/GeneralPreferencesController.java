package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.TextfieldDialog;
import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.net.BlockExplorer;
import com.sparrowwallet.sparrow.net.FeeRatesSource;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class GeneralPreferencesController extends PreferencesDetailController {
    private static final Logger log = LoggerFactory.getLogger(GeneralPreferencesController.class);

    private static final Server CUSTOM_BLOCK_EXPLORER = new Server("http://custom.block.explorer");

    @FXML
    private ComboBox<FeeRatesSource> feeRatesSource;

    @FXML
    private ComboBox<Server> blockExplorers;

    @FXML
    private UnlabeledToggleSwitch loadRecentWallets;

    @FXML
    private UnlabeledToggleSwitch validateDerivationPaths;

    @FXML
    private UnlabeledToggleSwitch groupByAddress;

    @FXML
    private UnlabeledToggleSwitch includeMempoolOutputs;

    @FXML
    private UnlabeledToggleSwitch notifyNewTransactions;

    @FXML
    private UnlabeledToggleSwitch checkNewVersions;

    @Override
    public void initializeView(Config config) {
        if(config.getFeeRatesSource() != null) {
            feeRatesSource.setValue(config.getFeeRatesSource());
        } else {
            feeRatesSource.getSelectionModel().select(1);
            config.setFeeRatesSource(feeRatesSource.getValue());
        }

        feeRatesSource.valueProperty().addListener((observable, oldValue, newValue) -> {
            config.setFeeRatesSource(newValue);
            EventManager.get().post(new FeeRatesSourceChangedEvent(newValue));
        });

        blockExplorers.setItems(getBlockExplorerList());
        blockExplorers.setConverter(new StringConverter<>() {
            @Override
            public String toString(Server server) {
                if(server == null || server == BlockExplorer.NONE.getServer()) {
                    return "None";
                }

                if(server == CUSTOM_BLOCK_EXPLORER) {
                    return "Custom...";
                }

                return server.getHost();
            }

            @Override
            public Server fromString(String string) {
                return null;
            }
        });
        blockExplorers.valueProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue != null) {
                if(newValue == CUSTOM_BLOCK_EXPLORER) {
                    TextfieldDialog textfieldDialog = new TextfieldDialog();
                    textfieldDialog.initOwner(blockExplorers.getScene().getWindow());
                    textfieldDialog.setTitle("Enter Block Explorer URL");
                    textfieldDialog.setHeaderText("Enter the URL of the block explorer.\n\nIf present, the characters {0} will be replaced with the txid.\nFor example, https://localhost or https://localhost/tx/{0}\n");
                    textfieldDialog.getEditor().setPromptText("https://localhost");
                    Optional<String> optUrl = textfieldDialog.showAndWait();
                    if(optUrl.isPresent() && !optUrl.get().isEmpty()) {
                        try {
                            Server server = getBlockExplorer(optUrl.get());
                            config.setBlockExplorer(server);
                            Platform.runLater(() -> {
                                blockExplorers.getSelectionModel().select(-1);
                                blockExplorers.setItems(getBlockExplorerList());
                                blockExplorers.setValue(Config.get().getBlockExplorer());
                            });
                        } catch(Exception e) {
                            AppServices.showErrorDialog("Invalid URL", "The URL " + optUrl.get() + " is not valid.");
                            blockExplorers.setValue(oldValue);
                        }
                    } else {
                        blockExplorers.setValue(oldValue);
                    }
                } else {
                    Config.get().setBlockExplorer(newValue);
                }
            }
        });

        if(config.getBlockExplorer() != null) {
            blockExplorers.setValue(config.getBlockExplorer());
        } else {
            blockExplorers.getSelectionModel().select(0);
        }

        loadRecentWallets.setSelected(config.isLoadRecentWallets());
        loadRecentWallets.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setLoadRecentWallets(newValue);
            EventManager.get().post(new RequestOpenWalletsEvent());
        });

        validateDerivationPaths.setSelected(config.isValidateDerivationPaths());
        validateDerivationPaths.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setValidateDerivationPaths(newValue);
            System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_SCRIPT_TYPES_PROPERTY, Boolean.toString(!newValue));
            System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, Boolean.toString(!newValue));
        });

        groupByAddress.setSelected(config.isGroupByAddress());
        includeMempoolOutputs.setSelected(config.isIncludeMempoolOutputs());
        groupByAddress.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setGroupByAddress(newValue);
        });
        includeMempoolOutputs.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setIncludeMempoolOutputs(newValue);
            EventManager.get().post(new IncludeMempoolOutputsChangedEvent());
        });

        notifyNewTransactions.setSelected(config.isNotifyNewTransactions());
        notifyNewTransactions.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setNotifyNewTransactions(newValue);
        });

        checkNewVersions.setSelected(config.isCheckNewVersions());
        checkNewVersions.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setCheckNewVersions(newValue);
            EventManager.get().post(new VersionCheckStatusEvent(newValue));
        });
    }

    private static Server getBlockExplorer(String serverUrl) {
        String url = serverUrl.trim();
        if(url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new Server(url);
    }

    private ObservableList<Server> getBlockExplorerList() {
        List<Server> servers = Arrays.stream(BlockExplorer.values()).map(BlockExplorer::getServer).collect(Collectors.toList());
        if(Config.get().getBlockExplorer() != null && !servers.contains(Config.get().getBlockExplorer())) {
            servers.add(Config.get().getBlockExplorer());
        }
        servers.add(CUSTOM_BLOCK_EXPLORER);
        return FXCollections.observableList(servers);
    }

}
