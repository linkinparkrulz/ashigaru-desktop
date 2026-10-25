package com.sparrowwallet.sparrow.swap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SwapServiceTest {
    private static SwapService newService() throws Exception {
        //Not get(): that registers the singleton for Tor events
        Constructor<SwapService> constructor = SwapService.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object field(SwapService service, String name) throws Exception {
        Field field = SwapService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private static void set(SwapService service, String name, Object value) throws Exception {
        Field field = SwapService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockingAWalletWipesItsIdentity() throws Exception {
        SwapService service = newService();
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte)7);
        ((Map<String, byte[]>)field(service, "seeds")).put("wallet", seed);
        assertTrue(service.hasIdentity("wallet"));

        service.walletUnloaded("wallet");
        assertFalse(service.hasIdentity("wallet"));
        assertArrayEquals(new byte[32], seed);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotSwitchWalletsWhileASwapRuns() throws Exception {
        SwapService service = newService();
        set(service, "engineWalletId", "first");
        set(service, "runningWalletId", "first");
        ((Set<String>)field(service, "runningSwaps")).add("swap-1");

        String refusal = service.ensureRunningFor("second");
        assertNotNull(refusal);
        assertTrue(refusal.contains("A swap is running"));
        assertEquals("first", field(service, "engineWalletId"));
        //The wallet it runs for is fine
        assertNull(service.ensureRunningFor("first"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockingTheSwappingWalletKeepsTheEngineForItsSwap() throws Exception {
        SwapService service = newService();
        set(service, "engineWalletId", "first");
        set(service, "runningWalletId", "first");
        ((Set<String>)field(service, "runningSwaps")).add("swap-1");

        service.walletUnloaded("first");
        assertEquals("first", field(service, "runningWalletId"));

        ((Set<String>)field(service, "runningSwaps")).clear();
        service.walletUnloaded("first");
        assertNull(field(service, "runningWalletId"));
    }

    @Test
    void moneroNodeMustBeAnOnionWithAPort() {
        assertTrue(SwapService.isOnionHostAndPort("abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion:18081"));
        assertFalse(SwapService.isOnionHostAndPort("node.example.com:18081"));
        assertFalse(SwapService.isOnionHostAndPort("abc.onion"));
        assertFalse(SwapService.isOnionHostAndPort("not a host"));
    }
}
