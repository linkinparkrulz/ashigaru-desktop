package com.samourai.whirlpool.client.wallet.data.utxoConfig;

import com.samourai.whirlpool.client.wallet.data.supplier.PersistableData;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class UtxoConfigData extends PersistableData {
  private static final Logger log = LoggerFactory.getLogger(UtxoConfigData.class);
  private static final int EXPIRATION_SECONDS = 604800; // 7d >= mempool expiration

  private Map<String, UtxoConfigPersisted> utxoConfigs;

  // used by Sparrow
  public UtxoConfigData() {
    this(new LinkedHashMap<String, UtxoConfigPersisted>());
  }

  // used by Sparrow
  public UtxoConfigData(Map<String, UtxoConfigPersisted> utxoConfigs) {
    super();
    this.utxoConfigs = utxoConfigs;
  }

  // used by Sparrow
  public Map<String, UtxoConfigPersisted> getUtxoConfigs() {
    return utxoConfigs;
  }

  public synchronized void add(String key, UtxoConfigPersisted utxoConfig) {
    utxoConfigs.put(key, utxoConfig);
    setLastChange();
  }

  protected synchronized int cleanup(final Collection<String> validKeys) {
    final long MIN_EXPIRED = System.currentTimeMillis() - (EXPIRATION_SECONDS * 1000);

    // remove obsolete utxoConfigs
    Map<String, UtxoConfigPersisted> newUtxoConfigs =
        (utxoConfigs.entrySet()).stream()
            .filter(
                new Predicate<Map.Entry<String, UtxoConfigPersisted>>() {
                  @Override
                  public boolean test(Map.Entry<String, UtxoConfigPersisted> e) {
                    String key = e.getKey();
                    UtxoConfigPersisted utxoConfigPersisted = e.getValue();

                    // keep existing utxos
                    if (validKeys.contains(key)) {
                      // mark as valid
                      if (utxoConfigPersisted.getExpired() != null) {
                        utxoConfigPersisted.setExpired(null);
                        setLastChange();
                        if (log.isDebugEnabled()) {
                          log.debug("utxoConfig valid: " + key + ", " + utxoConfigPersisted);
                        }
                      }
                      return true;
                    }

                    // mark as expired
                    if (utxoConfigPersisted.getExpired() == null) {
                      utxoConfigPersisted.setExpired(System.currentTimeMillis());
                      setLastChange();
                      if (log.isDebugEnabled()) {
                        log.debug("utxoConfig expired: " + key + ", " + utxoConfigPersisted);
                      }
                    }

                    // purge older expired
                    if (utxoConfigPersisted.getExpired() <= MIN_EXPIRED) {
                      if (log.isDebugEnabled()) {
                        log.debug("utxoConfig cleaned: " + key + ", " + utxoConfigPersisted);
                      }
                      return false;
                    }

                    // keep the others
                    if (log.isTraceEnabled()) {
                      long remainingSeconds =
                          (utxoConfigPersisted.getExpired() - MIN_EXPIRED) / 1000;
                      log.trace(
                          "utxoConfig awaiting expiration ("
                              + remainingSeconds
                              + "s): "
                              + key
                              + ", "
                              + utxoConfigPersisted);
                    }
                    return true;
                  }
                })
            .collect(
                Collectors.toMap(
                    new Function<Map.Entry<String, UtxoConfigPersisted>, String>() {
                      @Override
                      public String apply(Map.Entry<String, UtxoConfigPersisted> e) {
                        return e.getKey();
                      }
                    },
                    new Function<Map.Entry<String, UtxoConfigPersisted>, UtxoConfigPersisted>() {
                      @Override
                      public UtxoConfigPersisted apply(Map.Entry<String, UtxoConfigPersisted> e) {
                        return e.getValue();
                      }
                    }));

    int nbCleaned = utxoConfigs.size() - newUtxoConfigs.size();
    this.utxoConfigs = newUtxoConfigs;
    if (nbCleaned > 0) {
      setLastChange();
    }
    if (log.isDebugEnabled()) {
      log.debug(
          "cleanup: minExpired="
              + MIN_EXPIRED
              + ", nbCleaned="
              + nbCleaned
              + ", nb="
              + newUtxoConfigs.size());
    }
    return nbCleaned;
  }

  public UtxoConfigPersisted getUtxoConfig(String key) {
    return utxoConfigs.get(key);
  }

  @Override
  public String toString() {
    return utxoConfigs.size() + " utxoConfigs";
  }
}
