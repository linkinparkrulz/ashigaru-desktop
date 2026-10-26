package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SwapProgressTest {
    private static final Instant T0 = Instant.parse("2026-10-26T18:13:29Z");

    private static JsonObject event(String type, String content) {
        return JsonParser.parseString("{\"channelName\":\"SwapProgress\",\"event\":{\"event\":{\"type\":\"" + type + "\""
                + (content == null ? "" : ",\"content\":" + content) + "},\"swap_id\":\"12e8facc-8dc4-4184-bfa9-38ee87333dc2\"}}").getAsJsonObject();
    }

    @Test
    void readsTheEventFromARealSwap() {
        //Exactly as it appeared in the first mainnet swap's log
        JsonObject payload = JsonParser.parseString("{\"channelName\":\"SwapProgress\",\"event\":{\"event\":{\"type\":\"CheckingMoneroNodeConnectivity\"},"
                + "\"swap_id\":\"12e8facc-8dc4-4184-bfa9-38ee87333dc2\"}}").getAsJsonObject();
        assertEquals("12e8facc-8dc4-4184-bfa9-38ee87333dc2", SwapProgress.swapId(payload));
        SwapProgress.Status status = SwapProgress.read(payload, null, T0);
        assertEquals(SwapProgress.Step.NODES, status.step());
        assertFalse(status.finished());
    }

    @Test
    void aStepKeepsItsStartWhenRepeatedAndFlagsWhenSlow() {
        SwapProgress.Status first = SwapProgress.read(event("CheckingMoneroNodeConnectivity", null), null, T0);
        SwapProgress.Status again = SwapProgress.read(event("Resuming", null), first, T0.plusSeconds(30));
        assertEquals(T0, again.since());
        assertFalse(again.isSlow(T0.plus(Duration.ofMinutes(1))));
        assertTrue(again.isSlow(T0.plus(Duration.ofMinutes(3))));

        SwapProgress.Status next = SwapProgress.read(event("SwapSetupInflight", "{\"btc_lock_amount\":373999}"), again, T0.plusSeconds(60));
        assertEquals(SwapProgress.Step.TERMS, next.step());
        assertEquals(T0.plusSeconds(60), next.since());
    }

    @Test
    void confirmationsAreShown() {
        SwapProgress.Status xmr = SwapProgress.read(event("XmrLockTxInMempool",
                "{\"xmr_lock_txid\":\"ab\",\"xmr_lock_tx_confirmations\":3,\"xmr_lock_tx_target_confirmations\":10}"), null, T0);
        assertEquals(SwapProgress.Step.MAKER_CONFIRM, xmr.step());
        assertEquals("3 of 10 confirmations", xmr.detail());

        SwapProgress.Status btc = SwapProgress.read(event("BtcLockTxInMempool",
                "{\"btc_lock_txid\":\"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff\",\"btc_lock_confirmations\":null}"), null, T0);
        assertEquals(SwapProgress.Step.LOCK_CONFIRM, btc.step());
        assertTrue(btc.detail().startsWith("0 confirmations"));
    }

    @Test
    void endsAreFinished() {
        SwapProgress.Status done = SwapProgress.read(event("XmrRedeemed", "{\"xmr_redeem_txids\":[\"cafe\"],\"xmr_receive_pool\":[]}"), null, T0);
        assertEquals(SwapProgress.Step.DONE, done.step());
        assertTrue(done.finished());

        SwapProgress.Status refunded = SwapProgress.read(event("BtcRefunded", "{\"btc_refund_txid\":\"beef\"}"), null, T0);
        assertEquals(SwapProgress.Step.REFUNDED, refunded.step());
        assertTrue(refunded.finished());
    }

    @Test
    void aStopSaysWhetherAnythingWasLocked() {
        SwapProgress.Status checking = SwapProgress.read(event("CheckingMoneroNodeConnectivity", null), null, T0);
        JsonObject stop = JsonParser.parseString("{\"swapId\":\"x\",\"error\":\"Monero daemon RPC health check failed\"}").getAsJsonObject();
        SwapProgress.Status stopped = SwapProgress.stopped(stop, checking, T0.plusSeconds(90));
        assertEquals(SwapProgress.Step.STOPPED, stopped.step());
        assertTrue(stopped.detail().contains("health check failed"));
        assertTrue(stopped.detail().contains("Nothing was locked"));

        SwapProgress.Status locked = SwapProgress.read(event("BtcLockTxInMempool", "{\"btc_lock_txid\":\"aa\",\"btc_lock_confirmations\":1}"), null, T0);
        assertTrue(SwapProgress.stopped(stop, locked, T0).detail().contains("refunded to Badbank after the timelock"));
    }

    @Test
    void aMakerThatDoesNotAnswerIsExplained() {
        String reason = SwapProgress.stopReason("Failed to setup swap after retries: Failed to read spot price response from Alice", null);
        assertTrue(reason.startsWith("The maker did not answer the swap request"));
        assertTrue(reason.contains("Failed to read spot price response"));
        assertTrue(reason.contains("Nothing was locked"));
    }

    @Test
    void unknownEventsKeepTheStep() {
        SwapProgress.Status terms = SwapProgress.read(event("SwapSetupInflight", "{}"), null, T0);
        SwapProgress.Status unknown = SwapProgress.read(event("SomethingNew", null), terms, T0.plusSeconds(5));
        assertEquals(SwapProgress.Step.TERMS, unknown.step());
        assertEquals("SomethingNew", unknown.detail());
    }
}
