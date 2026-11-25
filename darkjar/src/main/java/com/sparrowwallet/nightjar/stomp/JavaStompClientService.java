package com.sparrowwallet.nightjar.stomp;

import com.samourai.http.client.HttpUsage;
import com.samourai.stomp.client.IStompClient;
import com.samourai.stomp.client.IStompClientService;
import com.sparrowwallet.nightjar.http.JavaHttpClient;
import com.sparrowwallet.nightjar.http.JavaHttpClientService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class JavaStompClientService implements IStompClientService {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    private final JavaHttpClientService httpClientService;
    private ScheduledExecutorService heartbeatScheduler;

    public JavaStompClientService(JavaHttpClientService httpClientService) {
        this.httpClientService = httpClientService;
    }

    private synchronized ScheduledExecutorService getHeartbeatScheduler() {
        if (heartbeatScheduler == null || heartbeatScheduler.isShutdown()) {
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stomp-heartbeat-" + THREAD_COUNTER.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }
        return heartbeatScheduler;
    }

    @Override
    public IStompClient newStompClient() {
        JavaHttpClient httpClient = httpClientService.getHttpClient(HttpUsage.COORDINATOR_WEBSOCKET);
        return new JavaStompClient(httpClient, getHeartbeatScheduler());
    }

    public synchronized void shutdown() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }
}
