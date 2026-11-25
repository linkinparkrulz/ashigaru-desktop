package com.samourai.whirlpool.client.wallet.beans;

import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import java.util.function.Predicate;

import java.util.ArrayList;
import java.util.Collection;

public class MixingState {
  private boolean started;
  private Collection<WhirlpoolUtxo> utxosMixing;
  private int nbMixing;
  private int nbMixingMustMix;
  private int nbMixingLiquidity;
  private int nbQueued;
  private int nbQueuedMustMix;
  private int nbQueuedLiquidity;
  private Subject<MixingState> observable;

  public MixingState(boolean started) {
    this.started = started;
    doSetUtxosMixing(new ArrayList<WhirlpoolUtxo>());
    doSetUtxosQueued(new ArrayList<WhirlpoolUtxo>());
    this.observable = BehaviorSubject.create();
  }

  protected void setStarted(boolean started) {
    this.started = started;
    emit();
  }

  public boolean isStarted() {
    return started;
  }

  private void doSetUtxosMixing(Collection<WhirlpoolUtxo> utxosMixing) {
    this.utxosMixing = utxosMixing;
    this.nbMixing = utxosMixing.size();
    this.nbMixingLiquidity =
        (int)
            (utxosMixing).stream()
                .filter(
                    new Predicate<WhirlpoolUtxo>() {
                      @Override
                      public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
                        return whirlpoolUtxo.isAccountPostmix();
                      }
                    })
                .count();
    this.nbMixingMustMix = this.nbMixing - this.nbMixingLiquidity;
  }

  private void doSetUtxosQueued(Collection<WhirlpoolUtxo> utxosQueued) {
    this.nbQueued = utxosQueued.size();
    this.nbQueuedLiquidity =
        (int)
            (utxosQueued).stream()
                .filter(
                    new Predicate<WhirlpoolUtxo>() {
                      @Override
                      public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
                        return whirlpoolUtxo.isAccountPostmix();
                      }
                    })
                .count();
    this.nbQueuedMustMix = this.nbQueued - this.nbQueuedLiquidity;
  }

  protected void incrementUtxoQueued(WhirlpoolUtxo utxoQueued) {
    if (utxoQueued.isAccountPostmix()) {
      nbQueuedLiquidity++;
    } else {
      nbQueuedMustMix++;
    }
    nbQueued++;
  }

  protected synchronized void set(
          Collection<WhirlpoolUtxo> utxosMixing, Collection<WhirlpoolUtxo> utxosQueued) {
    doSetUtxosMixing(utxosMixing);
    doSetUtxosQueued(utxosQueued);
    emit();
  }

  protected synchronized void setUtxosMixing(Collection<WhirlpoolUtxo> utxosMixing) {
    doSetUtxosMixing(utxosMixing);
    emit();
  }

  protected synchronized void setUtxosQueued(Collection<WhirlpoolUtxo> utxosQueued) {
    doSetUtxosQueued(utxosQueued);
    emit();
  }

  protected void emit() {
    // notify
    observable.onNext(this);
  }

  @Override
  public String toString() {
    return nbQueued + " queued, " + nbMixing + " mixing: " + utxosMixing;
  }

  public Collection<WhirlpoolUtxo> getUtxosMixing() {
    return utxosMixing;
  }

  public int getNbMixing() {
    return nbMixing;
  }

  public int getNbMixingMustMix() {
    return nbMixingMustMix;
  }

  public int getNbMixingLiquidity() {
    return nbMixingLiquidity;
  }

  public int getNbQueued() {
    return nbQueued;
  }

  public int getNbQueuedMustMix() {
    return nbQueuedMustMix;
  }

  public int getNbQueuedLiquidity() {
    return nbQueuedLiquidity;
  }

  public Observable<MixingState> getObservable() {
    return observable;
  }
}
