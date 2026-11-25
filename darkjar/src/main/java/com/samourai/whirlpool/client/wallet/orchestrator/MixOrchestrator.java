package com.samourai.whirlpool.client.wallet.orchestrator;

import com.samourai.wallet.util.AbstractOrchestrator;
import com.samourai.whirlpool.client.WhirlpoolClient;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.mix.MixParams;
import com.samourai.whirlpool.client.mix.listener.MixFailReason;
import com.samourai.whirlpool.client.mix.listener.MixStep;
import com.samourai.whirlpool.client.wallet.beans.*;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.samourai.whirlpool.client.whirlpool.listener.WhirlpoolClientListener;
import com.samourai.whirlpool.protocol.beans.Utxo;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

public abstract class MixOrchestrator extends AbstractOrchestrator {
  private final Logger log = LoggerFactory.getLogger(MixOrchestrator.class);
  private static final int LAST_ERROR_DELAY = 60 * 5; // 5min
  private static final int START_DELAY = 5000;

  private MixOrchestratorData data;

  private int maxClients;
  private int maxClientsPerPool;
  private boolean liquidityClient;
  private boolean autoMix;

  public MixOrchestrator(
      int loopDelay,
      int clientDelay,
      MixOrchestratorData data,
      int maxClients,
      int maxClientsPerPool,
      boolean liquidityClient,
      boolean autoMix) {
    super(loopDelay, START_DELAY, clientDelay);
    this.data = data;

    this.maxClients = maxClients;
    this.maxClientsPerPool = Math.min(maxClientsPerPool, maxClients); // prevent wrong configuration
    this.liquidityClient = liquidityClient;
    this.autoMix = autoMix;
  }

  @Override
  public synchronized void stop() {
    super.stop();

    clearQueue();
    stopMixingClients();

    // clear mixing data *after* stopping clients
    data.clear();
  }

  protected abstract WhirlpoolClient runWhirlpoolClient(WhirlpoolUtxo whirlpoolUtxo)
      throws NotifiableException;

  protected void stopWhirlpoolClient(Mixing mixing, boolean cancel, boolean reQueue) {
    if (log.isDebugEnabled()) {
      String reQueueStr = reQueue ? "(REQUEUE)" : "";
      if (cancel) {
        log.debug("Canceling mixing client" + reQueueStr + ": " + mixing);
      } else {
        log.debug("Stopping mixing client" + reQueueStr + ": " + mixing);
      }
    }
    // override here
  }

  @Override
  protected void runOrchestrator() {
    try {
      findAndMix();
    } catch (Exception e) {
      log.error("", e);
    }
  }

  protected boolean findAndMix() {
    if (log.isDebugEnabled()) {
      log.debug("checking for queued utxos to mix...");
    }

    // find mixable for each pool
    boolean found = false;
    for (Pool pool : data.getPools()) {
      try {
        boolean foundForPool = findAndMix(pool.getPoolId());
        if (foundForPool) {
          found = true;
        }
      } catch (Exception e) {
        log.error("", e);
      }
    }
    return found;
  }

  public synchronized void stopMixingClients() {
    if (log.isDebugEnabled()) {
      log.debug("stopMixingClients: " + data.getMixing().size() + " mixing");
    }
    for (Mixing oneMixing : data.getMixing()) {
      stopWhirlpoolClient(oneMixing, true, false);
    }
  }

  private synchronized void clearQueue() {
    data.getQueue()
        .forEach(
            new Consumer<WhirlpoolUtxo>() {
              @Override
              public void accept(WhirlpoolUtxo whirlpoolUtxo) {
                whirlpoolUtxo.getUtxoState().setStatus(WhirlpoolUtxoStatus.READY, false, false);
              }
            });
  }

