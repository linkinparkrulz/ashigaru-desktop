package com.samourai.wallet.send.spend;

import com.samourai.wallet.SamouraiWalletConst;
import com.samourai.wallet.hd.AddressType;
import com.samourai.wallet.send.MyTransactionOutPoint;
import com.samourai.wallet.send.SendFactoryGeneric;
import com.samourai.wallet.send.UTXO;
import com.samourai.wallet.send.provider.UtxoProvider;
import com.samourai.wallet.send.beans.SpendError;
import com.samourai.wallet.send.beans.SpendTx;
import com.samourai.wallet.send.beans.SpendType;
import com.samourai.wallet.send.exceptions.MakeTxException;
import com.samourai.wallet.send.exceptions.SignTxException;
import com.samourai.wallet.send.exceptions.SpendException;
import com.samourai.wallet.util.FeeUtil;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolAccount;

import org.apache.commons.lang3.tuple.Triple;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;

public class SpendSelectionSimple extends SpendSelection {
    private static final Logger log = LoggerFactory.getLogger(SpendSelectionSimple.class);

    public SpendSelectionSimple(Collection<UTXO> utxos) {
        super(SpendType.SIMPLE);

        for (UTXO utxo : utxos) {
            addSelectedUTXO(utxo);
        }
    }

    public static SpendSelectionSimple computeSpendSingle(Collection<UTXO> utxos, long amount, NetworkParameters params, BigInteger feePerKb) {
        // sort in ascending order by value
        List<UTXO> sortedUtxos = new ArrayList<>(utxos);
        Collections.sort(sortedUtxos, new UTXO.UTXOComparator());
        Collections.reverse(sortedUtxos);

        // get smallest 1 UTXO > than spend + fee + dust
        for (UTXO u : sortedUtxos) {
            Triple<Integer, Integer, Integer> outpointTypes = FeeUtil.getInstance().getOutpointCount(new Vector(u.getOutpoints()), params);
            if (u.getValue() >= (amount + SamouraiWalletConst.bDust.longValue() + FeeUtil.getInstance().estimatedFeeSegwit(outpointTypes.getLeft(), outpointTypes.getMiddle(), outpointTypes.getRight(), 2, feePerKb).longValue())) {
                if (log.isDebugEnabled()) {
                    log.debug("spend type:" + SpendType.SIMPLE);
                    log.debug("single output");
                    log.debug("amount:" + amount);
                    log.debug("value selected:" + u.getValue());
                    log.debug("total value selected:" + u.getValue());
                    log.debug("nb inputs:" + u.getOutpoints().size());
                }
                return new SpendSelectionSimple(List.of(u));
            }
        }
        return null;
    }

    public static SpendSelectionSimple computeSpendMultiple(Collection<UTXO> utxos, long amount, NetworkParameters params, BigInteger feePerKb) {
        // sort in descending order by value
        List<UTXO> sortedUtxos = new ArrayList<>(utxos);
        Collections.sort(sortedUtxos, new UTXO.UTXOComparator());
        int selected = 0;
        int p2pkh = 0;
        int p2sh_p2wpkh = 0;
        int p2wpkh = 0;

        // get largest UTXOs > than spend + fee + dust
        long totalValueSelected = 0L;
        List<UTXO> selectedUTXO = new ArrayList<UTXO>();
        for (UTXO u : sortedUtxos) {

            selectedUTXO.add(u);
            totalValueSelected += u.getValue();
            selected += u.getOutpoints().size();

//                            Log.d("SendActivity", "value selected:" + u.getValue());
//                            Log.d("SendActivity", "total value selected/threshold:" + totalValueSelected + "/" + (amount + SamouraiWallet.bDust.longValue() + FeeUtil.getInstance().estimatedFee(selected, 2).longValue()));

            Triple<Integer, Integer, Integer> outpointTypes = FeeUtil.getInstance().getOutpointCount(new Vector<MyTransactionOutPoint>(u.getOutpoints()), params);
            p2pkh += outpointTypes.getLeft();
            p2sh_p2wpkh += outpointTypes.getMiddle();
            p2wpkh += outpointTypes.getRight();
            if (totalValueSelected >= (amount + SamouraiWalletConst.bDust.longValue() + FeeUtil.getInstance().estimatedFeeSegwit(p2pkh, p2sh_p2wpkh, p2wpkh, 2, feePerKb).longValue())) {
                if (log.isDebugEnabled()) {
                    log.debug("spend type:" + SpendType.SIMPLE);
                    log.debug("multiple outputs");
                    log.debug("amount:" + amount);
                    log.debug("total value selected:" + totalValueSelected);
                    log.debug("nb inputs:" + selected);
                }
                return new SpendSelectionSimple(selectedUTXO);
            }
        }
        return null;
    }

    @Override
    public SpendTx spendTx(long amount, String address, AddressType addressType, WhirlpoolAccount account, boolean rbfOptIn, NetworkParameters params, BigInteger feePerKb, Runnable restoreChangeIndexes, UtxoProvider utxoProvider) throws SpendException {
        List<MyTransactionOutPoint> outpoints = getSpendFrom();
        Triple<Integer, Integer, Integer> outpointTypes = FeeUtil.getInstance().getOutpointCount(new Vector(outpoints), params);
        BigInteger fee;
        long change;
        Map<String, Long> receivers = new HashMap<>();
        if (amount == getTotalValueSelected()) {
            // NO CHANGE = 1 output

            // estimate fee
            fee = FeeUtil.getInstance().estimatedFeeSegwit(outpointTypes.getLeft(), outpointTypes.getMiddle(), outpointTypes.getRight(), 1, feePerKb);

            // adjust amount
            amount -= fee.longValue();

            // add recipient output
            receivers.put(address, amount);
            change = 0;
        } else{
            // WITH CHANGE = 2 outputs

            // estimate fee
            fee = FeeUtil.getInstance().estimatedFeeSegwit(outpointTypes.getLeft(), outpointTypes.getMiddle(), outpointTypes.getRight(), 2, feePerKb);

            // compute change
            change = computeChange(amount, fee);

            // add recipient output
            receivers.put(address, amount);

            // add change output
            String changeAddress = utxoProvider.getChangeAddress(account, addressType);
            receivers.put(changeAddress, change);
        }

        //
        // fee sanity check
        //
        if (restoreChangeIndexes != null) {
            restoreChangeIndexes.run(); // TODO zeroleak
        }
        return new SpendTx(addressType, amount, fee.longValue(), change, this, receivers, rbfOptIn, utxoProvider, params);
    }
}
