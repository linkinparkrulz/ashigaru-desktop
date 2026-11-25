package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.sparrow.control.LifeHashIcon;
import com.sparrowwallet.sparrow.control.PayNymAvatar;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The picture beside the wallet name: the PayNym robot when it can be asked for (through a proxy, with a payment
 * code), the fingerprint picture otherwise and until the robot arrives. The network is stood in for by adding to the
 * robot control what it adds when an image has loaded.
 */
class WalletIdentityAvatarTest {
    private static final String FINGERPRINT = "73c5da0a";
    //BIP47's own test vector: a valid payment code
    private static final PaymentCode CODE = PaymentCode.fromString("PM8TJS2JxQ5ztXUpBBRnpTbcUXbUHy2T1abfrb3KkAAtMEGNbey4oumH7Hc578WgQJhPjBxteQ5GHHToTYHE3A1w6p7tU6KSoFmWBVbFGjKPisZDbP97");

    @BeforeAll
    static void startToolkit() throws Exception {
        JavaFXTestSupport.ensureStarted();
        javafx.application.Platform.setImplicitExit(false);
        onFx(() -> org.controlsfx.glyphfont.GlyphFontRegistry.register(new com.sparrowwallet.sparrow.glyphfont.FontAwesome5()));
    }

    @AfterEach
    void restoreProxy() {
        WalletIdentityAvatar.proxyInUse = () -> false;
    }

    private static void onFx(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        JavaFXTestSupport.runAndWait(() -> {
            try {
                action.run();
            } catch(Throwable t) {
                failure.set(t);
            }
        });
        if(failure.get() != null) {
            if(failure.get() instanceof AssertionError error) {
                throw error;
            }
            throw new AssertionError(failure.get());
        }
    }

    private static boolean has(StackPane host, Class<? extends Node> type) {
        return host.getChildren().stream().anyMatch(type::isInstance);
    }

    @Test
    void aWalletWithoutAPaymentCodeShowsTheFingerprintPicture() throws Exception {
        onFx(() -> {
            WalletIdentityAvatar.proxyInUse = () -> true;
            StackPane host = new StackPane();
            WalletIdentityAvatar avatar = new WalletIdentityAvatar(host);
            avatar.show(FINGERPRINT, null);
            assertTrue(has(host, LifeHashIcon.class));
            assertFalse(has(host, PayNymAvatar.class));
            assertNull(avatar.payNymAvatar());
            assertFalse(avatar.showsRobot());
        });
    }

    @Test
    void withNoProxyNothingIsAskedForAndTheFingerprintPictureStays() throws Exception {
        onFx(() -> {
            WalletIdentityAvatar.proxyInUse = () -> false;
            StackPane host = new StackPane();
            WalletIdentityAvatar avatar = new WalletIdentityAvatar(host);
            avatar.show(FINGERPRINT, CODE);
            assertTrue(has(host, LifeHashIcon.class));
            assertFalse(has(host, PayNymAvatar.class), "no control, so no request to paynym.rs over the open internet");
            assertNull(avatar.payNymAvatar());
        });
    }

    @Test
    void withAProxyTheRobotIsRequestedAndTheFingerprintPictureShowsUntilItArrives() throws Exception {
        onFx(() -> {
            WalletIdentityAvatar.proxyInUse = () -> true;
            StackPane host = new StackPane();
            WalletIdentityAvatar avatar = new WalletIdentityAvatar(host);
            avatar.show(FINGERPRINT, CODE);
            assertTrue(has(host, LifeHashIcon.class));
            assertTrue(has(host, PayNymAvatar.class));
            assertEquals(CODE.toString(), avatar.payNymAvatar().getPaymentCode().toString());
            assertFalse(avatar.showsRobot());
        });
    }

    @Test
    void whenTheRobotArrivesItTakesTheFingerprintPicturesPlace() throws Exception {
        onFx(() -> {
            WalletIdentityAvatar.proxyInUse = () -> true;
            StackPane host = new StackPane();
            WalletIdentityAvatar avatar = new WalletIdentityAvatar(host);
            avatar.show(FINGERPRINT, CODE);
            //What the control does once the image has loaded
            avatar.payNymAvatar().getChildren().add(new Circle(15, Color.ORANGE));
            assertTrue(avatar.showsRobot());
            assertFalse(has(host, LifeHashIcon.class));
            assertTrue(has(host, PayNymAvatar.class));
        });
    }

    @Test
    void showingAnotherWalletForgetsTheFirst() throws Exception {
        onFx(() -> {
            WalletIdentityAvatar.proxyInUse = () -> true;
            StackPane host = new StackPane();
            WalletIdentityAvatar avatar = new WalletIdentityAvatar(host);
            avatar.show(FINGERPRINT, CODE);
            PayNymAvatar first = avatar.payNymAvatar();
            avatar.show("00112233", null);
            assertFalse(has(host, PayNymAvatar.class));
            assertEquals(1, host.getChildren().size());
            //A late image for the first wallet must not change the second one's picture
            first.getChildren().add(new Circle(15));
            assertFalse(avatar.showsRobot());
            assertTrue(has(host, LifeHashIcon.class));
        });
    }

    @Test
    void aWalletWithNoFingerprintGetsAPlaceholderNotAPictureOfZeros() throws Exception {
        onFx(() -> {
            WalletIdentityAvatar avatar;
            StackPane host = new StackPane();
            avatar = new WalletIdentityAvatar(host);
            for(String none : new String[] {null, "", "00000000"}) {
                avatar.show(none, null);
                assertFalse(has(host, LifeHashIcon.class), "'" + none + "'");
                assertEquals(1, host.getChildren().size());
            }
        });
    }

    @Test
    void clearingLeavesNothing() throws Exception {
        onFx(() -> {
            WalletIdentityAvatar.proxyInUse = () -> true;
            StackPane host = new StackPane();
            WalletIdentityAvatar avatar = new WalletIdentityAvatar(host);
            avatar.show(FINGERPRINT, CODE);
            avatar.clear();
            assertTrue(host.getChildren().isEmpty());
            assertFalse(avatar.showsRobot());
        });
    }
}