  private synchronized boolean findAndMix(String poolId) throws Exception {
    if (!isStarted()) {
      return false; // wallet stopped in meantime
    }

    // find mixable for pool
    WhirlpoolUtxo[] mixableUtxos = findMixable(poolId);
    if (mixableUtxos == null) {
      return false;
    }

    // mix
    WhirlpoolUtxo whirlpoolUtxo = mixableUtxos[0];
    WhirlpoolUtxo mixingToSwap = mixableUtxos[1]; // may be null
    mix(whirlpoolUtxo, mixingToSwap);
    setLastRun();
    return true;
  }

  private Optional<Mixing> findMixingToSwap(
      final WhirlpoolUtxo toMix,
      final String mixingHashCriteria,
      final boolean bestPriorityCriteria,
      final String poolIdCriteria) {
    final WhirlpoolUtxoPriorityComparator comparator =
        WhirlpoolUtxoPriorityComparator.getInstance();

    return (data.getMixing()).stream()
        .filter(
            new Predicate<Mixing>() {
              @Override
              public boolean test(Mixing mixing) {
                String poolId = mixing.getUtxo().getUtxoState().getPoolId();

                // should not interrupt a mix
                MixProgress mixProgress = mixing.getUtxo().getUtxoState().getMixProgress();
                if (mixProgress != null && !mixProgress.getMixStep().isInterruptable()) {
                  return false;
                }

                // hash criteria
                if (mixingHashCriteria != null) {
                  String mixingHash = mixing.getUtxo().getUtxo().tx_hash;
                  if (!mixingHash.equals(mixingHashCriteria)) {
                    return false;
                  }
                }

                // pool criteria
                if (poolIdCriteria != null) {
                  if (!poolIdCriteria.equals(poolId)) {
                    return false;
                  }
                }

                // don't swap liquidity-dedicated thread for a mustMix
                if (liquidityClient
                    && toMix.isAccountPremix()
                    && mixing.getUtxo().isAccountPostmix()) {
                  boolean isLiquidityThread =
                      data.getNbMixing(poolId) > maxClientsPerPool
                          && data.getNbMixing(poolId, true) == 1;
                  if (isLiquidityThread) {
                    if (log.isTraceEnabled()) {
                      log.trace("not swapping liquidity thread: " + mixing);
                    }
                    return false;
                  }
                }

                // should be lower priority
                if (bestPriorityCriteria && comparator.compare(mixing.getUtxo(), toMix) <= 0) {
                  return false;
                }
                return true;
              }
            })
        .findFirst();
  }

  public boolean hasMoreMixingThreadAvailable(String poolId, boolean liquidity) {
    // check maxClients vs all mixings
    if (data.getMixing().size() >= maxClients) {
      return false;
    }

    // check maxClientsPerPool
    return !isMaxClientsPerPoolReached(poolId, liquidity);
  }

  private boolean isMaxClientsPerPoolReached(String poolId, boolean liquidity) {
    // allow addition
    // al thread for concurrent liquidity remixing
    if (liquidity && liquidityClient && data.getNbMixing(poolId, true) == 0) {
      return false;
    }

    // check maxClientsPerPool vs pool's mixings
    int nbMixingInPool = data.getNbMixing(poolId);
    return (nbMixingInPool >= maxClientsPerPool);
  }

  // returns [mixable,mixingToSwapOrNull]
  private WhirlpoolUtxo[] findMixable(final String poolId) {
    Predicate<WhirlpoolUtxo> filter =
        new Predicate<WhirlpoolUtxo>() {
          @Override
          public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
            // filter by poolId
            if (!poolId.equals(whirlpoolUtxo.getUtxoState().getPoolId())) {
              return false;
            }
            return true;
          }
        };
    List<WhirlpoolUtxo> mixableUtxos = getQueueByMixableStatus(true, filter, MixableStatus.MIXABLE);

    // find first mixable utxo, eventually by swapping a lower priority mixing utxo
    if (!mixableUtxos.isEmpty()) {
      for (WhirlpoolUtxo toMix : mixableUtxos) {
        WhirlpoolUtxo[] swap = findSwap(toMix, false);
        if (swap != null) {
          return swap;
        }
      }
      if (log.isDebugEnabled()) {
        log.debug(
            "["
                + poolId
                + "] "
                + data.getNbMixing(poolId)
                + " mixing, "
                + mixableUtxos.size()
                + " mixables, no additional mixing thread available");
      }
    }

