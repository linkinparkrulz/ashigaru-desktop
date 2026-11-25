package com.samourai.whirlpool.client.wallet.beans;

import com.samourai.wallet.api.backend.beans.UnspentOutput;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.client.wallet.data.chain.ChainSupplier;
import com.samourai.whirlpool.client.wallet.data.pool.PoolSupplier;
import com.samourai.whirlpool.client.wallet.data.utxo.UtxoSupplier;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MixOrchestratorData {
  private final Logger log = LoggerFactory.getLogger(MixOrchestratorData.class);

  private ConcurrentHashMap<String, Mixing> mixing;
  private Set<String> mixingHashs;
  private Map<String, Integer> mixingPerPool;

  private MixingStateEditable mixingState;
  private PoolSupplier poolSupplier;
  private UtxoSupplier utxoSupplier;
  private ChainSupplier chainSupplier;

  public MixOrchestratorData(
      MixingStateEditable mixingState,
      PoolSupplier poolSupplier,
      UtxoSupplier utxoSupplier,
      ChainSupplier chainSupplier) {
    this.mixing = new ConcurrentHashMap<String, Mixing>();
    this.mixingHashs = new HashSet<String>();
    this.mixingPerPool = new HashMap<String, Integer>();
    this.mixingState = mixingState;
    this.poolSupplier = poolSupplier;
    this.utxoSupplier = utxoSupplier;
    this.chainSupplier = chainSupplier;
  }

  public Stream<WhirlpoolUtxo> getQueue() {
    return (utxoSupplier.findUtxos(WhirlpoolAccount.PREMIX, WhirlpoolAccount.POSTMIX)).stream()
        .filter(
            new Predicate<WhirlpoolUtxo>() {
              @Override
              public boolean test(WhirlpoolUtxo whirlpoolUtxo) {
                // queued
                return WhirlpoolUtxoStatus.MIX_QUEUE.equals(
                    whirlpoolUtxo.getUtxoState().getStatus());
              }
            });
  }

  public Collection<Pool> getPools() {
    return poolSupplier.getPools();
  }

  public Pool findPoolById(String poolId) {
    return poolSupplier.findPoolById(poolId);
  }

  public void clear() {
    mixing.clear();
    mixingHashs.clear();
    mixingPerPool.clear();
    this.mixingState.setUtxosMixing(new LinkedList<WhirlpoolUtxo>());
  }

  public synchronized void removeMixing(WhirlpoolUtxo whirlpoolUtxo) {
    String key = ClientUtils.utxoToKey(whirlpoolUtxo.getUtxo());
    mixing.remove(key);
    mixingHashs.remove(whirlpoolUtxo.getUtxo().tx_hash);
    mixingPerPool = computeMixingPerPool();
    mixingState.setUtxosMixing(computeUtxosMixing());
  }

  public synchronized void addMixing(Mixing mixingToAdd) {
    WhirlpoolUtxo whirlpoolUtxo = mixingToAdd.getUtxo();
    String key = ClientUtils.utxoToKey(whirlpoolUtxo.getUtxo());
    mixing.put(key, mixingToAdd);
    mixingHashs.add(whirlpoolUtxo.getUtxo().tx_hash);
    mixingPerPool = computeMixingPerPool();
    mixingState.set(
        computeUtxosMixing(),
        getQueue().collect(Collectors.<WhirlpoolUtxo>toList())); // recount nbQueued too
  }

  private Collection<WhirlpoolUtxo> computeUtxosMixing() {
    return (mixing.values()).stream()
        .map(
            new Function<Mixing, WhirlpoolUtxo>() {
              @Override
              public WhirlpoolUtxo apply(Mixing m) {
                return m.getUtxo();
              }
            })
        .collect(Collectors.<WhirlpoolUtxo>toList());
  }

  private Map<String, Integer> computeMixingPerPool() {
    Map<String, Integer> mixingPerPool = new HashMap<String, Integer>();
    for (Mixing mixingItem : mixing.values()) {
      String poolId = mixingItem.getUtxo().getUtxoState().getPoolId();
      int currentCount = mixingPerPool.containsKey(poolId) ? mixingPerPool.get(poolId) : 0;
      mixingPerPool.put(poolId, currentCount + 1);
    }
    return mixingPerPool;
  }

  public Collection<Mixing> getMixing() {
    return mixing.values();
  }

  public Mixing getMixing(UnspentOutput utxo) {
    final String key = ClientUtils.utxoToKey(utxo);
    return mixing.get(key);
  }

  public boolean isHashMixing(String txid) {
    return mixingHashs.contains(txid);
  }

  public int getNbMixing(String poolId) {
    Integer nbMixingInPool = mixingPerPool.get(poolId);
    return (nbMixingInPool != null ? nbMixingInPool : 0);
  }

  public int getNbMixing(final String poolId, final boolean liquidity) {
    return (mixing.values()).stream()
        .filter(
            new Predicate<Mixing>() {
              @Override
              public boolean test(Mixing mixing) {
                return mixing.getUtxo().getUtxoState().getPoolId().equals(poolId)
                    && mixing.getUtxo().isAccountPostmix() == liquidity;
              }
            })
        .collect(Collectors.<Mixing>toList())
        .size();
  }

  public MixingStateEditable getMixingState() {
    return mixingState;
  }

  public void recountQueued() {
    mixingState.setUtxosQueued(getQueue().collect(Collectors.<WhirlpoolUtxo>toList()));
  }

  public int getLatestBlockHeight() {
    return chainSupplier.getLatestBlock().height;
  }
}
