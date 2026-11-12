package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.wallet.WalletForm;

import java.util.Collection;

/**
 * Where a wallet's funds sit along the Whirlpool pipeline. Read straight from each account's UTXO
 * set, so it costs nothing to recompute and never touches the entry tree the lists are built from.
 */
public record WalletPrivacy(Account deposit, Account premix, Account postmix, Account badbank) {
    public record Account(long sats, int utxos) {
        static final Account EMPTY = new Account(0, 0);
    }

    public static WalletPrivacy of(WalletForm masterForm) {
        Account deposit = account(masterForm.getWallet());
        Account premix = Account.EMPTY, postmix = Account.EMPTY, badbank = Account.EMPTY;
        for(WalletForm nested : masterForm.getNestedWalletForms()) {
            StandardAccount type = nested.getWallet().getStandardAccountType();
            if(type == StandardAccount.WHIRLPOOL_PREMIX) {
                premix = account(nested.getWallet());
            } else if(type == StandardAccount.WHIRLPOOL_POSTMIX) {
                postmix = account(nested.getWallet());
            } else if(type == StandardAccount.WHIRLPOOL_BADBANK) {
                badbank = account(nested.getWallet());
            }
        }
        return new WalletPrivacy(deposit, premix, postmix, badbank);
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

    /** Share of the wallet that has been mixed: Postmix over everything, 0 to 1. */
    public double privateShare() {
        long total = total();
        return total == 0 ? 0 : (double) postmix.sats / total;
    }
}
