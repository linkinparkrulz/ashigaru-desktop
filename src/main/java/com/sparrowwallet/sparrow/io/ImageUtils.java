package com.sparrowwallet.sparrow.io;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;

public class ImageUtils {
    public static byte[] resize(Image image, int width, int height) {
        return resize(SwingFXUtils.fromFXImage(image, null), width, height);
    }

    public static byte[] resize(BufferedImage image, int width, int height) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resize(image, baos, width, height);
            return baos.toByteArray();
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void resize(BufferedImage image, OutputStream outputStream, int width, int height) throws IOException {
        ImageIO.write(resizeToImage(image, width, height), "png", outputStream);
    }

    public static BufferedImage resizeToImage(BufferedImage image, int width, int height) {
        double scale = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
        int targetW = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    public static byte[] resize(File file, int width, int height) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        resize(file, baos, width, height);
        return baos.toByteArray();
    }

    public static void resize(File file, OutputStream outputStream, int width, int height) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if(image == null) {
            throw new IOException("Could not read image from " + file.getName());
        }
        resize(image, outputStream, width, height);
    }

    public static InputStream resize(InputStream inputStream, int width, int height) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resize(inputStream, baos, width, height);
            return new ByteArrayInputStream(baos.toByteArray());
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void resize(InputStream inputStream, OutputStream outputStream, int width, int height) throws IOException {
        BufferedImage image = ImageIO.read(inputStream);
        if(image == null) {
            throw new IOException("Could not read image from stream");
        }
        resize(image, outputStream, width, height);
    }
}
