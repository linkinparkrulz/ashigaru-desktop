package com.sparrowwallet.nightjar.http;

import com.google.common.net.HostAndPort;
import com.samourai.http.client.HttpUsage;
import com.samourai.http.client.IHttpClientService;
import com.samourai.whirlpool.client.utils.ClientUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.Socks4Proxy;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("UnstableApiUsage")
public class JavaHttpClientService implements IHttpClientService {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String WHIRLPOOL_HTTPCLIENT = "Whirlpool-HttpClient";

    private final Map<HttpUsage, JavaHttpClient> httpClients;
    private HostAndPort torProxy;
    private long requestTimeout = 30000;

    public JavaHttpClientService(HostAndPort torProxy) {
        this.httpClients = new ConcurrentHashMap<>();
        this.torProxy = torProxy;
    }

    public JavaHttpClientService(HostAndPort torProxy, long requestTimeout) {
        this.httpClients = new ConcurrentHashMap<>();
        this.torProxy = torProxy;
        this.requestTimeout = requestTimeout;
    }

    public HostAndPort getTorProxy() {
        return torProxy;
    }

    public void setTorProxy(HostAndPort torProxy) {
        this.torProxy = torProxy;
    }

    @Override
    public JavaHttpClient getHttpClient(HttpUsage httpUsage) {
        JavaHttpClient httpClient = httpClients.get(httpUsage);
        if(httpClient == null) {
            if(log.isDebugEnabled()) {
                log.debug("+httpClient[" + httpUsage + "]");
            }
            httpClient = computeHttpClient(httpUsage);
            httpClients.put(httpUsage, httpClient);
        }
        return httpClient;
    }

    private JavaHttpClient computeHttpClient(HttpUsage httpUsage) {
        HttpClient httpClient = computeHttpClient(httpUsage, torProxy);
        return new JavaHttpClient(httpUsage, httpClient, requestTimeout);
    }

    public void changeIdentityRest() {
        for(JavaHttpClient httpClient : httpClients.values()) {
            // restart REST clients
            if(httpClient.getHttpUsage().isRest()) {
                httpClient.restart();
            }
        }
        // don't break non-REST connexions, it will be renewed on next connexion
    }

    public static HttpClient computeHttpClient(HttpUsage httpUsage, HostAndPort proxy) {
        return computeHttpClient(httpUsage, proxy, ClientUtils.USER_AGENT);
    }

    public static HttpClient computeHttpClient(HttpUsage httpUsage, HostAndPort proxy, String userAgent) {
        String name = WHIRLPOOL_HTTPCLIENT + "-" + httpUsage.toString();

        // we use jetty for proxy SOCKS support.
        // One selector thread: Jetty otherwise starts one per two CPU cores (5 on a 10-core Mac), and those threads are leased from
        // the pool below before any request runs. A handful of requests over Tor needs no more than one.
        HttpClient jettyHttpClient = new HttpClient(new HttpClientTransportOverHTTP(1), new SslContextFactory.Client());
        jettyHttpClient.setName(name);

        // Bound the Jetty thread pool: the default QueuedThreadPool creates min=8/max=200 threads
        // per HttpUsage per wallet, most of which sit idle over Tor. Threads are made only when needed
        // (min 1) and end after 30s idle; the max leaves room for the selector and reserved threads Jetty
        // leases up front, including the lease a failed start keeps across a restart (a cap of 6 failed
        // with "Insufficient configured threads" on machines with 6+ cores whenever Tor changed identity).
        QueuedThreadPool threadPool = new QueuedThreadPool(16, 1, 30_000);
        threadPool.setName(name);
        threadPool.setDaemon(true);
        jettyHttpClient.setExecutor(threadPool);
        jettyHttpClient.setScheduler(new ScheduledExecutorScheduler(name + "-scheduler", true));

        // prevent user-agent tracking
        jettyHttpClient.setUserAgentField(null);

        // proxy
        if(proxy != null) {
            ProxyConfiguration.Proxy jettyProxy = computeJettyProxy(proxy);
            jettyHttpClient.getProxyConfiguration().getProxies().add(jettyProxy);
        } else {
            if(log.isDebugEnabled()) {
                log.debug("+httpClient: no proxy");
            }
        }

        return jettyHttpClient;
    }

    public static ProxyConfiguration.Proxy computeJettyProxy(HostAndPort proxy) {
        return new Socks4Proxy(proxy.getHost(), proxy.getPort());
    }

    public void shutdown() {
        for(JavaHttpClient javaHttpClient : httpClients.values()) {
            javaHttpClient.stop();
        }

        httpClients.clear();
    }
}
