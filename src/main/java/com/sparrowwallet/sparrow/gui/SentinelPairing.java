package com.sparrowwallet.sparrow.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.samourai.wallet.crypto.AESUtil;
import com.samourai.wallet.util.CharSequenceX;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.MnemonicException;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.control.DicewareWordList;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What Tools > Pair with Sentinel hands over, apart from the screen. Sentinel (the Dojo Bay edition) reads a watch-only
 * pairing made for Ashigaru and Samourai: a JSON envelope {@code {payload, external, version}} whose payload is the
 * keys, encrypted with Samourai's AES under a one-time password of three words from the EFF large list joined by "-".
 * The decrypted JSON is {@code {"watch-only": [fingerprint, BIP44 xpub, BIP49 ypub, BIP84 zpub, Premix, Postmix, Bad Bank]}};
 * a key that is not there is null, and Sentinel names it as skipped. Only public keys are ever put in it.
 */
public final class SentinelPairing {
    /** The format id Sentinel looks for in {@code external} */
    public static final String EXTERNAL = "samourai.wallet.watchonly";
    /** The newest pairing version Sentinel reads in full */
    public static final String VERSION = "1.0.0";
    public static final int WORD_COUNT = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SentinelPairing() {}

    /** The seven entries of the watch-only array, in Sentinel's order. A key that could not be had is null. */
    public record Slots(String fingerprint, String bip44, String bip49, String bip84, String premix, String postmix, String badBank) {
        /** What goes into the array: index 0 is the fingerprint, the six after it are keys */
        public List<String> asList() {
            return Arrays.asList(fingerprint, bip44, bip49, bip84, premix, postmix, badBank);
        }

        private static final List<String> NAMES = List.of("Deposit BIP44", "Deposit BIP49", "Deposit BIP84", "Premix", "Postmix", "Bad Bank");

        /** The keys that are in the pairing, by the names Sentinel shows */
        public List<String> includedNames() {
            return names(true);
        }

        /** The keys that are not, by the same names */
        public List<String> missingNames() {
            return names(false);
        }

        private List<String> names(boolean present) {
            List<String> keys = asList().subList(1, 7);
            List<String> names = new ArrayList<>();
            for(int i = 0; i < keys.size(); i++) {
                if((keys.get(i) != null) == present) {
                    names.add(NAMES.get(i));
                }
            }
            return names;
        }

        public boolean hasAnyKey() {
            return !includedNames().isEmpty();
        }
    }

    /**
     * Collects the keys of a master wallet.
     *
     * @param master    the master (Deposit) wallet; its own key and its Whirlpool accounts are public data
     * @param decrypted a copy of the master keystore with its seed or master key open, or null. Only the Deposit BIP44 and BIP49 keys need
     *                  it, as the wallet keeps just its BIP84 key. They are left out, never guessed, unless the seed gives back the wallet's
     *                  own BIP84 key, so a wallet with a BIP39 passphrase the seed alone does not carry cannot hand out the wrong keys.
     */
    public static Slots slots(Wallet master, Keystore decrypted) {
        Keystore keystore = master.getKeystores().get(0);
        String fingerprint = keystore.getKeyDerivation() == null ? null : keystore.getKeyDerivation().getMasterFingerprint();

        String bip84 = master.getScriptType() == ScriptType.P2WPKH && keystore.getExtendedPublicKey() != null
                ? write(keystore.getExtendedPublicKey(), ScriptType.P2WPKH) : null;
        String bip44 = null;
        String bip49 = null;
        if(decrypted != null && bip84 != null) {
            int account = master.getAccountIndex();
            try {
                Keystore p84 = derive(decrypted, ScriptType.P2WPKH.getDefaultDerivation(account));
                if(p84 != null && bip84.equals(write(p84.getExtendedPublicKey(), ScriptType.P2WPKH))) {
                    Keystore p44 = derive(decrypted, ScriptType.P2PKH.getDefaultDerivation(account));
                    Keystore p49 = derive(decrypted, ScriptType.P2SH_P2WPKH.getDefaultDerivation(account));
                    bip44 = p44 == null ? null : write(p44.getExtendedPublicKey(), ScriptType.P2PKH);
                    bip49 = p49 == null ? null : write(p49.getExtendedPublicKey(), ScriptType.P2SH_P2WPKH);
                }
            } catch(MnemonicException | RuntimeException e) {
                //No legacy keys, and nothing about why goes anywhere: the seed is involved
            }
        }

        return new Slots(fingerprint, bip44, bip49, bip84, childKey(master, StandardAccount.WHIRLPOOL_PREMIX),
                childKey(master, StandardAccount.WHIRLPOOL_POSTMIX), childKey(master, StandardAccount.WHIRLPOOL_BADBANK));
    }

    private static String childKey(Wallet master, StandardAccount account) {
        Wallet child = master.getChildWallet(account);
        if(child == null || child.getKeystores().isEmpty() || child.getKeystores().get(0).getExtendedPublicKey() == null) {
            return null;
        }
        return write(child.getKeystores().get(0).getExtendedPublicKey(), ScriptType.P2WPKH);
    }

    private static Keystore derive(Keystore decrypted, List<ChildNumber> derivation) throws MnemonicException {
        if(decrypted.hasSeed() && !decrypted.getSeed().isEncrypted()) {
            return Keystore.fromSeed(decrypted.getSeed(), derivation);
        }
        if(decrypted.hasMasterPrivateExtendedKey() && !decrypted.getMasterPrivateExtendedKey().isEncrypted()) {
            return Keystore.fromMasterPrivateExtendedKey(decrypted.getMasterPrivateExtendedKey(), derivation);
        }
        return null;
    }

    /** The key in the header Sentinel expects for the type: xpub, ypub or zpub (tpub, upub, vpub on a test network) */
    private static String write(ExtendedKey key, ScriptType type) {
        return key.toString(ExtendedKey.Header.fromScriptType(type, false));
    }

    /** A new one-time password: three words of the EFF large list at random, joined with "-" */
    public static String newWords() {
        List<String> list = DicewareWordList.INSTANCE.getWords();
        StringBuilder words = new StringBuilder();
        for(int i = 0; i < WORD_COUNT; i++) {
            if(i > 0) {
                words.append('-');
            }
            words.append(list.get(RANDOM.nextInt(list.size())));
        }
        return words.toString();
    }

    /** The text for the QR (and for Sentinel's paste box): the keys, encrypted under the words */
    public static String envelope(Slots slots, String words) {
        JsonArray array = new JsonArray();
        for(String slot : slots.asList()) {
            array.add(slot == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(slot));
        }
        JsonObject inner = new JsonObject();
        inner.add("watch-only", array);

        String encrypted;
        try {
            encrypted = AESUtil.encrypt(inner.toString(), new CharSequenceX(words), AESUtil.DefaultPBKDF2Iterations);
        } catch(Exception e) {
            throw new IllegalStateException("The pairing could not be encrypted", e);
        }

        JsonObject outer = new JsonObject();
        outer.addProperty("payload", encrypted);
        outer.addProperty("external", EXTERNAL);
        outer.addProperty("version", VERSION);
        return outer.toString();
    }
}
