package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.swap.SwapService;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Settings - Swaps. Turns Badbank to XMR swaps on, and holds what the engine needs that Ashigaru
 * does not already know: the Monero node, and how much one swap may lock while this is experimental.
 */
public class SwapsPreferencesController extends PreferencesDetailController {
    @FXML private UnlabeledToggleSwitch swapsEnabled;
    @FXML private TextField moneroNode;
    @FXML private Label moneroNodeError;
    @FXML private TextField maxSwap;
    @FXML private Label engineStatus;

    @Override
    public void initializeView(Config config) {
        SwapService service = SwapService.get();
        boolean available = service.isAvailable();
        swapsEnabled.setDisable(!available);

        swapsEnabled.setSelected(config.isSwapsEnabled() && available);
        swapsEnabled.selectedProperty().addListener((observable, oldValue, enabled) -> {
            config.setSwapsEnabled(enabled);
            if(enabled) {
                service.startForOpenWallet();
            }
        });

        moneroNode.setText(config.getSwapMoneroNode() == null ? "" : config.getSwapMoneroNode());
        moneroNode.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if(!focused) {
                saveMoneroNode(config, service);
            }
        });
        moneroNode.setOnAction(event -> saveMoneroNode(config, service));

        maxSwap.setText(btc(config.getSwapMaxSats()));
        maxSwap.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if(!focused) {
                saveMaxSwap(config);
            }
        });
        maxSwap.setOnAction(event -> saveMaxSwap(config));

        engineStatus.textProperty().bind(service.statusProperty());
        if(config.isSwapsEnabled()) {
            service.startForOpenWallet();
        }
    }

    private void saveMoneroNode(Config config, SwapService service) {
        String node = moneroNode.getText().trim();
        boolean valid = node.isEmpty() || SwapService.isOnionHostAndPort(node);
        moneroNodeError.setVisible(!valid);
        moneroNodeError.setManaged(!valid);
        if(valid && !node.equals(config.getSwapMoneroNode() == null ? "" : config.getSwapMoneroNode())) {
            config.setSwapMoneroNode(node.isEmpty() ? null : node);
            service.startForOpenWallet();
        }
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
        return new BigDecimal(sats).movePointLeft(8).stripTrailingZeros().toPlainString().toLowerCase(Locale.ROOT);
    }
}
