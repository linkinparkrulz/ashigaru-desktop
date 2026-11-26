package com.sparrowwallet.sparrow.io;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ServerTest {
    private static final String ONION = "dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion";

    @Test
    public void aNicknameIsTheShortName() {
        assertEquals("My node", new Server("tcp://" + ONION + ":50001", "My node").getShortDisplayName());
    }

    @Test
    public void anOnionWithoutNicknameIsShortened() {
        Server server = new Server("tcp://" + ONION + ":50001");
        assertEquals("dojobaye…dnu2yd.onion", server.getShortDisplayName());
        //The full address stays available, for the tooltip and the dialogs
        assertEquals("tcp://" + ONION + ":50001", server.getDisplayName());
    }

    @Test
    public void anotherHostIsShownWithoutProtocolOrPort() {
        assertEquals("electrum.example.org", new Server("ssl://electrum.example.org:50002").getShortDisplayName());
        assertEquals("127.0.0.1", new Server("tcp://127.0.0.1:50001").getShortDisplayName());
    }

    @Test
    public void theRetiredSuffixIsTakenOffSavedNames() {
        Server saved = Server.fromString("tcp://" + ONION + ":50001|yellow (+91xTx93x3) (unverified endpoint)");
        assertEquals("yellow (+91xTx93x3)", saved.getAlias());
        assertEquals("tcp://" + ONION + ":50001|yellow (+91xTx93x3)", saved.toString());
    }
}
