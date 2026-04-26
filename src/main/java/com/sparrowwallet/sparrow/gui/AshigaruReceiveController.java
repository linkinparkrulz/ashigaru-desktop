package com.sparrowwallet.sparrow.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.wallet.NodeEntry;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import javafx.animation.PauseTransition;
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

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
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
    @FXML private Button copyBtn;
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
        dialog.setOnShown(e ->
            dialog.getDialogPane().getScene().getWindow().setOnCloseRequest(ev -> dialog.close()));

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
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            // Muted palette: dark foreground on soft off-white
            MatrixToImageConfig config = new MatrixToImageConfig(0xFF1A1B20, 0xFFD8D8D8);
            BufferedImage qr = MatrixToImageWriter.toBufferedImage(matrix, config);

            // Overlay Ashigaru logo in center (~22% of QR width)
            try (InputStream logoIn = getClass().getResourceAsStream("/image/Ashigaru_Terminal_Logo_Circle.png")) {
                if (logoIn != null) {
                    BufferedImage logo = ImageIO.read(logoIn);
                    int logoSize = Math.round(size * 0.22f);
                    int x = (size - logoSize) / 2;
                    int y = (size - logoSize) / 2;
                    Graphics2D g = qr.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // Background patch so logo stays legible
                    g.setColor(new java.awt.Color(0xD8, 0xD8, 0xD8));
                    g.fillRoundRect(x - 4, y - 4, logoSize + 8, logoSize + 8, 8, 8);
                    g.drawImage(logo, x, y, logoSize, logoSize, null);
                    g.dispose();
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(qr, "PNG", baos);
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
        copyBtn.setText("✓");
        copyBtn.setStyle("-fx-text-fill: #4CAF50;");
        PauseTransition pause = new PauseTransition(Duration.seconds(1.5));
        pause.setOnFinished(e -> { copyBtn.setText("⎘"); copyBtn.setStyle("-fx-text-fill: #A0A0A0;"); });
        pause.play();
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
