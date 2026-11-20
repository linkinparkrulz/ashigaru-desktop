package com.sparrowwallet.sparrow.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** QR pictures for the Ashigaru screens, made on this computer */
final class QrImages {
    private static final Logger log = LoggerFactory.getLogger(QrImages.class);

    private static final int DARK_MUTED = 0xFF1A1B20;
    private static final int LIGHT_MUTED = 0xFFD8D8D8;

    private QrImages() {}

    /**
     * The Receive screen's picture: muted colours, the Ashigaru logo in the middle, and error correction high enough
     * to survive it. For short texts such as an address.
     */
    static BufferedImage branded(String content, int size) {
        try {
            BufferedImage qr = render(content, size, ErrorCorrectionLevel.H, 1, DARK_MUTED, LIGHT_MUTED);
            try(InputStream logoIn = QrImages.class.getResourceAsStream("/image/Ashigaru_Terminal_Logo_Square.png")) {
                if(logoIn != null) {
                    BufferedImage logo = ImageIO.read(logoIn);
                    int logoSize = Math.round(size * 0.22f);
                    int x = (size - logoSize) / 2;
                    int y = (size - logoSize) / 2;
                    Graphics2D g = qr.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    //Background patch so the logo stays legible
                    g.setColor(new java.awt.Color(0xD8, 0xD8, 0xD8));
                    g.fillRoundRect(x - 4, y - 4, logoSize + 8, logoSize + 8, 8, 8);
                    g.drawImage(logo, x, y, logoSize, logoSize, null);
                    g.dispose();
                }
            }
            return qr;
        } catch(Exception e) {
            log.error("Error generating QR code", e);
            return null;
        }
    }

    /**
     * For a long text that another phone has to read off the screen: black on white, no logo, a quiet margin, and the
     * lowest error correction so that every module is as large as it can be.
     */
    static BufferedImage dense(String content, int size) {
        try {
            return render(content, size, ErrorCorrectionLevel.L, 2, 0xFF000000, 0xFFFFFFFF);
        } catch(Exception e) {
            //Never the content: it may be keys
            log.error("Error generating QR code", e);
            return null;
        }
    }

    static Image toFx(BufferedImage image) {
        return image == null ? null : SwingFXUtils.toFXImage(image, null);
    }

    private static BufferedImage render(String content, int size, ErrorCorrectionLevel level, int margin, int dark, int light) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, margin);
        hints.put(EncodeHintType.ERROR_CORRECTION, level);
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        return MatrixToImageWriter.toBufferedImage(matrix, new MatrixToImageConfig(dark, light));
    }
}
