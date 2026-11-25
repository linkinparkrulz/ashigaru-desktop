package com.sparrowwallet.sparrow.testutil;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.*;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Made-up wallets for documentation pictures and tests: a wallet from the well-known "abandon" test seed, with the Whirlpool accounts and a few
 * coins in them that were never on any chain. Nothing here belongs to a real wallet.
 */
public final class SampleWallets {
    public static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    private SampleWallets() {}

    /** A master wallet (the Deposit account) with its Premix, Postmix and Badbank accounts, no coins yet */
    public static Wallet whirlpoolWallet(String name) throws Exception {
        Wallet wallet = new Wallet(name);
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        wallet.getKeystores().add(Keystore.fromSeed(new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39),
                ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        for(StandardAccount account : StandardAccount.WHIRLPOOL_ACCOUNTS) {
            wallet.addChildWallet(account);
        }
        return wallet;
    }

    /**
     * Pays {@code sats} to the address at {@code index} of the wallet's receive chain, in a transaction from an invented outpoint, and records the
     * coin and the transaction on the wallet the way a server's answer would.
     */
    public static BlockTransactionHashIndex fund(Wallet wallet, int index, long sats, int height, Date date, String label) {
        WalletNode purpose = wallet.getNode(KeyPurpose.RECEIVE);
        purpose.fillToIndex(wallet, index + 5);
        WalletNode node = purpose.getChildren().stream().filter(child -> child.getIndex() == index).findFirst().orElseThrow();

        Transaction tx = new Transaction();
        tx.addInput(Sha256Hash.of(("invented " + wallet.getName() + index + sats).getBytes(StandardCharsets.UTF_8)), 0, new com.sparrowwallet.drongo.protocol.Script(new byte[0]));
        tx.addOutput(sats, node.getAddress());
        tx.addOutput(sats / 3 + 1_000, new ECKey());
        Sha256Hash txid = tx.getTxId();
        BlockTransaction blockTx = new BlockTransaction(txid, height, date, 1_240L, tx);
        blockTx.setLabel(label);
        wallet.updateTransactions(Map.of(txid, blockTx));

        BlockTransactionHashIndex coin = new BlockTransactionHashIndex(txid, height, date, 1_240L, 0, sats);
        coin.setLabel(label);
        node.updateTransactionOutputs(wallet, Set.of(coin));
        return coin;
    }
}
