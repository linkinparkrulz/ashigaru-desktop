package com.samourai.whirlpool.client.mix.handler;

import com.samourai.wallet.client.indexHandler.IIndexHandler;
import com.samourai.wallet.util.XPubUtil;
import org.bitcoinj.core.NetworkParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XPubPostmixHandler extends AbstractPostmixHandler {
  private static final Logger log = LoggerFactory.getLogger(XPubPostmixHandler.class);
  private static final XPubUtil xPubUtil = XPubUtil.getInstance();

  private String xPub;
  private int chain;
  private int startIndex;

  public XPubPostmixHandler(
      IIndexHandler indexHandler,
      NetworkParameters params,
      String xPub,
      int chain,
      int startIndex) {
    super(indexHandler, params);
    this.xPub = xPub;
    this.chain = chain;
    this.startIndex = startIndex;
  }

  @Override
  protected MixDestination computeNextDestination() throws Exception {
    // index
    int index = indexHandler.getAndIncrementUnconfirmed();
    index = Math.max(index, startIndex);

    // address
    String address = xPubUtil.getAddressBech32(xPub, index, chain, params);
    String path = xPubUtil.getPath(index, chain);

    log.debug("Mixing to external xPub -> receiveAddress=" + address + ", path=" + path);
    return new MixDestination(DestinationType.XPUB, index, address, path);
  }
}
