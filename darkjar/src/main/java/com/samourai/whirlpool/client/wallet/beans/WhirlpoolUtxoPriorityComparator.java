package com.samourai.whirlpool.client.wallet.beans;


import java.util.Comparator;

public class WhirlpoolUtxoPriorityComparator implements Comparator<WhirlpoolUtxo> {
  private static final WhirlpoolUtxoPriorityComparator instance =
      new WhirlpoolUtxoPriorityComparator();
  private static final int RECENT_ERROR_SECONDS = 3600; // 1h
  private static final int SLOW_MIXING_SECONDS = 3600; // 1h

  public static WhirlpoolUtxoPriorityComparator getInstance() {
    return instance;
  }

  protected WhirlpoolUtxoPriorityComparator() {}

  @Override
  public int compare(WhirlpoolUtxo o1, WhirlpoolUtxo o2) {
    // premix before postmix
    if (o1.isAccountPremix() && o2.isAccountPostmix()) {
      return -1;
    }
    if (o1.isAccountPostmix() && o2.isAccountPremix()) {
      return 1;
    }

    // no error first
    boolean o1Error = hasRecentError(o1);
    boolean o2Error = hasRecentError(o2);
    if (o1Error && !o2Error) {
      return 1;
    }
    if (o2Error && !o1Error) {
      return -1;
    }
    if (o1Error && o2Error) {
      // both errors: older error first
      return Long.compare(o1.getUtxoState().getLastError(), o2.getUtxoState().getLastError());
    }

    // decrease priority when mixing for too long
    boolean o1MixingSlow = isMixingSlow(o1);
    boolean o2MixingSlow = isMixingSlow(o2);
    if (o1MixingSlow && !o2MixingSlow) {
      return 1;
    }
    if (o2MixingSlow && !o1MixingSlow) {
      return -1;
    }

    // same priority
    return 0;
  }

  private boolean hasRecentError(WhirlpoolUtxo whirlpoolUtxo) {
    WhirlpoolUtxoState s = whirlpoolUtxo.getUtxoState();
    if (s.getLastError() == null) {
      // no error
      return false;
    }

    // recent error?
    long minRecentError = System.currentTimeMillis() - (RECENT_ERROR_SECONDS * 1000);
    return s.getLastError() >= minRecentError;
  }

  private boolean isMixingSlow(WhirlpoolUtxo whirlpoolUtxo) {
    WhirlpoolUtxoState s = whirlpoolUtxo.getUtxoState();
    MixProgress mixProgress = s.getMixProgress();
    if (mixProgress == null) {
      // not mixing
      return false;
    }

    // mixing for long time?
    long elapsedSeconds = (System.currentTimeMillis() - mixProgress.getSince()) / 1000;
    return elapsedSeconds >= SLOW_MIXING_SECONDS;
  }
}
