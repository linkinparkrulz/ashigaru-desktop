package com.samourai.whirlpool.client.wallet.data.pool;

import com.samourai.whirlpool.client.tx0.Tx0ParamService;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolPoolByBalanceMinDescComparator;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.samourai.whirlpool.protocol.rest.PoolInfo;
import com.samourai.whirlpool.protocol.rest.PoolsResponse;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PoolData {
  private static final Logger log = LoggerFactory.getLogger(PoolData.class);

  private final Map<String, Pool> poolsById;

  public PoolData(PoolsResponse poolsResponse, Tx0ParamService tx0ParamService) {
    this.poolsById = computePools(poolsResponse, tx0ParamService);
  }

  private static Map<String, Pool> computePools(
          PoolsResponse poolsResponse, final Tx0ParamService tx0ParamService) {
    // biggest balanceMin first
    List<Pool> poolsOrdered =
        (Arrays.asList(poolsResponse.pools)).stream()
            .map(
                new Function<PoolInfo, Pool>() {
                  @Override
                  public Pool apply(PoolInfo poolInfo) {
                    Pool pool = new Pool();
                    pool.setPoolId(poolInfo.poolId);
                    pool.setDenomination(poolInfo.denomination);
                    pool.setFeeValue(poolInfo.feeValue);
                    pool.setMustMixBalanceMin(poolInfo.mustMixBalanceMin);
                    pool.setMustMixBalanceCap(poolInfo.mustMixBalanceCap);
                    pool.setMustMixBalanceMax(poolInfo.mustMixBalanceMax);
                    pool.setMinAnonymitySet(poolInfo.minAnonymitySet);
                    pool.setMinMustMix(poolInfo.minMustMix);
                    pool.setTx0MaxOutputs(poolInfo.tx0MaxOutputs);
                    pool.setNbRegistered(poolInfo.nbRegistered);

                    pool.setMixAnonymitySet(poolInfo.mixAnonymitySet);
                    pool.setMixStatus(poolInfo.mixStatus);
                    pool.setElapsedTime(poolInfo.elapsedTime);
                    pool.setNbConfirmed(poolInfo.nbConfirmed);

                    pool.setPremixValueMinAndDepositMin(tx0ParamService);
                    return pool;
                  }
                })
            .sorted(new WhirlpoolPoolByBalanceMinDescComparator())
            .collect(Collectors.<Pool>toList());

    // map by id
    Map<String, Pool> poolsById = new LinkedHashMap<String, Pool>();
    for (Pool pool : poolsOrdered) {
      poolsById.put(pool.getPoolId(), pool);
    }
    return poolsById;
  }

  public Collection<Pool> getPools() {
    return poolsById.values();
  }

  public Pool findPoolById(String poolId) {
    return poolsById.get(poolId);
  }
}
