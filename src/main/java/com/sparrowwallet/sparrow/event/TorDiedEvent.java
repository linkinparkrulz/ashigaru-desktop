package com.sparrowwallet.sparrow.event;

/** The bundled Tor daemon stopped listening without having been asked to stop */
public class TorDiedEvent extends TorStatusEvent {
    public TorDiedEvent() {
        super("Tor stopped unexpectedly");
    }
}