    // no mixable found
    return null;
  }

  private synchronized WhirlpoolUtxo[] findSwap(WhirlpoolUtxo toMix, boolean mixNow) {
    String toMixHash = toMix.getUtxo().tx_hash;
    final String mixingHashCriteria = data.isHashMixing(toMixHash) ? toMixHash : null;
    String poolId = toMix.getUtxoState().getPoolId();
    boolean liquidity = toMix.isAccountPostmix();
    boolean mixingThreadAvailable = hasMoreMixingThreadAvailable(poolId, liquidity);
    if (mixingHashCriteria == null && mixingThreadAvailable) {
      // no swap required
      if (log.isTraceEnabled()) {
        log.trace("findSwap(" + toMix + ") => no swap required");
      }
      return new WhirlpoolUtxo[] {toMix, null};
    }

    // a swap is required to mix this utxo
    boolean bestPriorityCriteria = !mixNow;
    // swap with mixing from same pool when maxClientsPerPool is reached
    String poolIdCriteria =
        isMaxClientsPerPoolReached(poolId, liquidity) ? toMix.getUtxoState().getPoolId() : null;
    Optional<Mixing> mixingToSwapOpt =
        findMixingToSwap(toMix, mixingHashCriteria, bestPriorityCriteria, poolIdCriteria);
    if (mixingToSwapOpt.isPresent()) {
      // found mixing to swap
      if (log.isTraceEnabled()) {
        log.trace("findSwap(" + toMix + ") => swap found");
      }
      return new WhirlpoolUtxo[] {toMix, mixingToSwapOpt.get().getUtxo()};
    }
    return null;
  }

  private List<WhirlpoolUtxo> getQueueByMixableStatus(
      final boolean filterErrorDelay,
      Predicate<WhirlpoolUtxo> utxosFilter,
      final MixableStatus... filterMixableStatuses) {
    final long lastErrorMax = System.currentTimeMillis() - (LAST_ERROR_DELAY * 1000);

    // find queued
    Stream<WhirlpoolUtxo> stream =
        data.getQueue()
            .filter(
                new Predicate<WhirlpoolUtxo>() {
                  @Override
                  public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
                    WhirlpoolUtxoState utxoState = whirlpoolUtxo.getUtxoState();
                    // don't retry before errorDelay
                    boolean accepted =
                        (!filterErrorDelay
                            || utxoState.getLastError() == null
                            || utxoState.getLastError() < lastErrorMax);
                    if (!accepted) {
                      return false;
                    }

                    // filter by mixableStatus
                    return ArrayUtils.contains(filterMixableStatuses, utxoState.getMixableStatus());
                  }
                });
    if (utxosFilter != null) {
      stream = stream.filter(utxosFilter);
    }
    List<WhirlpoolUtxo> whirlpoolUtxos = stream.collect(Collectors.<WhirlpoolUtxo>toList());

    // suffle & sort
    sortShuffled(whirlpoolUtxos);
    return whirlpoolUtxos;
  }

  protected void sortShuffled(List<WhirlpoolUtxo> whirlpoolUtxos) {
    // shuffle
    Collections.shuffle(whirlpoolUtxos);

    // sort by priority, but keep utxos shuffled when same-priority
    Collections.sort(whirlpoolUtxos, WhirlpoolUtxoPriorityComparator.getInstance());
  }

  private boolean isQueuedAndMixable(WhirlpoolUtxo whirlpoolUtxo) {
    boolean isMixable =
        MixableStatus.MIXABLE.equals(whirlpoolUtxo.getUtxoState().getMixableStatus());
    if (isMixable
        && WhirlpoolUtxoStatus.MIX_QUEUE.equals(whirlpoolUtxo.getUtxoState().getStatus())) {
      if (log.isTraceEnabled()) {
        log.trace("new MIXABLE in mixQueue: " + whirlpoolUtxo);
      }
      return true;
    }
    return false;
  }

  public void mixQueue(WhirlpoolUtxo whirlpoolUtxo) throws NotifiableException {
    if (log.isDebugEnabled()) {
      log.debug(" +mixQueue: " + whirlpoolUtxo);
    }
    mixQueue(whirlpoolUtxo, true);
  }

  protected void mixQueue(WhirlpoolUtxo whirlpoolUtxo, boolean notify) throws NotifiableException {
    WhirlpoolUtxoState utxoState = whirlpoolUtxo.getUtxoState();
    WhirlpoolUtxoStatus utxoStatus = utxoState.getStatus();
    if (WhirlpoolUtxoStatus.MIX_QUEUE.equals(utxoStatus)) {
      // already queued
      if (log.isDebugEnabled()) {
        log.debug("mixQueue ignored: utxo already queued for " + whirlpoolUtxo);
      }
      return;
    }
    if (data.getMixing(whirlpoolUtxo.getUtxo()) != null
        || WhirlpoolUtxoStatus.MIX_SUCCESS.equals(utxoStatus)) {
      if (log.isDebugEnabled()) {
        log.debug("mixQueue ignored: utxo already mixing for " + whirlpoolUtxo);
      }
      return;
    }
    if (!WhirlpoolUtxoStatus.MIX_FAILED.equals(utxoStatus)
        && !WhirlpoolUtxoStatus.READY.equals(utxoStatus)) {
      throw new NotifiableException(
          "cannot add to mix queue: utxoStatus=" + utxoStatus + " for " + whirlpoolUtxo);
    }
    if (whirlpoolUtxo.getUtxoState().getPoolId() == null) {
      throw new NotifiableException("cannot add to mix queue: no pool set for " + whirlpoolUtxo);
    }

    // add to queue
    utxoState.setStatus(WhirlpoolUtxoStatus.MIX_QUEUE, false, false);
    data.getMixingState().incrementUtxoQueued(whirlpoolUtxo);
    if (notify) {
      notifyOrchestrator();
    }
  }

  public synchronized void mixNow(WhirlpoolUtxo whirlpoolUtxo) throws NotifiableException {
    // verify & queue
    mixQueue(whirlpoolUtxo, false);

    // mix now
    WhirlpoolUtxo[] mixableUtxos = findSwap(whirlpoolUtxo, true);
    if (mixableUtxos == null) {
      log.warn("No thread available to mix now, mix queued: " + whirlpoolUtxo);
      return;
    }
    WhirlpoolUtxo mixingToSwap = mixableUtxos[1]; // may be null
    mix(whirlpoolUtxo, mixingToSwap);
  }

  public synchronized void mixStop(WhirlpoolUtxo whirlpoolUtxo, boolean cancel, boolean reQueue) {
    WhirlpoolUtxoState utxoState = whirlpoolUtxo.getUtxoState();

    Mixing myMixing = data.getMixing(whirlpoolUtxo.getUtxo());
    if (myMixing != null) {
      // stop mixing
      stopWhirlpoolClient(myMixing, cancel, reQueue);
    } else {
      // remove from queue
      if (cancel) {
        log.debug("Remove from queue: " + whirlpoolUtxo);
      }
      boolean wasQueued = WhirlpoolUtxoStatus.MIX_QUEUE.equals(utxoState.getStatus());
      WhirlpoolUtxoStatus utxoStatus =
          cancel ? WhirlpoolUtxoStatus.READY : WhirlpoolUtxoStatus.STOP;
      utxoState.setStatus(utxoStatus, false, false);

      // recount QUEUE if it was queued
      if (wasQueued) {
        data.recountQueued();
      }
    }
  }

  protected synchronized void mix(WhirlpoolUtxo whirlpoolUtxo, WhirlpoolUtxo mixingToSwap)
      throws NotifiableException {
    if (!isStarted()) {
      throw new NotifiableException("Wallet is stopped");
    }

    // check mixable
    MixableStatus mixableStatus = whirlpoolUtxo.getUtxoState().getMixableStatus();
    if (!MixableStatus.MIXABLE.equals(mixableStatus)) {
      throw new NotifiableException("Cannot mix: " + mixableStatus);
    }

    if (log.isDebugEnabled()) {
      log.debug(" + Mix(" + (mixingToSwap != null ? "SWAP" : "IDLE") + "): " + whirlpoolUtxo);
    }

    // run mix
    WhirlpoolClient whirlpoolClient = runWhirlpoolClient(whirlpoolUtxo);
    Mixing mixing = new Mixing(whirlpoolUtxo, whirlpoolClient);
    data.addMixing(mixing);

    // stop mixingToSwap after adding our mix (to avoid triggering another mix before our mix)
    if (mixingToSwap != null) {
      mixStop(mixingToSwap, true, true);
    }
  }

  protected WhirlpoolClientListener computeMixListener(final MixParams mixParams) {
    final WhirlpoolUtxo whirlpoolUtxo = mixParams.getWhirlpoolUtxo();
    return new WhirlpoolClientListener() {
      @Override
      public void success(Utxo receiveUtxo) {
        // manage
        data.removeMixing(whirlpoolUtxo);

        // notify orchestrator
        notifyOrchestrator();
      }

      @Override
      public void fail(MixFailReason reason, String notifiableError) {
        // manage
        data.removeMixing(whirlpoolUtxo);

        // notify orchestrator
        notifyOrchestrator();
      }

      @Override
      public void progress(MixStep mixStep) {
        // nothing to do
      }
    };
  }

  public void onUtxoChanges(WhirlpoolUtxoChanges whirlpoolUtxoChanges) {
    if (!isStarted()) {
      return;
    }

    boolean notify = false;

    // DETECTED
    int nbQueued = 0;
    for (WhirlpoolUtxo whirlpoolUtxo : whirlpoolUtxoChanges.getUtxosAdded()) {
      // autoQueue
      if (isAutoQueue(whirlpoolUtxo, whirlpoolUtxoChanges.isFirstFetch())) {
        try {
          mixQueue(whirlpoolUtxo, false);
        } catch (Exception e) {
          log.error("", e);
        }
        notify = true;
        nbQueued++;
      }
      if (isQueuedAndMixable(whirlpoolUtxo)) {
        notify = true;
      }
    }
    if (nbQueued > 0) {
      if (log.isDebugEnabled()) {
        log.debug(" +mixQueue: " + nbQueued + " utxos");
      }
    }

    // CONFIRMED
    for (WhirlpoolUtxo whirlpoolUtxo : whirlpoolUtxoChanges.getUtxosConfirmed()) {
      if (isQueuedAndMixable(whirlpoolUtxo)) {
        notify = true;
      }
    }

    // REMOVED
    for (WhirlpoolUtxo whirlpoolUtxo : whirlpoolUtxoChanges.getUtxosRemoved()) {
      // stop mixing it
      Mixing mixing = data.getMixing(whirlpoolUtxo.getUtxo());
      if (mixing != null) {
        if (log.isDebugEnabled()) {
          log.debug("Stopping mixing removed utxo: " + whirlpoolUtxo);
        }
        stopWhirlpoolClient(mixing, true, false);
      }
    }

    if (notify) {
      notifyOrchestrator();
    }
  }

  private boolean isAutoQueue(WhirlpoolUtxo whirlpoolUtxo, boolean isFirstFetch) {
    if (WhirlpoolUtxoStatus.READY.equals(whirlpoolUtxo.getUtxoState().getStatus())
        && whirlpoolUtxo.getUtxoState().getPoolId() != null) {

      // automix : queue new PREMIX
      if (autoMix && whirlpoolUtxo.isAccountPremix()) {
        return true;
      }

      // queue unfinished POSTMIX utxos
      if ((!isFirstFetch || autoMix) && whirlpoolUtxo.isAccountPostmix()) {
        return true;
      }
    }
    return false;
  }
}
