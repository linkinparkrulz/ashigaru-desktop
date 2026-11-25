package com.samourai.whirlpool.client.utils;

import com.samourai.wallet.api.backend.MinerFeeTarget;
import com.samourai.wallet.api.backend.beans.UnspentOutput;
import com.samourai.wallet.api.backend.beans.WalletResponse;
import com.samourai.wallet.client.BipWalletAndAddressType;
import com.samourai.wallet.client.indexHandler.IIndexHandler;
import com.samourai.wallet.hd.AddressType;
import com.samourai.wallet.hd.Chain;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.whirlpool.client.wallet.WhirlpoolWallet;
import com.samourai.whirlpool.client.wallet.beans.*;
import java.util.Collection;
import java.util.Iterator;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.bitcoinj.core.NetworkParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DebugUtils {
  private static final Logger log = LoggerFactory.getLogger(DebugUtils.class);

  public static String getDebug(WhirlpoolWallet whirlpoolWallet) {
    StringBuilder sb = new StringBuilder("\n");
    sb.append("now = " + ClientUtils.dateToString(System.currentTimeMillis()));
    if (whirlpoolWallet != null) {
      sb.append(getDebugWallet(whirlpoolWallet));
      sb.append(getDebugUtxos(whirlpoolWallet));
      sb.append(getDebugMixingThreads(whirlpoolWallet));
    } else {
      sb.append("WhirlpoolWallet is closed.\n");
    }
    sb.append(getDebugSystem());
    return sb.toString();
  }

  public static String getDebugWallet(WhirlpoolWallet whirlpoolWallet) {
    StringBuilder sb = new StringBuilder().append("\n");

    // receive address
    AddressType depositAddressType = AddressType.SEGWIT_NATIVE;
    BipWalletAndAddressType receiveWallet =
        whirlpoolWallet.getWalletSupplier().getWallet(WhirlpoolAccount.DEPOSIT, depositAddressType);
    HD_Address hdAddress = receiveWallet.getNextAddress(false);
    String receiveAddress = hdAddress.getAddressString(depositAddressType);
    String receivePath = hdAddress.getPathFull(depositAddressType);

    sb.append("⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿" + "\n");
    sb.append("⣿ RECEIVE ADDRESS" + "\n");
    sb.append(" • Address: " + receiveAddress + "\n");
    sb.append(" • Path: " + receivePath + "\n");

    // balance
    sb.append("⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿" + "\n");
    sb.append("⣿ WALLET BALANCE:" + "\n");
    for (WhirlpoolAccount account : WhirlpoolAccount.values()) {
      Collection<WhirlpoolUtxo> utxos = whirlpoolWallet.getUtxoSupplier().findUtxos(account);
      sb.append(" • " + account + ": " + utxos.size() + " utxos\n");

      for (AddressType addressType : account.getAddressTypes()) {
        utxos = whirlpoolWallet.getUtxoSupplier().findUtxos(addressType, account);
        BipWalletAndAddressType wallet =
            whirlpoolWallet.getWalletSupplier().getWallet(account, addressType);
        sb.append(
            "___"
                + account
                + "/"
                + addressType
                + ": "
                + utxos.size()
                + " utxos"
                + ", pub="
                + ClientUtils.maskString(wallet.getPub())
                + "\n");

        for (Chain chain : Chain.values()) {
          IIndexHandler indexHandler =
              whirlpoolWallet
                  .getWalletStateSupplier()
                  .getIndexHandlerWallet(account, addressType, chain);
          int index = indexHandler.get();
          sb.append("______.index[" + chain + "] = " + index + "\n");
        }
      }
    }
    sb.append(
        " • TOTAL = "
            + ClientUtils.satToBtc(whirlpoolWallet.getUtxoSupplier().getBalanceTotal())
            + " BTC"
            + "\n");
    sb.append("");

    // chain status
    WalletResponse.InfoBlock latestBlock = whirlpoolWallet.getChainSupplier().getLatestBlock();
    sb.append("⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿" + "\n");
    sb.append("⣿ LATEST BLOCK:" + "\n");
    sb.append(" • Height: " + latestBlock.height + "\n");
    sb.append(" • Hash: " + latestBlock.hash + "\n");
    sb.append(" • Time: " + ClientUtils.dateToString(latestBlock.time * 1000) + "\n");
    sb.append("");

    // chain
    sb.append("⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿" + "\n");
    sb.append("⣿ MINER FEE:" + "\n");
    for (MinerFeeTarget minerFeeTarget : MinerFeeTarget.values()) {
      long value = whirlpoolWallet.getMinerFeeSupplier().getFee(minerFeeTarget);
      sb.append("fee[" + minerFeeTarget + "] = " + value + "\n");
    }
    return sb.toString();
  }

  public static String getDebugUtxos(WhirlpoolWallet whirlpoolWallet) {
    StringBuilder sb = new StringBuilder().append("\n");
    for (WhirlpoolAccount account : WhirlpoolAccount.values()) {
      sb.append(getDebugUtxos(whirlpoolWallet, account) + "\n");
    }
    return sb.toString();
  }

  private static String getDebugUtxos(WhirlpoolWallet whirlpoolWallet, WhirlpoolAccount account) {
    StringBuilder sb = new StringBuilder().append("\n");

    Collection<WhirlpoolUtxo> utxos = whirlpoolWallet.getUtxoSupplier().findUtxos(account);
    int latestBlockHeight = whirlpoolWallet.getChainSupplier().getLatestBlock().height;
    try {
      sb.append("⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿");
      double balance = ClientUtils.satToBtc(whirlpoolWallet.getUtxoSupplier().getBalanceTotal());
      String lastUpdate =
          ClientUtils.dateToString(whirlpoolWallet.getUtxoSupplier().getLastUpdate());
      sb.append(
          "⣿ "
              + account.name()
              + " UTXOS ("
              + utxos.size()
              + ") ("
              + balance
              + "btc at "
              + lastUpdate
              + "):");
      sb.append(getDebugUtxos(utxos, latestBlockHeight));
    } catch (Exception e) {
      log.error("", e);
    }
    return sb.toString();
  }

  public static String getDebugUtxos(Collection<WhirlpoolUtxo> utxos, int latestBlockHeight) {
    String lineFormat =
        "| %10s | %7s | %68s | %45s | %13s | %27s | %14s | %8s | %8s | %4s | %19s | %19s |\n";
    StringBuilder sb = new StringBuilder().append("\n");
    sb.append(
        String.format(
                lineFormat,
                "BALANCE",
                "CONFIRM",
                "UTXO",
                "ADDRESS",
                "TYPE",
                "PATH",
                "STATUS",
                "MIXABLE",
                "POOL",
                "MIXS",
                "ACTIVITY",
                "ERROR")
            + "\n");
    sb.append(
        String.format(lineFormat, "(btc)", "", "", "", "", "", "", "", "", "", "", "") + "\n");
    Iterator var3 = utxos.iterator();

    while (var3.hasNext()) {
      WhirlpoolUtxo whirlpoolUtxo = (WhirlpoolUtxo) var3.next();
      WhirlpoolUtxoState utxoState = whirlpoolUtxo.getUtxoState();
      UnspentOutput o = whirlpoolUtxo.getUtxo();
      String utxo = o.tx_hash + ":" + o.tx_output_n;
      String mixableStatusName =
          utxoState.getMixableStatus() != null ? utxoState.getMixableStatus().name() : "-";
      Long lastActivity = whirlpoolUtxo.getUtxoState().getLastActivity();
      String activity =
          (lastActivity != null ? ClientUtils.dateToString(lastActivity) + " " : "")
              + (whirlpoolUtxo.getUtxoState().getMessage() != null
                  ? whirlpoolUtxo.getUtxoState().getMessage()
                  : "");
      Long lastError = whirlpoolUtxo.getUtxoState().getLastError();
      String error =
          (lastError != null ? ClientUtils.dateToString(lastError) + " " : "")
              + (whirlpoolUtxo.getUtxoState().getError() != null
                  ? whirlpoolUtxo.getUtxoState().getError()
                  : "");
      sb.append(
          String.format(
              lineFormat,
              ClientUtils.satToBtc(o.value),
              whirlpoolUtxo.computeConfirmations(latestBlockHeight),
              utxo,
              o.addr,
              whirlpoolUtxo.getAddressType(),
              whirlpoolUtxo.getPathFull(),
              utxoState.getStatus().name(),
              mixableStatusName,
              whirlpoolUtxo.getUtxoState().getPoolId() != null
                  ? whirlpoolUtxo.getUtxoState().getPoolId()
                  : "-",
              whirlpoolUtxo.getMixsDone(),
              activity,
              error));
    }
    return sb.toString();
  }

  public static String getDebugUtxos(
      Collection<UnspentOutput> utxos, int purpose, int accountIndex, NetworkParameters params) {
    String lineFormat = "| %10s | %7s | %68s | %45s | %18s |\n";
    StringBuilder sb = new StringBuilder().append("\n");
    sb.append(
        String.format(lineFormat, "BALANCE", "CONFIRM", "UTXO", "ADDRESS", "TYPE", "PATH") + "\n");
    sb.append(String.format(lineFormat, "(btc)", "", "", "", "", "") + "\n");
    for (UnspentOutput o : utxos) {
      String utxo = o.tx_hash + ":" + o.tx_output_n;
      sb.append(
          String.format(
              lineFormat,
              ClientUtils.satToBtc(o.value),
              o.confirmations,
              utxo,
              o.addr,
              AddressType.findByAddress(o.addr, params),
              o.getPathFull(purpose, accountIndex)));
    }
    return sb.toString();
  }

  public static String getDebugSystem() {
    StringBuilder sb = new StringBuilder().append("\n");
    final ThreadGroup tg = Thread.currentThread().getThreadGroup();
    Collection<Thread> threadSet =
        (Thread.getAllStackTraces().keySet()).stream()
            .filter(
                new Predicate<Thread>() {
                  @Override
                  public boolean test(Thread t) {
                    return t.getThreadGroup() == tg;
                  }
                })
            .sorted(
                Comparator.comparing(
                    new Function<Thread, String>() {
                      @Override
                      public String apply(Thread t) {
                        return t.getName().toLowerCase();
                      }
                    }))
            .collect(Collectors.<Thread>toList());
    sb.append("⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿" + "\n");
    sb.append("⣿ SYSTEM THREADS:" + "\n");
    int i = 0;
    for (Thread t : threadSet) {
      sb.append("#" + i + " " + t + ":" + "" + t.getState() + "\n");
      // show trace for BLOCKED
      if (Thread.State.BLOCKED.equals(t.getState())) {
        sb.append(StringUtils.join(t.getStackTrace(), "\n"));
      }
      i++;
    }

    // memory
    Runtime rt = Runtime.getRuntime();
    long total = rt.totalMemory();
    long free = rt.freeMemory();
    long used = total - free;
    sb.append(
        "⣿ MEM USE: "
            + ClientUtils.bytesToMB(used)
            + "M/"
            + ClientUtils.bytesToMB(total)
            + "M"
            + "\n");

    return sb.toString();
  }

  public static String getDebugMixingThreads(WhirlpoolWallet whirlpoolWallet) {
    StringBuilder sb = new StringBuilder().append("\n");
    MixingState mixingState = whirlpoolWallet.getMixingState();
    try {
      sb.append("⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿⣿" + "\n");
      sb.append("⣿ MIXING THREADS:" + "\n");

      String lineFormat =
          "| %25s | %8s | %10s | %10s | %8s | %68s | %14s | %8s | %6s | %19s | %19s |\n";
      sb.append(
          String.format(
                  lineFormat,
                  "STATUS",
                  "SINCE",
                  "ACCOUNT",
                  "BALANCE",
                  "CONFIRMS",
                  "UTXO",
                  "PATH",
                  "POOL",
                  "MIXS",
                  "ACTIVITY",
                  "ERROR")
              + "\n");

      long now = System.currentTimeMillis();
      for (WhirlpoolUtxo whirlpoolUtxo : mixingState.getUtxosMixing()) {
        MixProgress mixProgress = whirlpoolUtxo.getUtxoState().getMixProgress();
        String progress = mixProgress != null ? mixProgress.toString() : "";
        String since = mixProgress != null ? ((now - mixProgress.getSince()) / 1000) + "s" : "";
        UnspentOutput o = whirlpoolUtxo.getUtxo();
        String utxo = o.tx_hash + ":" + o.tx_output_n;
        Long lastActivity = whirlpoolUtxo.getUtxoState().getLastActivity();
        String activity =
            (lastActivity != null ? ClientUtils.dateToString(lastActivity) + " " : "")
                + (whirlpoolUtxo.getUtxoState().getMessage() != null
                    ? whirlpoolUtxo.getUtxoState().getMessage()
                    : "");
        Long lastError = whirlpoolUtxo.getUtxoState().getLastError();
        String error =
            (lastError != null ? ClientUtils.dateToString(lastError) + " " : "")
                + (whirlpoolUtxo.getUtxoState().getError() != null
                    ? whirlpoolUtxo.getUtxoState().getError()
                    : "");
        sb.append(
            String.format(
                    lineFormat,
                    progress,
                    since,
                    whirlpoolUtxo.getAccount().name(),
                    ClientUtils.satToBtc(o.value),
                    o.confirmations,
                    utxo,
                    o.getPath(),
                    mixProgress.getPoolId() != null ? mixProgress.getPoolId() : "-",
                    whirlpoolUtxo.getMixsDone(),
                    activity,
                    error)
                + "\n");
      }
    } catch (Exception e) {
      log.error("", e);
    }
    sb.append(
        "Mixing: "
            + mixingState.getNbMixing()
            + " ("
            + mixingState.getNbMixingMustMix()
            + "+"
            + mixingState.getNbMixingLiquidity()
            + ")\n");
    sb.append(
        "Queued: "
            + mixingState.getNbQueued()
            + " ("
            + mixingState.getNbQueuedMustMix()
            + "+"
            + mixingState.getNbQueuedLiquidity()
            + ")\n");
    return sb.toString();
  }
}
