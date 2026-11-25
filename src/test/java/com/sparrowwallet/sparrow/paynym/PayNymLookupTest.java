package com.sparrowwallet.sparrow.paynym;

import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** A PayNym lookup ends its own request: at its client timeout, and when the caller stops waiting */
class PayNymLookupTest {
    private static final String URL = "http://paynym25chftmsywv4v2r67agbrr62lcxagsf4tymbzpeeucucy2ivad.onion/api/v1/nym?compact=true";

    /** A proxy that accepts connections and never answers */
    private static Proxy proxy(ServerSocket server) {
        return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", server.getLocalPort()));
    }

    @Test
    void aDirectoryThatNeverAnswersFailsAtTheClientTimeout() throws Exception {
        try(ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            long start = System.nanoTime();
            Throwable error = PayNymService.postDirect(proxy(server), URL, "{\"nym\":\"+a\"}", 500).ignoreElements().blockingGet();
            assertTrue(error instanceof IOException, String.valueOf(error));
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5, "ended at the client timeout");
        }
    }

    @Test
    void stoppingTheWaitClosesTheConnection() throws Exception {
        try(ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            AtomicReference<Socket> accepted = new AtomicReference<>();
            CountDownLatch connected = new CountDownLatch(1);
            Thread acceptor = new Thread(() -> {
                try {
                    accepted.set(server.accept());
                    connected.countDown();
                } catch(IOException e) {
                    //closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            Disposable call = PayNymService.postDirect(proxy(server), URL, "{\"nym\":\"+a\"}", 30_000).subscribeOn(Schedulers.io())
                    .subscribe(map -> fail("no answer was sent"), error -> fail("nobody is waiting for an error"));
            assertTrue(connected.await(10, TimeUnit.SECONDS), "the call connected");
            long start = System.nanoTime();
            call.dispose();

            Socket client = accepted.get();
            client.setSoTimeout(5_000);
            //The SOCKS greeting may arrive first; then the close
            int read;
            while((read = client.getInputStream().read()) != -1) {
                //skip the greeting
            }
            assertEquals(-1, read);
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5, "closed well before the 30 s timeout");
            client.close();
        }
    }
}
