package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.gui.Icons;
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
    @FXML private Button checkNodesBtn;
    @FXML private Button addNodeBtn;
    @FXML private Button editNodesBtn;
    @FXML private Label nodeLockNote;
    @FXML private Label nodeLockDetail;
    @FXML private Label nodeLockIcon;
    @FXML private Region statusDot;
    @FXML private VBox nodeCard;
    @FXML private HBox addNodeRow;

    private final List<CheckBox> nodeBoxes = new ArrayList<>();
    private final List<Button> removeButtons = new ArrayList<>();
    //Each node's last check result, beside its address
    private final java.util.Map<String, Label> nodeResults = new java.util.HashMap<>();

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
            updateNodeLock();
        });
        engineStatus.textProperty().bind(service.statusProperty());
        showRunning(service.runningProperty().get());
        service.runningProperty().addListener((observable, wasRunning, running) -> showRunning(running));
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
        removeButtons.clear();
        nodeResults.clear();
        List<String> selected = MoneroNodes.getSelected();
        for(MoneroNodes.Node node : MoneroNodes.PUBLIC_NODES) {
            nodeList.getChildren().add(nodeRow(node.name(), node.address(), selected.contains(node.address()), false, config));
        }
        for(String address : config.getSwapCustomMoneroNodes()) {
            nodeList.getChildren().add(nodeRow("Your node", address, selected.contains(address), true, config));
        }
        showNodeProblem();
        updateNodeLock();
    }

    private HBox nodeRow(String name, String address, boolean ticked, boolean custom, Config config) {
        CheckBox box = new CheckBox(name);
        box.setUserData(address);
        box.setSelected(ticked);
        box.setMinWidth(130);
        box.selectedProperty().addListener((observable, oldValue, newValue) -> saveSelection(config));
        nodeBoxes.add(box);

        Label addressLabel = new Label(address);
        addressLabel.getStyleClass().add("node-address");
        box.getStyleClass().add("node-check");
        addressLabel.setMinWidth(0);
        HBox.setHgrow(addressLabel, Priority.ALWAYS);
        Label result = new Label();
        result.setMinWidth(Region.USE_PREF_SIZE);
        nodeResults.put(address, result);
        HBox row = new HBox(10, box, addressLabel, result);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("swaps-node-row");

        if(custom) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button remove = new Button("Remove");
            remove.setMinWidth(Region.USE_PREF_SIZE);
            remove.getStyleClass().add("secondary-btn");
            removeButtons.add(remove);
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

    /**
     * The nodes are locked while swaps are on, as the Bitcoin server's settings are while Ashigaru is connected to it:
     * the engine was started with them, and a change that quietly restarted it would pull it out from under a swap. A
     * selection that cannot be used yet stays editable, since the engine is not running on it.
     */
    private void updateNodeLock() {
        boolean locked = swapsEnabled.isSelected() && MoneroNodes.problem(MoneroNodes.getSelected()) == null;
        //Locked, the list is a record of what swaps use: the ticks stay put, and what would change it is not offered
        nodeBoxes.forEach(box -> box.setDisable(locked));
        removeButtons.forEach(button -> {
            button.setVisible(!locked);
            button.setManaged(!locked);
        });
        addNodeRow.setVisible(!locked);
        addNodeRow.setManaged(!locked);
        editNodesBtn.setVisible(locked);
        editNodesBtn.setManaged(locked);
        if(locked) {
            nodeLockIcon.setGraphic(Icons.of(FontAwesome5.Glyph.LOCK, 12));
            nodeLockNote.setText("Locked while swaps are on");
            nodeLockDetail.setText("Edit nodes turns swaps off while you change them. Turn them back on when you are done.");
            if(!nodeCard.getStyleClass().contains("locked")) {
                nodeCard.getStyleClass().add("locked");
            }
        } else {
            nodeLockIcon.setGraphic(Icons.of(FontAwesome5.Glyph.LOCK_OPEN, 12));
            nodeLockNote.setText("Choose the nodes swaps use");
            nodeLockDetail.setText("Tick at least two public nodes, or add your own below.");
            nodeCard.getStyleClass().remove("locked");
        }
    }

    /** The dot beside the status: green while the swap engine runs */
    private void showRunning(boolean running) {
        statusDot.getStyleClass().remove("on");
        if(running) {
            statusDot.getStyleClass().add("on");
        }
    }

    /** Like "Edit Existing Connection" for the server: unlocks the nodes by turning swaps off, unless a swap is relying on them */
    @FXML
    private void onEditNodes() {
        if(SwapService.get().isSwapActive()) {
            showNodeMessage("A swap is running and relies on these nodes. Wait until it has finished, then edit them.");
            return;
        }
        //The switch's own listener turns the engine off and unlocks the nodes
        swapsEnabled.setSelected(false);
        showNodeMessage("Swaps are off while you change the nodes. Turn them back on when you are done.");
    }

    @FXML
    private void onCheckNodes() {
        SwapService service = SwapService.get();
        if(!service.runningProperty().get()) {
            showNodeMessage("Turn swaps on first: the nodes are checked through the swap engine and Tor.");
            return;
        }
        checkNodesBtn.setDisable(true);
        nodeResults.values().forEach(label -> label.setText(""));
        showNodeMessage("Asking each ticked node for its block height through Tor. This can take up to a minute.");
        service.checkNodes(check -> {
            checkNodesBtn.setDisable(false);
            for(com.google.gson.JsonElement element : check) {
                com.google.gson.JsonObject node = element.getAsJsonObject();
                Label label = nodeResults.get(node.get("node").getAsString());
                if(label != null) {
                    boolean ok = node.get("ok").getAsBoolean();
                    label.setText(ok ? "✓ block " + node.get("height").getAsString() : "✗ no answer");
                    label.getStyleClass().removeAll("swaps-good", "swaps-bad");
                    label.getStyleClass().add(ok ? "swaps-good" : "swaps-bad");
                }
            }
            List<String> unreachable = SwapService.unreachable(check);
            showNodeMessage(unreachable.isEmpty() ? "Every node answered." : "Not answering:\n" + String.join("\n", unreachable));
        }, e -> {
            checkNodesBtn.setDisable(false);
            showNodeMessage("Could not check the nodes: " + e.getMessage());
        });
    }

    private void saveSelection(Config config) {
        List<String> selected = nodeBoxes.stream().filter(CheckBox::isSelected).map(box -> (String)box.getUserData()).toList();
        config.setSwapMoneroNodes(new ArrayList<>(selected));
        showNodeProblem();
        SwapService.get().restart();
        updateNodeLock();
    }

    private void showNodeProblem() {
        List<String> selected = MoneroNodes.getSelected();
        String problem = MoneroNodes.problem(selected);
        //Ticks that will be ignored are said now, while they are being chosen, not found out from the status afterwards
        showNodeMessage(problem != null ? problem : MoneroNodes.note(selected));
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
