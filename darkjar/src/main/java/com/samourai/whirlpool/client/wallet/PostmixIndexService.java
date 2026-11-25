package com.samourai.whirlpool.client.wallet;

import com.samourai.wallet.client.BipWalletAndAddressType;
import com.samourai.wallet.client.indexHandler.IIndexHandler;
import com.samourai.wallet.hd.Chain;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.protocol.rest.CheckOutputRequest;
import io.reactivex.Observable;
import java.util.Optional;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostmixIndexService {
  private Logger log = LoggerFactory.getLogger(PostmixIndexService.class);
  private static final int POSTMIX_INDEX_RANGE_ITERATIONS = 50;
  protected static final int POSTMIX_INDEX_RANGE_ACCEPTABLE_GAP = 4;
  protected static final String CHECKOUTPUT_ERROR_OUTPUT_ALREADY_REGISTERED =
          "Output already registered";

  private WhirlpoolWalletConfig config;
  private Bech32UtilGeneric bech32Util;

  public PostmixIndexService(WhirlpoolWalletConfig config, Bech32UtilGeneric bech32Util) {
    this.config = config;
    this.bech32Util = bech32Util;
  }

  public void checkPostmixIndex(BipWalletAndAddressType walletPostmix) throws Exception {
    IIndexHandler postmixIndexHandler = walletPostmix.getIndexHandler();

    // check next output
    int postmixIndex =
            ClientUtils.computeNextReceiveAddressIndex(
                    postmixIndexHandler, config.getIndexRangePostmix());

    try {
      checkPostmixIndex(walletPostmix, postmixIndex).blockingSingle().get(); // throws on error
    } finally {
      postmixIndexHandler.cancelUnconfirmed(postmixIndex);
    }
  }

  private Observable<Optional<String>> checkPostmixIndex(
          BipWalletAndAddressType walletPostmix, int postmixIndex) throws Exception {
    if (log.isDebugEnabled()) {
      log.debug("checking postmixIndex: " + postmixIndex);
    }
    HD_Address hdAddress = walletPostmix.getAddressAt(Chain.RECEIVE.getIndex(), postmixIndex);
    String outputAddress = bech32Util.toBech32(hdAddress, config.getNetworkParameters());
    String signature = hdAddress.getECKey().signMessage(outputAddress);
    CheckOutputRequest checkOutputRequest = new CheckOutputRequest(outputAddress, signature);
    return config.getServerApi().checkOutput(checkOutputRequest);
  }

  public void fixPostmixIndex(BipWalletAndAddressType walletPostmix) throws Exception {
    IIndexHandler postmixIndexHandler = walletPostmix.getIndexHandler();

    int leftIndex = 0;
    int rightIndex = 0;
    for (int i = 0; i < POSTMIX_INDEX_RANGE_ITERATIONS; i++) {
      // quickly find index range
      Pair<Integer, Integer> indexRange = findPostmixIndexRange(walletPostmix);
      leftIndex = indexRange.getLeft();
      rightIndex = indexRange.getRight();
      if (log.isDebugEnabled()) {
        log.debug("valid postmixIndex range #" + i + ": [" + leftIndex + ";" + rightIndex + "]");
      }

      if ((rightIndex - leftIndex) < POSTMIX_INDEX_RANGE_ACCEPTABLE_GAP) {
        // finished
        if (log.isDebugEnabled()) {
          log.debug("fixing postmixIndex: " + rightIndex);
        }
        postmixIndexHandler.confirmUnconfirmed(rightIndex);
        return;
      }

      // continue with closer and closer index range...
    }
    throw new Exception(
            "PostmixIndex error - please resync your wallet or contact support. PostmixIndex=["
                    + leftIndex
                    + ";"
                    + rightIndex
                    + "]");
  }

  private Pair<Integer, Integer> findPostmixIndexRange(BipWalletAndAddressType walletPostmix)
          throws Exception {
    IIndexHandler postmixIndexHandler = walletPostmix.getIndexHandler();

    int postmixIndex = 0;
    int incrementGap = 1;
    for (int i = 0; i < POSTMIX_INDEX_RANGE_ITERATIONS; i++) {
      int leftIndex = 0;
      try {
        // increment by incrementGap
        for (int x = 0; x < incrementGap; x++) {
          postmixIndex =
                  ClientUtils.computeNextReceiveAddressIndex(
                          postmixIndexHandler, config.getIndexRangePostmix());

          // set leftIndex
          if (x == 0) {
            leftIndex = postmixIndex;
          }
        }

        // check next output
        checkPostmixIndex(walletPostmix, postmixIndex).blockingSingle().get();

        // success!
        if (log.isDebugEnabled()) {
          log.debug("valid postmixIndex: " + postmixIndex);
        }

        // set postmixIndex to leftIndex (by cancelling unconfirmed indexs > leftIndex)
        if (log.isDebugEnabled()) {
          log.debug("rollbacking postmixIndex: " + postmixIndex + " => " + leftIndex);
        }
        for (int unconfirmedIndex = postmixIndex;
             unconfirmedIndex > leftIndex;
             unconfirmedIndex--) {
          postmixIndexHandler.cancelUnconfirmed(unconfirmedIndex);
        }

        // => return inclusive range
        return Pair.of(leftIndex, postmixIndex);

      } catch (Exception e) {
        String restErrorMessage = ClientUtils.parseRestErrorMessage(e);
        if (restErrorMessage != null
                && CHECKOUTPUT_ERROR_OUTPUT_ALREADY_REGISTERED.equals(restErrorMessage)) {
          log.warn("postmixIndex already used: " + postmixIndex);

          // quick look-forward
          incrementGap *= 2;

          // avoid flooding
          try {
            Thread.sleep(500);
          } catch (InterruptedException ee) {
          }
        } else {
          throw e;
        }
      }
    }
    throw new NotifiableException(
            "PostmixIndex error - please resync your wallet or contact support. PostmixIndex="
                    + postmixIndex);
  }
}
