package com.sparrowwallet.sparrow.whirlpool;

import com.google.common.net.HostAndPort;
import com.samourai.http.client.HttpUsage;
import com.sparrowwallet.nightjar.http.JavaHttpClientService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Whirlpool coordinator's HTTP client must start and restart whatever the machine's core count. Jetty leases its selector
 * threads from the client's bounded pool up front; with one per two cores, a 6-thread pool failed on 6+ core machines whenever
 * Tor changed identity ("Insufficient configured threads", seen as "Error fetching pools"). Jetty is internal to the darkjar
 * module, so its client is driven by reflection here.
 */
class WhirlpoolHttpClientThreadsTest {
    private static Object client() throws Exception {
        Method compute = JavaHttpClientService.class.getMethod("computeHttpClient", HttpUsage.class, HostAndPort.class);
        return compute.invoke(null, HttpUsage.COORDINATOR_REST, null);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    @Test
    void oneSelectorWhateverTheCoreCount() throws Exception {
        Object transport = call(client(), "getTransport");
        assertEquals(1, call(transport, "getSelectors"));
    }

    @Test
    void restartsAsATorIdentityChangeDoes() throws Exception {
        Object client = client();
        call(client, "start");
        try {
            for(int i = 0; i < 3; i++) {
                call(client, "stop");
                call(client, "start");
                assertEquals(Boolean.TRUE, call(client, "isRunning"));
            }
        } finally {
            call(client, "stop");
        }
    }
}
