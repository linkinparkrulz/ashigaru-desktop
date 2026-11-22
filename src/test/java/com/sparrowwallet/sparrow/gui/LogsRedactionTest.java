package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What a shared log must not carry: a Monero address ties a person to a BTC lock amount that anyone can find on chain */
class LogsRedactionTest {
    //Shaped like a mainnet Monero address (95 characters starting with 4) but not a real one
    private static final String XMR = "4q2Dkq6ozw8yQ3t9g3QygMp3xMYfnw6f2mr16unD8uzXkyQBS7As25AhCje1UdemiPgTYu21LugRY98q6ZvjEKvdgq2mtVY";
    private static final String SUBADDRESS = "8" + XMR.substring(1);
    private static final String INTEGRATED = XMR + "Rg5Mx9qKbWn";

    @Test
    void moneroAddressesAreRedacted() {
        assertEquals(95, XMR.length());
        assertEquals(106, INTEGRATED.length());
        for(String address : new String[] {XMR, SUBADDRESS, INTEGRATED}) {
            String line = "Swap abc started: 0.04998760 BTC to " + address + ", network fee 1240 sats";
            String redacted = LogsController.redact(line, List.of());
            assertFalse(redacted.contains(address.substring(0, 20)), redacted);
            assertTrue(redacted.contains("[xmr address]"), redacted);
            assertTrue(redacted.contains("0.04998760 BTC") && redacted.contains("network fee 1240 sats"), "the rest of the line stays: " + redacted);
        }
    }

    @Test
    void theWholeIntegratedAddressGoesNotJustItsFirstPart() {
        assertEquals("to [xmr address].", LogsController.redact("to " + INTEGRATED + ".", List.of()));
    }

    @Test
    void ordinaryTextAndOtherPatternsAreUnchanged() {
        assertEquals("Swap engine running with 2 public Monero nodes", LogsController.redact("Swap engine running with 2 public Monero nodes", List.of()));
        assertEquals("a [address] b", LogsController.redact("a bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq b", List.of()));
        //A 95 character word that does not start with 4 or 8 is not an address
        assertTrue(LogsController.redact("x" + XMR.substring(1), List.of()).startsWith("x"));
    }
}
