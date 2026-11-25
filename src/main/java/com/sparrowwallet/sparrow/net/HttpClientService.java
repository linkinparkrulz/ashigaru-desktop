package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.samourai.http.client.HttpUsage;
import com.samourai.http.client.IHttpClient;
import com.sparrowwallet.nightjar.http.JavaHttpClientService;
import io.reactivex.Observable;
import java.util.Optional;
import javafx.concurrent.Service;
import javafx.concurrent.Task;

import java.util.Map;

public class HttpClientService {
    private final JavaHttpClientService httpClientService;

    public HttpClientService(HostAndPort torProxy) {
        this.httpClientService = new JavaHttpClientService(torProxy, 120000);
    }

    public <T> T requestJson(String url, Class<T> responseType, Map<String, String> headers) throws Exception {
        IHttpClient httpClient = httpClientService.getHttpClient(HttpUsage.COORDINATOR_REST);
        return httpClient.getJson(url, responseType, headers);
    }

    /** A GET returning the body as text, through this service's proxy (SOCKS4a: the host name goes to the proxy, it is not looked up here) */
    public String requestString(String url, Map<String, String> headers) throws Exception {
        return requestJson(url, String.class, headers);
    }

    /** A GET returning the body as bytes, through this service's proxy */
    public byte[] requestBytes(String url, Map<String, String> headers) throws Exception {
        return httpClientService.getHttpClient(HttpUsage.COORDINATOR_REST).getBytes(url, headers);
    }

    public <T> Observable<Optional<T>> postJson(String url, Class<T> responseType, Map<String, String> headers, Object body) {
        IHttpClient httpClient = httpClientService.getHttpClient(HttpUsage.COORDINATOR_REST);
        return httpClient.postJson(url, responseType, headers, body);
    }

    public String postString(String url, Map<String, String> headers, String contentType, String content) throws Exception {
        IHttpClient httpClient = httpClientService.getHttpClient(HttpUsage.COORDINATOR_REST);
        return httpClient.postString(url, headers, contentType, content);
    }

    public void changeIdentity() {
        HostAndPort torProxy = getTorProxy();
        if(torProxy != null) {
            TorUtils.changeIdentity(torProxy);
        }
    }

    public HostAndPort getTorProxy() {
        return httpClientService.getTorProxy();
    }

    public void setTorProxy(HostAndPort torProxy) {
        //Ensure all http clients are shutdown first
        httpClientService.shutdown();
        httpClientService.setTorProxy(torProxy);
    }

    public void shutdown() {
        httpClientService.shutdown();
    }

    public static class ShutdownService extends Service<Boolean> {
        private final HttpClientService httpClientService;

        public ShutdownService(HttpClientService httpClientService) {
            this.httpClientService = httpClientService;
        }

        @Override
        protected Task<Boolean> createTask() {
            return new Task<>() {
                protected Boolean call() throws Exception {
                    httpClientService.shutdown();
                    return true;
                }
            };
        }
    }
}
