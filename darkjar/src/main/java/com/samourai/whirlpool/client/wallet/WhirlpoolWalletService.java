package com.samourai.whirlpool.client.wallet;

import com.samourai.whirlpool.client.utils.ClientUtils;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WhirlpoolWalletService {
  private final Logger log = LoggerFactory.getLogger(WhirlpoolWalletService.class);

  private WhirlpoolWallet whirlpoolWallet; // or null

  public WhirlpoolWalletService() {
    this.whirlpoolWallet = null;

    // set user-agent
    ClientUtils.setupEnv();
  }

  public synchronized void closeWallet() {
    if (whirlpoolWallet != null) {
      whirlpoolWallet.close();
      whirlpoolWallet = null;
    } else {
      if (log.isDebugEnabled()) {
        log.debug("closeWallet skipped: no wallet opened");
      }
    }
  }

  public synchronized WhirlpoolWallet openWallet(WhirlpoolWallet wp) throws Exception {
    if (whirlpoolWallet != null) {
      throw new Exception("WhirlpoolWallet already opened");
    }

    wp.open();
    whirlpoolWallet = wp;
    return wp;
  }

  public Optional<WhirlpoolWallet> getWhirlpoolWallet() {
    return Optional.ofNullable(whirlpoolWallet);
  }

  public WhirlpoolWallet whirlpoolWallet() {
    return whirlpoolWallet;
  }
}
