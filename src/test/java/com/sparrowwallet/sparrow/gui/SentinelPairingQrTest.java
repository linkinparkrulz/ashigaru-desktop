package com.sparrowwallet.sparrow.gui;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A pairing QR is long: it has to come back out of the picture the dialog draws, as a phone camera would read it */
class SentinelPairingQrTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    private static String fullEnvelope() throws Exception {
        Wallet master = new Wallet("pair");
        master.setPolicyType(PolicyType.SINGLE);
        master.setScriptType(ScriptType.P2WPKH);
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39);
        master.getKeystores().add(Keystore.fromSeed(seed, ScriptType.P2WPKH.getDefaultDerivation()));
        master.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, master.getKeystores(), 1));
        for(StandardAccount account : new StandardAccount[] {StandardAccount.WHIRLPOOL_PREMIX, StandardAccount.WHIRLPOOL_POSTMIX, StandardAccount.WHIRLPOOL_BADBANK}) {
            master.addChildWallet(account);
        }
        Keystore open = new Keystore();
        open.setSeed(seed);
        return SentinelPairing.envelope(SentinelPairing.slots(master, open), SentinelPairing.newWords());
    }

    private static String decode(BufferedImage image) throws Exception {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        Result result = new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE));
        return result.getText();
    }

    @Test
    void theFullPairingComesBackOutOfTheQr() throws Exception {
        String envelope = fullEnvelope();
        BufferedImage qr = QrImages.dense(envelope, 460);
        assertNotNull(qr);
        assertEquals(envelope, decode(qr));
    }

    @Test
    void theQrIsSmallEnoughToScanOffAScreen() throws Exception {
        String envelope = fullEnvelope();
        int version = Encoder.encode(envelope, ErrorCorrectionLevel.L).getVersion().getVersionNumber();
        int modules = 17 + 4 * version + 4;
        //460 pixels across the picture, margin included: each module is at least this many pixels
        double pixelsPerModule = 460.0 / modules;
        System.out.println("pairing text " + envelope.length() + " characters, QR version " + version + ", " + modules + " modules, "
                + String.format("%.1f", pixelsPerModule) + " px per module at 460 px");
        assertTrue(version <= 40);
        assertTrue(pixelsPerModule >= 2.5, "modules of " + pixelsPerModule + " px would be hard to scan");
    }

    @Test
    void aPictureTwiceAsSmallStillReads() throws Exception {
        //A phone sees the screen smaller than it is: the same picture at half the size must still decode
        String envelope = fullEnvelope();
        BufferedImage qr = QrImages.dense(envelope, 920);
        BufferedImage half = new BufferedImage(460, 460, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = half.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(qr, 0, 0, 460, 460, null);
        g.dispose();
        try {
            assertEquals(envelope, decode(half));
        } catch(NotFoundException e) {
            fail("the QR did not decode at half size");
        }
    }

    @Test
    void theBrandedQrStillReadsForAShortText() throws Exception {
        String address = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
        BufferedImage qr = QrImages.branded(address, 200);
        assertNotNull(qr, "the logo may be missing in a test run, but the picture is still made");
        assertEquals(address, decode(qr));
    }
}
