package com.sparrowwallet.sparrow.whirlpool;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers WhirlpoolServices.closeWallet(), the cleanup path used when a wallet is deleted from
 * Ashigaru's single-window UI. Neither Whirlpool engine here is ever started, so this only
 * exercises the "not started" shutdown branch and the Mix To unlink - it does not touch
 * Whirlpool.ShutdownService (a javafx.concurrent.Service) or any network/coordinator code.
 */
class WhirlpoolServicesTest {

    @BeforeAll
    static void setUpToolkit() {
        JavaFXTestSupport.ensureStarted();
    }

    @Test
    void closeWalletRemovesEngineAndUnlinksMixToWallet() throws Exception {
        WhirlpoolServices services = new WhirlpoolServices();

        // MAINNET keeps this cleanup-path test independent of which testnet is in use; the
        // network-to-server mapping itself is covered by WhirlpoolServerResolverTest.
        Whirlpool deletedWallet = new Whirlpool(Network.MAINNET, null);
        Whirlpool mixingIntoDeletedWallet = new Whirlpool(Network.MAINNET, null);
        // Seeding the mix-to link directly via reflection rather than calling the public
        // setMixToWallet(id, minMixes) - with a non-null id it resolves a live Wallet through
        // AppServices.get().getOpenWallets(), which isn't set up in this bare unit test. The
        // code path actually under test (closeWallet() -> setMixToWallet(null, null)) doesn't
        // touch AppServices at all, so this is only about arranging the fixture.
        setMixToWalletId(mixingIntoDeletedWallet, "deleted-wallet-id");

        putWhirlpool(services, "deleted-wallet-id", deletedWallet);
        putWhirlpool(services, "other-wallet-id", mixingIntoDeletedWallet);

        // closeWallet() itself does Platform.runLater(...); calling it from the test thread and
        // then flushing the FX queue (rather than nesting it inside runAndWait) avoids racing
        // ahead of its own asynchronous body.
        services.closeWallet("deleted-wallet-id");
        JavaFXTestSupport.runAndWait(() -> {});

        assertNull(getWhirlpoolMap(services).get("deleted-wallet-id"),
                "deleted wallet's Whirlpool engine should be removed from the map");
        assertNull(mixingIntoDeletedWallet.getMixToWalletId(),
                "a wallet mixing into the deleted wallet should have its Mix To link cleared");

        // Cleanup: unregister the still-live engine so it doesn't linger on WhirlpoolEventService
        // for the rest of the test JVM.
        services.closeWallet("other-wallet-id");
        JavaFXTestSupport.runAndWait(() -> {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Whirlpool> getWhirlpoolMap(WhirlpoolServices services) throws Exception {
        Field field = WhirlpoolServices.class.getDeclaredField("whirlpoolMap");
        field.setAccessible(true);
        return (Map<String, Whirlpool>) field.get(services);
    }

    private static void putWhirlpool(WhirlpoolServices services, String walletId, Whirlpool whirlpool) throws Exception {
        getWhirlpoolMap(services).put(walletId, whirlpool);
    }

    private static void setMixToWalletId(Whirlpool whirlpool, String mixToWalletId) throws Exception {
        Field field = Whirlpool.class.getDeclaredField("mixToWalletId");
        field.setAccessible(true);
        field.set(whirlpool, mixToWalletId);
    }
}
