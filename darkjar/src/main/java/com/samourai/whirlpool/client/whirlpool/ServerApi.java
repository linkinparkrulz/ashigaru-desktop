package com.samourai.whirlpool.client.whirlpool;

import com.samourai.http.client.HttpUsage;
import com.samourai.http.client.IHttpClient;
import com.samourai.http.client.IHttpClientService;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.rest.*;
import io.reactivex.Observable;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Whirlpool server API */
public class ServerApi {
  private Logger log = LoggerFactory.getLogger(ServerApi.class);

  private String urlServer;
  private String urlServerOutputReg;
  private IHttpClient httpClientRest;
  private IHttpClient httpClientRegOutput;

  public ServerApi(String urlServer, String urlServerOutputReg, IHttpClientService httpClientService) {
    this(
        urlServer,
        urlServerOutputReg,
        httpClientService.getHttpClient(HttpUsage.COORDINATOR_REST),
        httpClientService.getHttpClient(HttpUsage.COORDINATOR_REGISTER_OUTPUT));
  }

  public ServerApi(String urlServer, String urlServerOutputReg, IHttpClient httpClientRest, IHttpClient httpClientRegOutput) {
    this.urlServer = urlServer;
    this.urlServerOutputReg = urlServerOutputReg;
    this.httpClientRest = httpClientRest;
    this.httpClientRegOutput = httpClientRegOutput;
  }

  public PoolsResponse fetchPools() throws Exception {
    String url = WhirlpoolProtocol.getUrlFetchPools(urlServer);
    if (log.isDebugEnabled()) {
      log.debug("fetchPools: " + url);
    }
    httpClientRest.connect();
    PoolsResponse poolsResponse = httpClientRest.getJson(url, PoolsResponse.class, null);
    return poolsResponse;
  }

  public Observable<Optional<Tx0DataResponseV2>> fetchTx0Data(Tx0DataRequestV2 tx0DataRequest)
      throws Exception {
    String url = WhirlpoolProtocol.getUrlTx0Data(urlServer);
    if (log.isDebugEnabled()) {
      log.debug("POST " + url + ": " + ClientUtils.toJsonString(tx0DataRequest));
    }
    Observable<Optional<Tx0DataResponseV2>> tx0DataResponse =
        httpClientRest.postJson(url, Tx0DataResponseV2.class, null, tx0DataRequest);
    return tx0DataResponse;
  }

  public String getWsUrlConnect() {
    return WhirlpoolProtocol.getUrlConnect(urlServer);
  }

  public Observable<Optional<String>> checkOutput(CheckOutputRequest checkOutputRequest)
      throws Exception {
    // POST request through a different identity for mix privacy
    httpClientRegOutput.connect();

    String checkOutputUrl = WhirlpoolProtocol.getUrlCheckOutput(urlServer);
    if (log.isDebugEnabled()) {
      log.debug("POST " + checkOutputUrl + ": " + ClientUtils.toJsonString(checkOutputRequest));
    }
    Observable<Optional<String>> observable =
        httpClientRegOutput.postJson(checkOutputUrl, String.class, null, checkOutputRequest);
    return observable;
  }

  public Observable<Optional<String>> registerOutput(RegisterOutputRequest registerOutputRequest)
      throws Exception {
    // POST request through a different identity for mix privacy
    httpClientRegOutput.connect();

    String registerOutputUrl = WhirlpoolProtocol.getUrlRegisterOutput(urlServerOutputReg);
    if (log.isDebugEnabled()) {
      log.debug(
          "POST " + registerOutputUrl + ": " + ClientUtils.toJsonString(registerOutputRequest));
    }
    Observable<Optional<String>> observable =
        httpClientRegOutput.postJson(registerOutputUrl, String.class, null, registerOutputRequest);
    return observable;
  }

  public Observable<Optional<String>> tx0Notify(Tx0NotifyRequest tx0NotifyRequest)
      throws Exception {
    httpClientRest.connect();

    String url = WhirlpoolProtocol.getUrlTx0Notify(urlServer);
    if (log.isDebugEnabled()) {
      log.debug("POST " + url + ": " + ClientUtils.toJsonString(tx0NotifyRequest));
    }
    Observable<Optional<String>> observable =
        httpClientRest.postJson(url, String.class, null, tx0NotifyRequest);
    return observable;
  }

  public String toString() {
    return "urlServer=" + urlServer;
  }
}
