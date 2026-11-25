package com.sparrowwallet.nightjar.stomp;

import com.samourai.http.client.HttpUsage;
import com.samourai.stomp.client.IStompClient;
import com.samourai.stomp.client.IStompClientService;
import com.sparrowwallet.nightjar.http.JavaHttpClient;
import com.sparrowwallet.nightjar.http.JavaHttpClientService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public class JavaStompClientService implements IStompClientService {
    private final JavaHttpClientService httpClientService;
    private ThreadPoolTaskScheduler taskScheduler;

    public JavaStompClientService(JavaHttpClientService httpClientService) {
        this.httpClientService = httpClientService;
    }

    private synchronized ThreadPoolTaskScheduler getTaskScheduler() {
        if(taskScheduler == null) {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("stomp-heartbeat");
            scheduler.setDaemon(true);
            scheduler.initialize();
            taskScheduler = scheduler;
        }
        return taskScheduler;
    }

    @Override
    public IStompClient newStompClient() {
        JavaHttpClient httpClient = httpClientService.getHttpClient(HttpUsage.COORDINATOR_WEBSOCKET);
        return new JavaStompClient(httpClient, getTaskScheduler());
    }

    public synchronized void shutdown() {
        if(taskScheduler != null) {
            taskScheduler.shutdown();
            taskScheduler = null;
        }
    }
}
