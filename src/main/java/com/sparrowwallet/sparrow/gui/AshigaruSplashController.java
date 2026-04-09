package com.sparrowwallet.sparrow.gui;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

public class AshigaruSplashController {
    @FXML private Label statusLabel;
    @FXML private ProgressBar progressBar;

    public void setStatus(String message) {
        if (statusLabel != null) statusLabel.setText(message);
    }

    public void stop() {
        // nothing to clean up
    }
}
