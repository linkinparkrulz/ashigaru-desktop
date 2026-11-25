package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.wallet.WalletForm;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.wallet.WalletNode;

import java.util.Collection;
import java.util.Map;

/**
 * Where a wallet's funds sit along the Whirlpool pipeline. Read straight from each account's UTXO
 * set, so it costs nothing to recompute and never touches the entry tree the lists are built from.
 */
public record WalletPrivacy(Account deposit, Account premix, Account postmix, Account badbank, Account postmixChange) {
    /**
     * Whether a coin is a Whirlpool output: one that has been through a mix and can be registered
     * for another. Mix outputs arrive on Postmix's receive chain. Anything on its change chain is
     * what was left over from spending a mixed coin: it sits in Postmix, but it is linked to that
     * spend, has no mix count of its own and cannot be remixed, so it never counts as private.
     */
    public static boolean isMixedCoin(Wallet account, WalletNode node) {
        return account.getStandardAccountType() == StandardAccount.WHIRLPOOL_POSTMIX
                && node != null && node.getKeyPurpose() == KeyPurpose.RECEIVE;
    }

    public record Account(long sats, int utxos) {
        static final Account EMPTY = new Account(0, 0);
    }

    public static WalletPrivacy of(WalletForm masterForm) {
        Account deposit = account(masterForm.getWallet());
        Account premix = Account.EMPTY, postmix = Account.EMPTY, badbank = Account.EMPTY, postmixChange = Account.EMPTY;
        for(WalletForm nested : masterForm.getNestedWalletForms()) {
            StandardAccount type = nested.getWallet().getStandardAccountType();
            if(type == StandardAccount.WHIRLPOOL_PREMIX) {
                premix = account(nested.getWallet());
            } else if(type == StandardAccount.WHIRLPOOL_POSTMIX) {
                postmix = account(nested.getWallet());
                long sats = 0;
                int count = 0;
                for(Map.Entry<BlockTransactionHashIndex, WalletNode> utxo : nested.getWallet().getWalletUtxos().entrySet()) {
                    if(!isMixedCoin(nested.getWallet(), utxo.getValue())) {
                        sats += utxo.getKey().getValue();
                        count++;
                    }
                }
                postmixChange = new Account(sats, count);
            } else if(type == StandardAccount.WHIRLPOOL_BADBANK) {
                badbank = account(nested.getWallet());
            }
        }
        return new WalletPrivacy(deposit, premix, postmix, badbank, postmixChange);
    }

    private static Account account(Wallet wallet) {
        Collection<BlockTransactionHashIndex> utxos = wallet.getWalletUtxos().keySet();
        return new Account(utxos.stream().mapToLong(BlockTransactionHashIndex::getValue).sum(), utxos.size());
    }

    public Account get(StandardAccount account) {
        if(account == StandardAccount.WHIRLPOOL_PREMIX) return premix;
        if(account == StandardAccount.WHIRLPOOL_POSTMIX) return postmix;
        if(account == StandardAccount.WHIRLPOOL_BADBANK) return badbank;
        return deposit;
    }

    public long total() {
        return deposit.sats + premix.sats + postmix.sats + badbank.sats;
    }

    /** Mixed coins only: Postmix without the change left over from spending. */
    public long privateSats() {
        return postmix.sats - postmixChange.sats;
    }

    public int privateUtxos() {
        return postmix.utxos - postmixChange.utxos;
    }

    /** Everything that has not been mixed: Deposit, plus spend change sitting in Postmix. */
    public long unmixedSats() {
        return deposit.sats + postmixChange.sats;
    }

    /** Share of the wallet that is mixed coins, 0 to 1. */
    public double privateShare() {
        long total = total();
        return total == 0 ? 0 : (double) privateSats() / total;
    }
}
