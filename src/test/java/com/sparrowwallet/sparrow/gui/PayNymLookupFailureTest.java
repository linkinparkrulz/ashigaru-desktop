package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** The verifier's PayNym lookup says plainly when the directory is down, and what to do instead */
class PayNymLookupFailureTest {
    @Test
    void aDirectoryThatIsDownSlowOrBrokenIsSaidPlainly() {
        for(Throwable error : new Throwable[] {
                new TimeoutException(),
                new IOException("Http query failed: status=502"),
                new RuntimeException(new ConnectException("Connection refused")),
                new IOException("Connection reset"),
                new RuntimeException()}) {
            assertEquals(Bip47MessageVerifierController.DIRECTORY_DOWN, Bip47MessageVerifierController.lookupFailure("+someone", error), String.valueOf(error));
        }
        assertTrue(Bip47MessageVerifierController.DIRECTORY_DOWN.contains("isn't working right now"));
        assertTrue(Bip47MessageVerifierController.DIRECTORY_DOWN.contains("paste the payment code yourself"));
    }

    @Test
    void anUnknownHandleAndBeingOfflineAreTheirOwnMessages() {
        String unknown = Bip47MessageVerifierController.lookupFailure("+nobody", new IOException("Http query failed: status=404"));
        assertTrue(unknown.startsWith("No PayNym called +nobody"), unknown);

        String offline = Bip47MessageVerifierController.lookupFailure("+someone",
                new RuntimeException(new IOException("PayNym directory not contacted: Ashigaru is in offline mode.")));
        assertEquals("Could not look up +someone: Ashigaru is in offline mode.", offline);
    }

    @Test
    void theLookupGivesUpLongBeforeTheHttpClientWould() {
        assertTrue(Bip47MessageVerifierController.LOOKUP_TIMEOUT_SECONDS <= 30);
    }
}
