package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.swap.MoneroNodes;
import com.sparrowwallet.sparrow.swap.SwapService;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings - Swaps. One switch for the whole app, the Monero nodes swaps trust, and how much one swap
 * may lock while this is experimental.
 */
public class SwapsPreferencesController extends PreferencesDetailController {
    @FXML private UnlabeledToggleSwitch swapsEnabled;
    @FXML private Label engineStatus;
    @FXML private TextField maxSwap;
    @FXML private VBox nodeList;
    @FXML private TextField customNode;
    @FXML private Label nodeMessage;

    private final List<CheckBox> nodeBoxes = new ArrayList<>();

    @Override
    public void initializeView(Config config) {
        SwapService service = SwapService.get();
        swapsEnabled.setDisable(!service.isAvailable());
        swapsEnabled.setSelected(config.isSwapsEnabled() && service.isAvailable());
        swapsEnabled.selectedProperty().addListener((observable, oldValue, enabled) -> {
            config.setSwapsEnabled(enabled);
            if(enabled) {
                service.startIfEnabled();
            } else {
                service.stop();
            }
        });
        engineStatus.textProperty().bind(service.statusProperty());
        if(!service.isAvailable()) {
            service.startIfEnabled();
        }

        maxSwap.setText(btc(config.getSwapMaxSats()));
        maxSwap.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if(!focused) {
                saveMaxSwap(config);
            }
        });
        maxSwap.setOnAction(event -> saveMaxSwap(config));

        buildNodeList(config);
        customNode.setOnAction(event -> onAddNode());
    }

    private void buildNodeList(Config config) {
        nodeList.getChildren().clear();
        nodeBoxes.clear();
        List<String> selected = MoneroNodes.getSelected();
        for(MoneroNodes.Node node : MoneroNodes.PUBLIC_NODES) {
            nodeList.getChildren().add(nodeRow(node.name(), node.address(), selected.contains(node.address()), false, config));
        }
        for(String address : config.getSwapCustomMoneroNodes()) {
            nodeList.getChildren().add(nodeRow("Your node", address, selected.contains(address), true, config));
        }
        showNodeProblem();
    }

    private HBox nodeRow(String name, String address, boolean ticked, boolean custom, Config config) {
        CheckBox box = new CheckBox(name);
        box.setUserData(address);
        box.setSelected(ticked);
        box.setMinWidth(130);
        box.selectedProperty().addListener((observable, oldValue, newValue) -> saveSelection(config));
        nodeBoxes.add(box);

        Label addressLabel = new Label(address);
        addressLabel.getStyleClass().add("card-faint");
        addressLabel.setMinWidth(0);
        HBox.setHgrow(addressLabel, Priority.ALWAYS);
        HBox row = new HBox(10, box, addressLabel);
        row.setAlignment(Pos.CENTER_LEFT);

        if(custom) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button remove = new Button("Remove");
            remove.setMinWidth(Region.USE_PREF_SIZE);
            remove.getStyleClass().add("secondary-btn");
            remove.setOnAction(event -> {
                List<String> customNodes = new ArrayList<>(config.getSwapCustomMoneroNodes());
                customNodes.remove(address);
                config.setSwapCustomMoneroNodes(customNodes);
                List<String> selected = new ArrayList<>(MoneroNodes.getSelected());
                selected.remove(address);
                config.setSwapMoneroNodes(selected);
                buildNodeList(config);
                SwapService.get().restart();
            });
            row.getChildren().addAll(spacer, remove);
        }
        return row;
    }

    @FXML
    private void onAddNode() {
        Config config = Config.get();
        String address = customNode.getText().trim().toLowerCase(Locale.ROOT);
        if(!MoneroNodes.isOnionHostAndPort(address)) {
            showNodeMessage("Enter your node's onion address and RPC port, e.g. abc…xyz.onion:18081.");
            return;
        }
        List<String> customNodes = new ArrayList<>(config.getSwapCustomMoneroNodes());
        if(!customNodes.contains(address) && !MoneroNodes.isPublic(address)) {
            customNodes.add(address);
            config.setSwapCustomMoneroNodes(customNodes);
        }
        List<String> selected = new ArrayList<>(MoneroNodes.getSelected());
        if(!selected.contains(address)) {
            selected.add(address);
            config.setSwapMoneroNodes(selected);
        }
        customNode.clear();
        buildNodeList(config);
        SwapService.get().restart();
    }

    private void saveSelection(Config config) {
        List<String> selected = nodeBoxes.stream().filter(CheckBox::isSelected).map(box -> (String)box.getUserData()).toList();
        config.setSwapMoneroNodes(new ArrayList<>(selected));
        showNodeProblem();
        SwapService.get().restart();
    }

    private void showNodeProblem() {
        String problem = MoneroNodes.problem(MoneroNodes.getSelected());
        showNodeMessage(problem);
    }

    private void showNodeMessage(String message) {
        nodeMessage.setText(message == null ? "" : message);
        nodeMessage.setVisible(message != null);
        nodeMessage.setManaged(message != null);
    }

    private void saveMaxSwap(Config config) {
        try {
            long sats = new BigDecimal(maxSwap.getText().trim()).movePointRight(8).longValueExact();
            if(sats > 0) {
                config.setSwapMaxSats(sats);
            }
        } catch(ArithmeticException | NumberFormatException e) {
            //Leave the saved value
        }
        maxSwap.setText(btc(config.getSwapMaxSats()));
    }

    private static String btc(long sats) {
        return new BigDecimal(sats).movePointLeft(8).stripTrailingZeros().toPlainString();
    }
}
