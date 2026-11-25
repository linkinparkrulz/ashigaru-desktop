package com.sparrowwallet.sparrow.gui;

import io.reactivex.exceptions.UndeliverableException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class UndeliverableErrorsTest {
    @Test
    void aLateNetworkErrorIsExpected() {
        assertTrue(UndeliverableErrors.isExpected(new UndeliverableException(new SocketTimeoutException("Read timed out"))));
        assertTrue(UndeliverableErrors.isExpected(new UndeliverableException(new IOException("Socket closed"))));
        assertTrue(UndeliverableErrors.isExpected(new UndeliverableException(new InterruptedException())));
    }

    @Test
    void anythingElseIsStillAnError() {
        assertFalse(UndeliverableErrors.isExpected(new UndeliverableException(new NullPointerException())));
        assertFalse(UndeliverableErrors.isExpected(new IOException("not undeliverable")));
        assertFalse(UndeliverableErrors.isExpected(new IllegalStateException()));
    }
}
