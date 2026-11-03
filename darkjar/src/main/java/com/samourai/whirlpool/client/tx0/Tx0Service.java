package com.samourai.whirlpool.client.tx0;

import com.samourai.wallet.api.backend.beans.HttpException;
import com.samourai.wallet.api.backend.beans.UnspentOutput;
import com.samourai.wallet.bip69.BIP69OutputComparator;
import com.samourai.wallet.client.BipWallet;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric;
import com.samourai.wallet.send.SendFactoryGeneric;
import com.samourai.wallet.send.provider.UtxoKeyProvider;
import com.samourai.wallet.util.FeeUtil;
import com.samourai.wallet.util.FormatsUtilGeneric;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.utils.BIP69InputComparatorUnspentOutput;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.client.wallet.WhirlpoolWalletConfig;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.samourai.whirlpool.client.whirlpool.beans.Tx0Data;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.rest.Tx0DataRequestV2;
import com.samourai.whirlpool.protocol.rest.Tx0DataResponseV2;
import com.samourai.whirlpool.protocol.util.XorMask;
import java.util.*;
import java8.util.function.ToLongFunction;
import java8.util.stream.StreamSupport;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Tx0Service {
  // the coordinator fee may never exceed 10% of the pool denomination
  private static final long MAX_POOL_FEE_DIVISOR = 10;

  private Logger log = LoggerFactory.getLogger(Tx0Service.class);

  private final Bech32UtilGeneric bech32Util = Bech32UtilGeneric.getInstance();
  private final FormatsUtilGeneric formatsUtilGeneric = FormatsUtilGeneric.getInstance();
  private final XorMask xorMask;
  private final FeeUtil feeUtil = FeeUtil.getInstance();

  private WhirlpoolWalletConfig config;

  public Tx0Service(WhirlpoolWalletConfig config) {
    this.config = config;
    xorMask = XorMask.getInstance(config.getSecretPointFactory());
  }

  private int computeNbPremixMax(
      long premixValue,
      Collection<? extends UnspentOutput> spendFrom,
      long feeValueOrFeeChange,
      int feeTx0,
      Pool pool) {
    NetworkParameters params = config.getNetworkParameters();
    long spendFromBalance = computeSpendFromBalance(spendFrom);

    // compute nbPremix ignoring TX0 fee
    int nbPremixInitial = (int) Math.ceil(spendFromBalance / premixValue);

    // compute nbPremix with TX0 fee
    int nbPremix = nbPremixInitial;
    while (true) {
      // estimate TX0 fee for nbPremix
      long tx0MinerFee = ClientUtils.computeTx0MinerFee(nbPremix, feeTx0, spendFrom, params);
      long spendValue =
          ClientUtils.computeTx0SpendValue(premixValue, nbPremix, feeValueOrFeeChange, tx0MinerFee);
      if (log.isDebugEnabled()) {
        log.debug(
            "computeNbPremixMax: nbPremix="
                + nbPremix
                + " => spendValue="
                + spendValue
                + ", tx0MinerFee="
                + tx0MinerFee
                + ", spendFromBalance="
                + spendFromBalance
                + ", nbPremixInitial="
                + nbPremixInitial);
      }
      if (spendFromBalance < spendValue) {
        // if UTXO balance is insufficient, try with less nbPremix
        nbPremix--;
      } else {
        // nbPremix found
        break;
      }
    }
    // no negative value
    if (nbPremix < 0) {
      nbPremix = 0;
    }
    nbPremix = capNbPremix(nbPremix, pool);
    return nbPremix;
  }

  private long computeOutputsSum(Tx0Preview tx0Preview) {
    long tx0SpendValue =
        ClientUtils.computeTx0SpendValue(
            tx0Preview.getPremixValue(),
            tx0Preview.getNbPremix(),
            tx0Preview.computeFeeValueOrFeeChange(),
            tx0Preview.getTx0MinerFee());
    return tx0SpendValue + tx0Preview.getChangeValue();
  }

  public Tx0Previews tx0Previews(Collection<UnspentOutput> spendFroms, Tx0Config tx0Config)
      throws Exception {

    // fetch fresh Tx0Data
    Map<String, Tx0Preview> tx0PreviewsByPoolId = new LinkedHashMap<String, Tx0Preview>();
    Collection<Tx0Data> tx0Datas = fetchTx0Data(config.getPartner());
    for (Tx0Data tx0Data : tx0Datas) {
      String poolId = tx0Data.getPoolId();
      try {
        Pool pool = tx0Config.getPoolSupplier().findPoolById(poolId);
        Tx0Param tx0Param =
            tx0Config
                .getTx0ParamService()
                .getTx0Param(pool, tx0Config.getTx0FeeTarget(), tx0Config.getMixFeeTarget());

        Tx0Preview tx0Preview = tx0Preview(spendFroms, tx0Config, tx0Param, tx0Data);
        tx0PreviewsByPoolId.put(poolId, tx0Preview);
      } catch (Exception e) {
        if (log.isDebugEnabled()) {
          log.debug("Pool not eligible for tx0: " + poolId, e.getMessage());
        }
      }
    }
    return new Tx0Previews(tx0PreviewsByPoolId);
  }

  protected Tx0Preview tx0Preview(
      Collection<UnspentOutput> spendFroms, Tx0Config tx0Config, Tx0Param tx0Param, Tx0Data tx0Data)
      throws Exception {

    // check balance min
    final long spendFromBalanceMin = tx0Param.getSpendFromBalanceMin();
    long spendFromBalance = computeSpendFromBalance(spendFroms);
    if (spendFromBalance < spendFromBalanceMin) {
      throw new NotifiableException(
          "Insufficient utxo value for Tx0: " + spendFromBalance + " < " + spendFromBalanceMin);
    }

    // check fee (duplicate safety check)
    int feeTx0 = tx0Param.getFeeTx0();
    if (feeTx0 < config.getFeeMin()) {
      throw new NotifiableException("Invalid fee for Tx0: " + feeTx0 + " < " + config.getFeeMin());
    }
    if (feeTx0 > config.getFeeMax()) {
      throw new NotifiableException("Invalid fee for Tx0: " + feeTx0 + " > " + config.getFeeMax());
    }

    // check premixValue (duplicate safety check)
    long premixValue = tx0Param.getPremixValue();
    if (!tx0Param.getPool().checkInputBalance(premixValue, false)) {
      throw new NotifiableException("Invalid premixValue for Tx0: " + premixValue);
    }

    // check coordinator fee: the tx0 data must not ask for more than the advertised pool fee,
    // and the pool fee itself must stay within a sane fraction of the denomination
    Pool pool = tx0Param.getPool();
    if (tx0Data.getFeeValue() > pool.getFeeValue()) {
      throw new NotifiableException(
          "Invalid coordinator fee for Tx0: " + tx0Data.getFeeValue() + " > pool fee " + pool.getFeeValue());
    }
    if (pool.getFeeValue() > pool.getDenomination() / MAX_POOL_FEE_DIVISOR) {
      throw new NotifiableException(
          "Invalid pool fee for Tx0: " + pool.getFeeValue() + " for denomination " + pool.getDenomination());
    }

    NetworkParameters params = config.getNetworkParameters();
    long feeValueOrFeeChange = tx0Data.computeFeeValueOrFeeChange();
    int nbPremix = computeNbPremixMax(premixValue, spendFroms, feeValueOrFeeChange, feeTx0, pool);
    long tx0MinerFee = ClientUtils.computeTx0MinerFee(nbPremix, feeTx0, spendFroms, params);
    long premixMinerFee = tx0Param.getPremixValue() - tx0Param.getPool().getDenomination();
    long mixMinerFee = nbPremix * premixMinerFee;
    long spendValue =
        ClientUtils.computeTx0SpendValue(premixValue, nbPremix, feeValueOrFeeChange, tx0MinerFee);
    long changeValue = spendFromBalance - spendValue;

    Tx0Preview tx0Preview =
        new Tx0Preview(
            pool,
            tx0Data,
            tx0MinerFee,
            mixMinerFee,
            premixMinerFee,
            tx0Param.getFeeTx0(),
            tx0Param.getFeePremix(),
            premixValue,
            changeValue,
            nbPremix);

    // verify outputsSum
    long outputsSum = computeOutputsSum(tx0Preview);
    if (outputsSum != spendFromBalance) {
      throw new Exception(
          "Invalid outputsSum for tx0: "
              + outputsSum
              + " vs "
              + spendFromBalance
              + " for tx0Preview=["
              + tx0Preview
              + "]");
    }
    return tx0Preview;
  }

  /** Generate maxOutputs premixes outputs max. */
  public Tx0 tx0(
      Collection<UnspentOutput> spendFroms,
      BipWallet depositWallet,
      BipWallet premixWallet,
      BipWallet postmixWallet,
      BipWallet badbankWallet,
      Pool pool,
      Tx0Config tx0Config,
      UtxoKeyProvider utxoKeyProvider)
      throws Exception {

    // compute & preview
    Tx0Previews tx0Previews = tx0Previews(spendFroms, tx0Config);
    Tx0Preview tx0Preview = tx0Previews.getTx0Preview(pool.getPoolId());
    if (tx0Preview == null) {
      throw new NotifiableException("Tx0 not possible for pool: " + pool.getPoolId());
    }

    log.info(
        " • Tx0: spendFrom="
            + spendFroms
            + ", changeWallet="
            + tx0Config.getChangeWallet().name()
            + ", tx0Preview={"
            + tx0Preview
            + "}");

    return tx0(
        spendFroms,
        depositWallet,
        premixWallet,
        postmixWallet,
        badbankWallet,
        tx0Config,
        tx0Preview,
        utxoKeyProvider);
  }

  public Tx0 tx0(
      Collection<UnspentOutput> spendFroms,
      BipWallet depositWallet,
      BipWallet premixWallet,
      BipWallet postmixWallet,
      BipWallet badbankWallet,
      Tx0Config tx0Config,
      Tx0Preview tx0Preview,
      UtxoKeyProvider utxoKeyProvider)
      throws Exception {
    NetworkParameters params = config.getNetworkParameters();

    Tx0Data tx0Data = tx0Preview.getTx0Data();

    // compute opReturnValue for feePaymentCode and feePayload
    String feeOrBackAddressBech32;
    if (tx0Data.getFeeValue() > 0) {
      // pay to fee
      feeOrBackAddressBech32 = tx0Data.getFeeAddress();
      if (log.isDebugEnabled()) {
        log.debug("feeAddressDestination: samourai => " + feeOrBackAddressBech32);
      }
    } else {
      // pay to deposit
      feeOrBackAddressBech32 = bech32Util.toBech32(depositWallet.getNextChangeAddress(), params);
      if (log.isDebugEnabled()) {
        log.debug("feeAddressDestination: back to deposit => " + feeOrBackAddressBech32);
      }
    }

    // sort inputs now, we need to know the first input for OP_RETURN encode
    List<UnspentOutput> sortedSpendFroms = new LinkedList<UnspentOutput>();
    sortedSpendFroms.addAll(spendFroms);
    Collections.sort(sortedSpendFroms, new BIP69InputComparatorUnspentOutput());

    // compute feePayloadMasked
    UnspentOutput firstInput = sortedSpendFroms.get(0);
    ECKey firstInputKey = utxoKeyProvider._getPrivKey(firstInput.tx_hash, firstInput.tx_output_n);
    String feePaymentCode = tx0Data.getFeePaymentCode();
    byte[] feePayload = tx0Data.getFeePayload();
    byte[] feePayloadMasked =
        xorMask.mask(
            feePayload,
            feePaymentCode,
            params,
            firstInputKey.getPrivKeyBytes(),
            firstInput.computeOutpoint(params));
    if (log.isDebugEnabled()) {
      log.debug("feePayloadHex=" + Hex.toHexString(feePayload));
    }
    return tx0(
        sortedSpendFroms,
        depositWallet,
        premixWallet,
        postmixWallet,
        badbankWallet,
        tx0Config,
        tx0Preview,
        feePayloadMasked,
        feeOrBackAddressBech32,
        utxoKeyProvider);
  }

  protected Tx0 tx0(
      List<UnspentOutput> sortedSpendFroms,
      BipWallet depositWallet,
      BipWallet premixWallet,
      BipWallet postmixWallet,
      BipWallet badbankWallet,
      Tx0Config tx0Config,
      Tx0Preview tx0Preview,
      byte[] feePayloadMasked,
      String feeOrBackAddressBech32,
      UtxoKeyProvider utxoKeyProvider)
      throws Exception {

    // find change wallet
    BipWallet changeWallet;
    switch (tx0Config.getChangeWallet()) {
      case PREMIX:
        changeWallet = premixWallet;
        break;
      case POSTMIX:
        changeWallet = postmixWallet;
        break;
      case BADBANK:
        changeWallet = badbankWallet;
        break;
      default:
        changeWallet = depositWallet;
        break;
    }

    //
    // tx0
    //

    Tx0 tx0 =
        buildTx0(
            sortedSpendFroms,
            premixWallet,
            tx0Preview,
            feePayloadMasked,
            feeOrBackAddressBech32,
            changeWallet,
            config.getNetworkParameters(),
            utxoKeyProvider);

    Transaction tx = tx0.getTx();
    final String hexTx = new String(Hex.encode(tx.bitcoinSerialize()));
    final String strTxHash = tx.getHashAsString();

    tx.verify();
    // System.out.println(tx);
    if (log.isDebugEnabled()) {
      log.debug("Tx0 hash: " + strTxHash);
      log.debug("Tx0 hex: " + hexTx);
      long feePrice = tx0Preview.getTx0MinerFee() / tx.getVirtualTransactionSize();
      log.debug("Tx0 size: " + tx.getVirtualTransactionSize() + "b, feePrice=" + feePrice + "s/b");
    }
    return tx0;
  }

  private int capNbPremix(int nbPremix, Pool pool) {
    int maxOutputs = config.getTx0MaxOutputs();
    if (maxOutputs > 0) {
      nbPremix = Math.min(maxOutputs, nbPremix); // cap with maxOutputs
    }
    nbPremix = Math.min(pool.getTx0MaxOutputs(), nbPremix); // cap with pool.tx0MaxOutputs
    return nbPremix;
  }

  protected long computeSpendFromBalance(Collection<? extends UnspentOutput> spendFroms) {
    long balance =
        StreamSupport.stream(spendFroms)
            .mapToLong(
                new ToLongFunction<UnspentOutput>() {
                  @Override
                  public long applyAsLong(UnspentOutput unspentOutput) {
                    return unspentOutput.value;
                  }
                })
            .sum();
    return balance;
  }

  protected Tx0 buildTx0(
      Collection<UnspentOutput> sortedSpendFroms,
      BipWallet premixWallet,
      Tx0Preview tx0Preview,
      byte[] feePayloadMasked,
      String feeOrBackAddressBech32,
      BipWallet changeWallet,
      NetworkParameters params,
      UtxoKeyProvider utxoKeyProvider)
      throws Exception {

    long premixValue = tx0Preview.getPremixValue();
    long feeValueOrFeeChange = tx0Preview.computeFeeValueOrFeeChange();
    int nbPremix = capNbPremix(tx0Preview.getNbPremix(), tx0Preview.getPool());
    long changeValueTotal = tx0Preview.getChangeValue();

    // verify

    if (sortedSpendFroms.size() <= 0) {
      throw new IllegalArgumentException("spendFroms should be > 0");
    }

    if (feeValueOrFeeChange <= 0) {
      throw new IllegalArgumentException("feeValueOrFeeChange should be > 0");
    }

    // at least 1 premix
    if (nbPremix < 1) {
      throw new Exception("Invalid nbPremix=" + nbPremix);
    }

    // verify outputsSum
    long outputsSum = computeOutputsSum(tx0Preview);
    long spendFromBalance = computeSpendFromBalance(sortedSpendFroms);
    if (outputsSum != spendFromBalance) {
      throw new Exception("Invalid outputsSum for tx0: " + outputsSum + " vs " + spendFromBalance);
    }

    //
    // tx0
    //
    //
    // make tx:
    // 5 spendTo outputs
    // SW fee
    // change
    // OP_RETURN
    //
    List<TransactionOutput> outputs = new ArrayList<TransactionOutput>();
    Transaction tx = new Transaction(params);

    //
    // premix outputs
    //
    List<TransactionOutput> premixOutputs = new ArrayList<TransactionOutput>();
    for (int j = 0; j < nbPremix; j++) {
      // send to PREMIX
      HD_Address toAddress = premixWallet.getNextAddress();
      String toAddressBech32 = bech32Util.toBech32(toAddress, params);
      if (log.isDebugEnabled()) {
        log.debug(
            "Tx0 out (premix): address="
                + toAddressBech32
                + ", path="
                + toAddress.toJSON().get("path")
                + " ("
                + premixValue
                + " sats)");
      }

      TransactionOutput txOutSpend =
          bech32Util.getTransactionOutput(toAddressBech32, premixValue, params);
      outputs.add(txOutSpend);
      premixOutputs.add(txOutSpend);
    }

    //
    // 1 or 2 change output(s)
    //
    List<TransactionOutput> changeOutputs = new LinkedList<TransactionOutput>();
    if (changeValueTotal > 0) {
      HD_Address changeAddress = changeWallet.getNextAddress();
      String changeAddressBech32 = bech32Util.toBech32(changeAddress, params);
      TransactionOutput changeOutput =
          bech32Util.getTransactionOutput(changeAddressBech32, changeValueTotal, params);
      outputs.add(changeOutput);
      changeOutputs.add(changeOutput);
      if (log.isDebugEnabled()) {
        log.debug(
            "Tx0 out (change): address="
                + changeAddressBech32
                + ", path="
                + changeAddress.toJSON().get("path")
                + " ("
                + changeValueTotal
                + " sats)");
      }
    } else {
      if (log.isDebugEnabled()) {
        log.debug("Tx0: spending whole utx0, no change");
      }
      if (changeValueTotal < 0) {
        throw new Exception(
            "Negative change detected, please report this bug. tx0Preview=" + tx0Preview);
      }
    }

    // samourai fee (or back deposit)
    TransactionOutput txSWFee =
        bech32Util.getTransactionOutput(feeOrBackAddressBech32, feeValueOrFeeChange, params);
    outputs.add(txSWFee);
    if (log.isDebugEnabled()) {
      log.debug(
          "Tx0 out (fee): feeAddress="
              + feeOrBackAddressBech32
              + " ("
              + feeValueOrFeeChange
              + " sats)");
    }

    // add OP_RETURN output
    Script op_returnOutputScript =
        new ScriptBuilder().op(ScriptOpCodes.OP_RETURN).data(feePayloadMasked).build();
    TransactionOutput txFeeOutput =
        new TransactionOutput(params, null, Coin.valueOf(0L), op_returnOutputScript.getProgram());
    outputs.add(txFeeOutput);
    if (log.isDebugEnabled()) {
      log.debug("Tx0 out (OP_RETURN): " + feePayloadMasked.length + " bytes");
    }
    if (feePayloadMasked.length != WhirlpoolProtocol.FEE_PAYLOAD_LENGTH) {
      throw new Exception(
          "Invalid opReturnValue length detected, please report this bug. opReturnValue="
              + feePayloadMasked
              + " vs "
              + WhirlpoolProtocol.FEE_PAYLOAD_LENGTH);
    }

    // all outputs
    Collections.sort(outputs, new BIP69OutputComparator());
    for (TransactionOutput to : outputs) {
      tx.addOutput(to);
    }

    // all inputs
    for (UnspentOutput spendFrom : sortedSpendFroms) {
      TransactionInput input = spendFrom.computeSpendInput(params);
      tx.addInput(input);
      if (log.isDebugEnabled()) {
        log.debug("Tx0 in: utxo=" + spendFrom);
      }
    }

    signTx0(tx, utxoKeyProvider);
    tx.verify();

    Tx0 tx0 = new Tx0(tx0Preview, tx, premixOutputs, changeOutputs);
    return tx0;
  }

  protected void signTx0(Transaction tx, UtxoKeyProvider utxoKeyProvider) throws Exception {
    SendFactoryGeneric.getInstance().signTransaction(tx, utxoKeyProvider);
  }

  protected Collection<Tx0Data> fetchTx0Data(String partnerId) throws Exception {
    Collection<Tx0Data> tx0Datas = new LinkedList<Tx0Data>();
    try {
      Tx0DataRequestV2 tx0DataRequest = new Tx0DataRequestV2(config.getScode(), partnerId);
      Tx0DataResponseV2 tx0DatasResponse =
          config.getServerApi().fetchTx0Data(tx0DataRequest).blockingFirst().get();
      for (Tx0DataResponseV2.Tx0Data tx0DataItem : tx0DatasResponse.tx0Datas) {
        Tx0Data tx0Data = new Tx0Data(tx0DataItem);
        tx0Datas.add(tx0Data);
      }
      return tx0Datas;
    } catch (HttpException e) {
      throw ClientUtils.wrapRestError(e);
    }
  }
}
