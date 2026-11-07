package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.InvalidWalletException;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.wallet.KeystoreController;

/**
 * Builds a watch-only wallet from what a user pastes into the watch-only import: a bare account extended public key
 * (zpub, or an xpub of a BIP84 account) or a single-key output descriptor, with or without a key origin.
 * Only Native SegWit (P2WPKH) is accepted, as the import offers BIP84 Deposit and Postmix accounts.
 */
public final class WatchOnlyWalletBuilder {
    public static final String NATIVE_SEGWIT_ONLY = "Only Native SegWit (zpub / BIP84) accounts are supported.";

    private static final int ACCOUNT_DEPTH = 3;

    private WatchOnlyWalletBuilder() {
    }

    /**
     * @param walletName the name for the new wallet
     * @param input      the pasted text; whitespace is ignored
     * @return a valid watch-only wallet, ready to save
     * @throws InvalidWatchOnlyInputException with a message for the user if the input can't be imported
     */
    public static Wallet build(String walletName, String input) throws InvalidWatchOnlyInputException {
        String raw = input == null ? "" : input.replaceAll("\\s+", "");
        if(raw.isEmpty()) {
            throw new InvalidWatchOnlyInputException("Enter an output descriptor or xpub.");
        }

        //A bare key also parses as an output descriptor, but without a key origin, so build it from the key itself
        Wallet wallet = raw.contains("(") ? fromDescriptor(raw) : fromExtendedKey(raw);
        wallet.setName(walletName);

        try {
            wallet.checkWallet();
        } catch(InvalidWalletException e) {
            throw new InvalidWatchOnlyInputException("This descriptor or xpub does not describe a complete wallet: " + e.getMessage());
        }

        return wallet;
    }

    /**
     * @return null if the input can be imported, otherwise the reason it can't
     */
    public static String validate(String input) {
        try {
            build("validate", input);
            return null;
        } catch(InvalidWatchOnlyInputException e) {
            return e.getMessage();
        }
    }

    private static Wallet fromExtendedKey(String raw) throws InvalidWatchOnlyInputException {
        ExtendedKey.Header header;
        ExtendedKey xpub;
        try {
            header = ExtendedKey.Header.fromExtendedKey(raw);
            xpub = ExtendedKey.fromDescriptor(raw);
        } catch(Exception e) {
            throw new InvalidWatchOnlyInputException(e.getMessage() != null && e.getMessage().contains("network")
                    ? e.getMessage() : "Not a valid output descriptor or xpub.");
        }

        if(header.isPrivateKey()) {
            throw new InvalidWatchOnlyInputException("This is a private key. A watch-only wallet needs the account's extended public key.");
        }

        //A zpub is Native SegWit by definition. A plain xpub carries no script type, and is how many wallets export a
        //BIP84 account, so it is read as Native SegWit as this import asks for. Other key types use other script types.
        ScriptType headerScriptType = header.getDefaultScriptType();
        if(headerScriptType != ScriptType.P2WPKH && headerScriptType != ScriptType.P2PKH) {
            throw new InvalidWatchOnlyInputException(NATIVE_SEGWIT_ONLY);
        }

        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        Keystore keystore = new Keystore();
        keystore.setSource(KeystoreSource.SW_WATCH);
        keystore.setWalletModel(WalletModel.SPARROW);
        keystore.setKeyDerivation(new KeyDerivation(KeystoreController.DEFAULT_WATCH_ONLY_FINGERPRINT, getAccountDerivationPath(xpub)));
        keystore.setExtendedPublicKey(xpub);
        wallet.makeLabelsUnique(keystore);
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        return wallet;
    }

    private static Wallet fromDescriptor(String raw) throws InvalidWatchOnlyInputException {
        OutputDescriptor descriptor;
        try {
            descriptor = OutputDescriptor.getOutputDescriptor(raw);
        } catch(Exception e) {
            throw new InvalidWatchOnlyInputException("Not a valid output descriptor or xpub.");
        }

        if(descriptor.isMultisig() || descriptor.getScriptType() != ScriptType.P2WPKH) {
            throw new InvalidWatchOnlyInputException(NATIVE_SEGWIT_ONLY);
        }

        Wallet wallet;
        try {
            wallet = descriptor.toWallet();
        } catch(Exception e) {
            throw new InvalidWatchOnlyInputException("Not a valid output descriptor or xpub.");
        }

        //A descriptor without a key origin (e.g. wpkh(zpub...)) gives no fingerprint or path, which the wallet needs
        for(Keystore keystore : wallet.getKeystores()) {
            KeyDerivation keyDerivation = keystore.getKeyDerivation();
            String fingerprint = keyDerivation == null ? null : keyDerivation.getMasterFingerprint();
            String path = keyDerivation == null ? null : keyDerivation.getDerivationPath();
            if(fingerprint == null || path == null) {
                keystore.setKeyDerivation(new KeyDerivation(
                        fingerprint == null ? KeystoreController.DEFAULT_WATCH_ONLY_FINGERPRINT : fingerprint,
                        path == null ? getAccountDerivationPath(keystore.getExtendedPublicKey()) : path));
            }
        }

        return wallet;
    }

    /**
     * An account-level key carries its account number, so a Postmix key gets the Postmix path rather than account 0.
     * The coin type follows the configured network.
     */
    static String getAccountDerivationPath(ExtendedKey xpub) {
        int account = 0;
        if(xpub != null && xpub.getKey().getDepth() == ACCOUNT_DEPTH && xpub.getKeyChildNumber().isHardened()) {
            account = xpub.getKeyChildNumber().num();
        }

        return KeyDerivation.writePath(ScriptType.P2WPKH.getDefaultDerivation(account));
    }

    public static class InvalidWatchOnlyInputException extends Exception {
        public InvalidWatchOnlyInputException(String message) {
            super(message);
        }
    }
}
