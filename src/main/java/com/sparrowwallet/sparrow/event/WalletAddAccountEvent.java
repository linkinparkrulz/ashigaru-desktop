package com.sparrowwallet.sparrow.event;

import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;

public class WalletAddAccountEvent {
    private final Wallet wallet;
    private final StandardAccount account;

    public WalletAddAccountEvent(Wallet wallet, StandardAccount account) {
        this.wallet = wallet;
        this.account = account;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public StandardAccount getAccount() {
        return account;
    }
}
