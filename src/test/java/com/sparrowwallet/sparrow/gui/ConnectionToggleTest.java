package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.gui.AshigaruMainController.ConnState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AD-34: going offline works from any state, and the pill only says Offline when the user chose it */
class ConnectionToggleTest {
    @Test
    void theMenuFollowsTheModeNotTheConnection() {
        assertEquals("Go offline", AshigaruMainController.toggleLabel(Mode.ONLINE), "online but still connecting, or after a failed connection");
        assertEquals("Go online", AshigaruMainController.toggleLabel(Mode.OFFLINE));
        assertEquals("Go online", AshigaruMainController.toggleLabel(null), "no mode chosen yet");
    }

    @Test
    void thePillSaysWhatIsTrue() {
        assertEquals("Online · not connected", AshigaruMainController.pillText(Mode.ONLINE, ConnState.DISCONNECTED));
        assertEquals("Offline", AshigaruMainController.pillText(Mode.OFFLINE, ConnState.DISCONNECTED));
        assertEquals("Connecting…", AshigaruMainController.pillText(Mode.ONLINE, ConnState.CONNECTING));
        assertEquals("Tor", AshigaruMainController.pillText(Mode.ONLINE, ConnState.CONNECTED));
    }
}
