package com.samourai.whirlpool.client.whirlpool;

import com.samourai.http.client.HttpUsage;
import com.samourai.http.client.IHttpClient;
import com.samourai.http.client.IHttpClientService;
import com.samourai.stomp.client.IStompClientService;
import com.samourai.tor.client.TorClientService;
import com.samourai.whirlpool.client.wallet.beans.ExternalDestination;
import com.samourai.whirlpool.client.wallet.beans.IndexRange;
import org.bitcoinj.core.NetworkParameters;

public class WhirlpoolClientConfig {
  private IHttpClientService httpClientService;
  private IStompClientService stompClientService;
  private TorClientService torClientService;
  private ServerApi serverApi;
  private ExternalDestination externalDestination;
  private NetworkParameters networkParameters;
  private IndexRange indexRangePostmix;
  // PEM file overriding the bundled blind signature public key, for a coordinator with its own key
  private String blindSignaturePublicKeyFile;

  public WhirlpoolClientConfig(
      IHttpClientService httpClientService,
      IStompClientService stompClientService,
      TorClientService torClientService,
      ServerApi serverApi,
      ExternalDestination externalDestination,
      NetworkParameters networkParameters,
      IndexRange indexRangePostmix) {
    this.httpClientService = httpClientService;
    this.stompClientService = stompClientService;
    this.torClientService = torClientService;
    this.serverApi = serverApi;
    this.externalDestination = externalDestination;
    this.networkParameters = networkParameters;
    this.indexRangePostmix = indexRangePostmix;
  }

  public IHttpClientService getHttpClientService() {
    return httpClientService;
  }

  public IHttpClient getHttpClient(HttpUsage httpUsage) {
    return httpClientService.getHttpClient(httpUsage);
  }

  public IStompClientService getStompClientService() {
    return stompClientService;
  }

  public TorClientService getTorClientService() {
    return torClientService;
  }

  public ServerApi getServerApi() {
    return serverApi;
  }

  public void setServerApi(ServerApi serverApi) {
    this.serverApi = serverApi;
  }

  public ExternalDestination getExternalDestination() {
    return externalDestination;
  }

  public void setExternalDestination(ExternalDestination externalDestination) {
    this.externalDestination = externalDestination;
  }

  public NetworkParameters getNetworkParameters() {
    return networkParameters;
  }

  public void setNetworkParameters(NetworkParameters networkParameters) {
    this.networkParameters = networkParameters;
  }

  public IndexRange getIndexRangePostmix() {
    return indexRangePostmix;
  }

  public void setIndexRangePostmix(IndexRange indexRangePostmix) {
    this.indexRangePostmix = indexRangePostmix;
  }

  public String getBlindSignaturePublicKeyFile() {
    return blindSignaturePublicKeyFile;
  }

  public void setBlindSignaturePublicKeyFile(String blindSignaturePublicKeyFile) {
    this.blindSignaturePublicKeyFile = blindSignaturePublicKeyFile;
  }
}
