package com.sparrowwallet.sparrow.gui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.sparrowwallet.sparrow.io.ImageUtils;
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

/** QR codes for the Ashigaru screens, drawn on this computer */
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
     * For a long text that another phone has to read off the screen: black on white, no logo, the lowest error
     * correction so that every module is as large as it can be, the quiet zone the QR standard asks for (four modules;
     * two made about one picture in twenty fail to read), and whole pixels per module so that none is drawn wider than
     * its neighbours. The picture is close to {@code size} across, a little under or over it.
     */
    static BufferedImage dense(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 4);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            //Asked for no particular size, the writer returns the code and its quiet zone at one pixel a module
            BitMatrix modules = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
            int scale = Math.max(1, Math.round((float)size / modules.getWidth()));
            int width = modules.getWidth() * scale;
            int height = modules.getHeight() * scale;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for(int y = 0; y < height; y++) {
                for(int x = 0; x < width; x++) {
                    image.setRGB(x, y, modules.get(x / scale, y / scale) ? 0x000000 : 0xFFFFFF);
                }
            }
            return image;
        } catch(Exception e) {
            //Never the content: it may be keys
            log.error("Error generating QR code", e);
            return null;
        }
    }

    /**
     * Whether a plain QR reader gets {@code content} back out of the picture. A long, dense code can, by chance, hold a
     * pattern the reader takes for a finder square and then loses the code (about one in twenty-five of the pairing
     * codes did, whatever the error correction), so a picture that fails this is replaced rather than shown.
     */
    static boolean reads(BufferedImage image, String content) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            return content.equals(new QRCodeReader().decode(bitmap).getText());
        } catch(Exception e) {
            return false;
        }
    }

    static Image toFx(BufferedImage image) {
        return ImageUtils.toFxImage(image);
    }

    private static BufferedImage render(String content, int size, ErrorCorrectionLevel level, int margin, int dark, int light) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, margin);
        hints.put(EncodeHintType.ERROR_CORRECTION, level);
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        return MatrixToImageWriter.toBufferedImage(matrix, new MatrixToImageConfig(dark, light));
    }
}
