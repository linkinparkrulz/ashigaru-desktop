package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.swap.SwapService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.controlsfx.glyphfont.Glyph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.ResourceBundle;

public class ToolsController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(ToolsController.class);

    @FXML private ToggleGroup toolsMenu;
    @FXML private ToggleButton bip47VerifierButton;
    @FXML private ToggleButton statsButton;
    @FXML private ToggleButton logsButton;
    @FXML private ToggleButton tourButton;
    @FXML private ToggleButton swapsButton;
    @FXML private StackPane toolsPane;
    @FXML private VBox toolsSidebar;
    @FXML private Label toolsTitle;
    @FXML private Button collapseBtn;
    @FXML private Glyph collapseGlyph;

    private Toggle requested;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        toolsMenu.selectedToggleProperty().addListener((observable, oldValue, selectedToggle) -> {
            if(selectedToggle == null) {
                oldValue.setSelected(true);
                return;
            }

            if(selectedToggle == bip47VerifierButton) {
                setToolPane("bip47-message-verifier.fxml");
            } else if(selectedToggle == statsButton) {
                setToolPane("stats.fxml");
            } else if(selectedToggle == logsButton) {
                setToolPane("logs.fxml");
            } else if(selectedToggle == tourButton) {
                setToolPane("tour-launcher.fxml");
            } else if(selectedToggle == swapsButton) {
                setToolPane("swaps.fxml");
            }
        });

        for(Toggle toggle : toolsMenu.getToggles()) {
            ToggleButton item = (ToggleButton)toggle;
            item.setTooltip(new Tooltip(item.getText()));
        }
        setCollapsed(Config.get().isToolsMenuCollapsed());

        boolean swaps = SwapService.get().isEnabled();
        swapsButton.setVisible(swaps);
        swapsButton.setManaged(swaps);

        // Select whichever tool is listed first rather than naming one, so the highlight follows
        // the sidebar order. getToggles() is in FXML declaration order.
        Platform.runLater(() -> {
            if(requested != null) {
                toolsMenu.selectToggle(requested);
            } else if(!toolsMenu.getToggles().isEmpty()) {
                toolsMenu.selectToggle(toolsMenu.getToggles().get(0));
            }
        });
    }

    @FXML
    private void onToggleCollapse() {
        boolean collapsed = !toolsSidebar.getStyleClass().contains("collapsed");
        Config.get().setToolsMenuCollapsed(collapsed);
        setCollapsed(collapsed);
    }

    /** Icons only, so the tool gets the width; the names stay in each icon's tooltip */
    private void setCollapsed(boolean collapsed) {
        toolsSidebar.getStyleClass().remove("collapsed");
        if(collapsed) {
            toolsSidebar.getStyleClass().add("collapsed");
        }
        toolsTitle.setVisible(!collapsed);
        toolsTitle.setManaged(!collapsed);
        for(Toggle toggle : toolsMenu.getToggles()) {
            ((ToggleButton)toggle).setContentDisplay(collapsed ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
        }
        collapseGlyph.setIcon(collapsed ? "ANGLE_DOUBLE_RIGHT" : "ANGLE_DOUBLE_LEFT");
        collapseBtn.setTooltip(new Tooltip(collapsed ? "Show tool names" : "Icons only"));
    }

    /** Opens on Swaps instead of the first tool, e.g. after choosing a Badbank coin to swap */
    public void showSwaps() {
        requested = swapsButton;
        if(toolsMenu.getSelectedToggle() != null) {
            toolsMenu.selectToggle(swapsButton);
        }
    }

    private void setToolPane(String fxmlName) {
        toolsPane.getChildren().clear();

        try {
            FXMLLoader toolLoader = new FXMLLoader(AppServices.class.getResource("gui/" + fxmlName));
            Node toolNode = toolLoader.load();
            //Scroll rather than clip: a tool narrower or shorter than its content otherwise loses
            //whatever lies past the edge. fitToHeight still lets the Logs text area fill the height.
            ScrollPane scrollPane = new ScrollPane(toolNode);
            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(true);
            scrollPane.getStyleClass().add("preferences-scroll");
            toolsPane.getChildren().add(scrollPane);
        } catch(Exception e) {
            log.error("Could not load tool pane: " + fxmlName, e);
            throw new IllegalStateException("Can't find tool pane: " + fxmlName, e);
        }
    }
}
