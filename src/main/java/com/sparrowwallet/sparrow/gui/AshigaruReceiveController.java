package com.sparrowwallet.sparrow.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Modality;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Controller for the Receive address dialog.
 * Shows the current fresh receive address, derivation path, and last-used status.
 */
public class AshigaruReceiveController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(AshigaruReceiveController.class);
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    @FXML private TextField addressField;
    @FXML private ImageView qrCodeView;
    @FXML private Label derivationLabel;
    @FXML private Label lastUsedLabel;
    @FXML private Button closeBtn;
    @FXML private Button nextAddressBtn;

    private WalletForm walletForm;
    private NodeEntry currentEntry;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // No-op; populated via show()
    }

    // -------------------------------------------------------------------------
    // Static factory — opens dialog modally
    // -------------------------------------------------------------------------

    public static void show(WalletForm walletForm) throws Exception {
        FXMLLoader loader = new FXMLLoader(AshigaruReceiveController.class.getResource("ashigaru-receive.fxml"));
        DialogPane pane = loader.load();
        AshigaruReceiveController ctrl = loader.getController();
        ctrl.walletForm = walletForm;
        ctrl.refreshAddress(null);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(walletForm.getWallet().getFullDisplayName() + " — Receive");
        dialog.setDialogPane(pane);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(AshigaruGui.get().getMainStage());
        dialog.setResultConverter(btn -> null);

        dialog.showAndWait();
    }

    // -------------------------------------------------------------------------
    // Address refresh
    // -------------------------------------------------------------------------

    private void refreshAddress(NodeEntry previous) {
        NodeEntry fresh = walletForm.getFreshNodeEntry(KeyPurpose.RECEIVE, previous);
        setNodeEntry(fresh);
    }

    private void setNodeEntry(NodeEntry entry) {
        this.currentEntry = entry;
        String address = entry.getAddress().toString();
        addressField.setText(address);
        derivationLabel.setText(buildDerivationPath(entry.getNode()));
        lastUsedLabel.setText(buildLastUsedText(entry));
        qrCodeView.setImage(generateQrCode(address, 200));
    }

    private Image generateQrCode(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.MARGIN, "1"));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos, new MatrixToImageConfig());
            return new Image(new ByteArrayInputStream(baos.toByteArray()));
        } catch (Exception e) {
            log.error("Error generating QR code", e);
            return null;
        }
    }

    private String buildDerivationPath(WalletNode node) {
        Wallet wallet = walletForm.getWallet();
        if (wallet.getKeystores().size() == 1) {
            KeyDerivation kd = wallet.getKeystores().get(0).getKeyDerivation();
            return kd.extend(node.getDerivation()).getDerivationPath();
        }
        return node.getDerivationPath().replace("m", "multi");
    }

    private String buildLastUsedText(NodeEntry entry) {
        WalletNode node = entry.getNode();
        if (node.getTransactionOutputs() == null || node.getTransactionOutputs().isEmpty()) {
            return "Never used";
        }
        BlockTransactionHashIndex latest = node.getTransactionOutputs().stream()
                .max((a, b) -> {
                    if (a.getDate() == null) return -1;
                    if (b.getDate() == null) return 1;
                    return a.getDate().compareTo(b.getDate());
                })
                .orElse(null);
        if (latest == null || latest.getDate() == null) {
            return "Used (unconfirmed)";
        }
        return "Last used " + DATE_FORMAT.format(latest.getDate());
    }

    // -------------------------------------------------------------------------
    // Button actions
    // -------------------------------------------------------------------------

    @FXML
    private void onCopy() {
        if (currentEntry == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(currentEntry.getAddress().toString());
        Clipboard.getSystemClipboard().setContent(content);
        Tooltip tip = new Tooltip("Copied!");
        tip.show(addressField.getScene().getWindow());
        new Timeline(new KeyFrame(Duration.seconds(1), e -> tip.hide())).play();
    }

    @FXML
    private void onNextAddress() {
        refreshAddress(currentEntry);
    }

    @FXML
    private void onClose() {
        closeBtn.getScene().getWindow().hide();
    }
}
