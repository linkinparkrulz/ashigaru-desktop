package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.control.LifeHashIcon;
import com.sparrowwallet.sparrow.control.PayNymAvatar;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.Config;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;

import java.util.function.BooleanSupplier;

/**
 * The picture beside the open wallet's name. It is the wallet's PayNym robot, drawn by paynym.rs from the wallet's
 * payment code, with a picture drawn on this computer from the master fingerprint underneath until the robot arrives
 * (and for good if it never does: the fingerprint picture needs no network).
 *
 * <p>Fetching the robot tells paynym.rs which payment code is being asked about, so it is only ever done through Tor
 * or the configured proxy, never over the open internet; with neither, the fingerprint picture stays.
 */
final class WalletIdentityAvatar {
    /** A fingerprint of eight zeros is what a wallet imported from a bare key reports: there is none */
    private static final String NO_FINGERPRINT = "00000000";
    private static final double SIZE = 30;

    /** Whether a request may leave through a proxy. Replaced by tests, which have no Tor. */
    static BooleanSupplier proxyInUse = () -> Config.get().isUseProxy() || AppServices.isTorRunning();

    private final StackPane host;
    private PayNymAvatar payNymAvatar;
    private boolean robotShowing;
    private Tooltip tooltip;

    WalletIdentityAvatar(StackPane host) {
        this.host = host;
    }

    /** Whether the PayNym robot is what is showing, as opposed to the fingerprint picture */
    boolean showsRobot() {
        return robotShowing;
    }

    /** The control that fetches the robot, when a fetch was started; tests stand in for the network through it */
    PayNymAvatar payNymAvatar() {
        return payNymAvatar;
    }

    void clear() {
        host.getChildren().clear();
        if(tooltip != null) {
            Tooltip.uninstall(host, tooltip);
            tooltip = null;
        }
        payNymAvatar = null;
        robotShowing = false;
    }

    /**
     * @param fingerprint the master fingerprint, or null or empty when there is none
     * @param paymentCode the wallet's payment code, or null for a wallet that has none
     */
    void show(String fingerprint, PaymentCode paymentCode) {
        clear();

        boolean known = fingerprint != null && !fingerprint.isEmpty() && !fingerprint.equals(NO_FINGERPRINT);
        LifeHashIcon lifeHash = null;
        if(known) {
            lifeHash = new LifeHashIcon();
            lifeHash.setHex(fingerprint);
            host.getChildren().add(lifeHash);
            setTooltip("Drawn from this wallet's master fingerprint, " + fingerprint);
        } else {
            host.getChildren().add(Icons.of(FontAwesome5.Glyph.EYE, 13));
            setTooltip("Watch-only: this wallet has no fingerprint or payment code to draw");
        }

        if(paymentCode != null && proxyInUse.getAsBoolean()) {
            PayNymAvatar avatar = new PayNymAvatar();
            avatar.setPrefSize(SIZE, SIZE);
            avatar.setForceLoad(true);
            payNymAvatar = avatar;
            final LifeHashIcon underneath = lifeHash;
            //The control draws nothing until the image is there; then the robot takes the fingerprint picture's place
            avatar.getChildren().addListener((ListChangeListener<javafx.scene.Node>) change -> {
                if(!avatar.getChildren().isEmpty() && payNymAvatar == avatar && !robotShowing) {
                    robotShowing = true;
                    if(underneath != null) {
                        host.getChildren().remove(underneath);
                    }
                    setTooltip("This wallet's PayNym, from paynym.rs");
                }
            });
            host.getChildren().add(avatar);
            avatar.setPaymentCode(paymentCode);
        }
    }

    private void setTooltip(String text) {
        if(tooltip != null) {
            Tooltip.uninstall(host, tooltip);
        }
        tooltip = new Tooltip(text);
        Tooltip.install(host, tooltip);
    }
}
