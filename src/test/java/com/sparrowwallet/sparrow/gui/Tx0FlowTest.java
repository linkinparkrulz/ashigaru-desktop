package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Tx0FlowTest {
    @BeforeAll
    static void startToolkit() {
        //Tx0Flow is a Canvas
        JavaFXTestSupport.ensureStarted();
    }

    @Test
    void everyAmountOnTheRibbonHasAllEightDecimals() {
        //From a tester's screenshot: the Anti-Sybil fee read 0.00125 beside 0.00277655 and 0.00001604
        assertEquals("0.00125000", Tx0Flow.amount(UnitFormat.DOT, 125_000));
        assertEquals("0.00277655", Tx0Flow.amount(UnitFormat.DOT, 277_655));
        assertEquals("0.00001604", Tx0Flow.amount(UnitFormat.DOT, 1_604));
        assertEquals("0.05000000", Tx0Flow.amount(UnitFormat.DOT, 5_000_000));
    }

    @Test
    void thePlainFormatStillDropsTrailingZerosSoItMustNotBeUsedHere() {
        assertEquals("0.00125", UnitFormat.DOT.formatBtcValue(125_000L));
    }
}
