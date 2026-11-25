package com.sparrowwallet.drongo.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogRedactionTest {
    private static final String TXID = "0ad8c9aa5d7e11b4f0c2a9e3b6d85f4c1a7e90b23d4c5f6a7b8c9d0e1f2a3b4c";

    @Test
    void swapAmountsAreRedactedInTheShapesTheEngineAndServiceWriteThem() {
        assertEquals("SwapSetupInflight: {\"btc_lock_amount\":[amount],\"btc_network_fee\":[amount]}",
                LogRedaction.redactAmounts("SwapSetupInflight: {\"btc_lock_amount\":373999,\"btc_network_fee\":1200}"));
        assertEquals("BobState { btc_amount: [amount], tx_lock_fee=[amount] }", LogRedaction.redactAmounts("BobState { btc_amount: 373999, tx_lock_fee=1200 }"));
        assertEquals("lock of [amount] is not [amount]", LogRedaction.redactAmounts("lock of Amount(373999) is not Amount(374000)"));
        assertEquals("this coin swaps [amount] now; the maker takes [amount] to [amount]",
                LogRedaction.redactAmounts("this coin swaps 0.00373999 BTC now; the maker takes 100000 sats to 1.5 XMR"));
        //A fee rate is not an amount, and neither is a version or a count
        assertEquals("fee rate 12 sat/vB, maker v3.1.0, 2 swaps", LogRedaction.redactAmounts("fee rate 12 sat/vB, maker v3.1.0, 2 swaps"));
        //And everything the general redaction covers
        assertEquals("lock [txid]", LogRedaction.redactAmounts("lock " + TXID));
    }

    @Test
    void theRestOfTheAppKeepsItsNumbers() {
        assertEquals("Fetched 3 blocks, fee 12, total 0.5 BTC", LogRedaction.redact("Fetched 3 blocks, fee 12, total 0.5 BTC"));
    }

    @Test
    void theAppenderRedactsMessagesAndStackTracesAndSwapAmountsOnlyInSwapLines() {
        LoggerContext context = new LoggerContext();
        RedactingMessageConverter message = new RedactingMessageConverter();
        RedactingThrowableConverter throwable = new RedactingThrowableConverter();
        throwable.setContext(context);
        throwable.start();

        Exception failure = new IllegalStateException("lock " + TXID + " pays 373999 sats", new RuntimeException("btc_lock_amount: 373999"));
        LoggingEvent swap = new LoggingEvent("x", context.getLogger("com.sparrowwallet.sparrow.swap.SwapService"), Level.ERROR,
                "Swap engine call failed for {}", failure, new Object[] {"\"btc_lock_amount\":373999"});
        assertEquals("Swap engine call failed for \"btc_lock_amount\":[amount]", message.convert(swap));
        String trace = throwable.convert(swap);
        assertTrue(trace.contains("lock [txid] pays [amount]"), trace);
        assertTrue(trace.contains("btc_lock_amount: [amount]"), trace);
        assertFalse(trace.contains("373999") || trace.contains(TXID), trace);
        assertTrue(trace.contains("at com.sparrowwallet.drongo.log.LogRedactionTest"), "the frames stay");

        LoggingEvent wallet = new LoggingEvent("x", context.getLogger("com.sparrowwallet.sparrow.wallet.Wallet"), Level.INFO,
                "Sent 0.5 BTC in " + TXID, null, null);
        assertEquals("Sent 0.5 BTC in [txid]", message.convert(wallet));
        assertFalse(RedactingMessageConverter.isSwapLogger("com.sparrowwallet.sparrow.swapper"));
    }
}
