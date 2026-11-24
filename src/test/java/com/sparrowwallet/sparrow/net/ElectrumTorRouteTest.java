package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AD-32: a server on the internet that is not an onion goes through Tor, never directly */
class ElectrumTorRouteTest {
    @Test
    void aPublicClearnetServerNeedsTor() {
        assertTrue(ElectrumServer.needsTor(HostAndPort.fromParts("testnet.aranguren.org", 52001), false, true));
        assertTrue(ElectrumServer.needsTor(HostAndPort.fromParts("mempool.space", 40002), false, true));
    }

    @Test
    void onionsAndTheUsersOwnNetworkDoNot() {
        //Onions already go through Tor by their own transport; the user's own network is reached directly
        assertFalse(ElectrumServer.needsTor(HostAndPort.fromParts("mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion", 40002), false, true));
        assertFalse(ElectrumServer.needsTor(HostAndPort.fromParts("192.168.1.20", 50001), true, true));
    }

    @Test
    void theTransportsForAProxiedPublicServerConnectThroughTheProxy() throws Exception {
        //What getTransport builds for a public server once it needs Tor: TCP and SSL both carry the proxy
        HostAndPort server = HostAndPort.fromParts("testnet.aranguren.org", 52001);
        HostAndPort proxy = HostAndPort.fromParts("127.0.0.1", 9050);
        assertTrue(Protocol.TCP.getTransport(server, proxy) instanceof TcpTransport tcp && tcp.socketFactory instanceof ProxySocketFactory);
        assertTrue(Protocol.SSL.getTransport(server, proxy) instanceof ProxyTcpOverTlsTransport);
    }
}
