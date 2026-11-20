package com.sparrowwallet.sparrow.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.samourai.wallet.crypto.AESUtil;
import com.samourai.wallet.util.CharSequenceX;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.StandardAccount;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.control.DicewareWordList;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pairing Sentinel reads. The checks at the bottom restate Sentinel's own parser (PairingPayloadImport.kt: the
 * envelope fields, the seven positions, the xpub check), so a payload that passes here is one it accepts.
 */
class SentinelPairingTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    //The BIP84 account 0 key of the seed above, from the BIP84 test vectors
    private static final String ZPUB = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs";

    private static DeterministicSeed seed(String passphrase) throws Exception {
        return new DeterministicSeed(MNEMONIC, passphrase, 0, DeterministicSeed.Type.BIP39);
    }

    private static Wallet wallet(String passphrase) throws Exception {
        Wallet wallet = new Wallet("pair");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        wallet.getKeystores().add(Keystore.fromSeed(seed(passphrase), ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        return wallet;
    }

    /** The wallet with its three Whirlpool accounts, as a mixing wallet has them */
    private static Wallet whirlpoolWallet() throws Exception {
        Wallet master = wallet("");
        for(StandardAccount account : List.of(StandardAccount.WHIRLPOOL_PREMIX, StandardAccount.WHIRLPOOL_POSTMIX, StandardAccount.WHIRLPOOL_BADBANK)) {
            master.addChildWallet(account);
        }
        return master;
    }

    private static Keystore openKeystore(Wallet wallet, String passphrase) throws Exception {
        Keystore keystore = new Keystore();
        keystore.setSeed(seed(passphrase));
        return keystore;
    }

    @Test
    void aMixingWalletGivesAllSevenEntries() throws Exception {
        Wallet master = whirlpoolWallet();
        SentinelPairing.Slots slots = SentinelPairing.slots(master, openKeystore(master, ""));
        assertEquals(master.getKeystores().get(0).getKeyDerivation().getMasterFingerprint(), slots.fingerprint());
        assertEquals(ZPUB, slots.bip84());
        assertTrue(slots.bip44().startsWith("xpub"), slots.bip44());
        assertTrue(slots.bip49().startsWith("ypub"), slots.bip49());
        for(String key : new String[] {slots.premix(), slots.postmix(), slots.badBank()}) {
            assertTrue(key.startsWith("zpub"), key);
        }
        assertEquals(Set.of("Deposit BIP44", "Deposit BIP49", "Deposit BIP84", "Premix", "Postmix", "Bad Bank"), new HashSet<>(slots.includedNames()));
        assertTrue(slots.missingNames().isEmpty());
        assertEquals(7, slots.asList().size());
        assertEquals(6, new HashSet<>(slots.asList().subList(1, 7)).size(), "six different keys");
    }

    @Test
    void theLegacyKeysAreTheOnesTheSeedGivesAtTheStandardPaths() throws Exception {
        Wallet master = wallet("");
        SentinelPairing.Slots slots = SentinelPairing.slots(master, openKeystore(master, ""));
        Keystore p44 = Keystore.fromSeed(seed(""), ScriptType.P2PKH.getDefaultDerivation());
        Keystore p49 = Keystore.fromSeed(seed(""), ScriptType.P2SH_P2WPKH.getDefaultDerivation());
        assertEquals(p44.getExtendedPublicKey().toString(ExtendedKey.Header.xpub), slots.bip44());
        assertEquals(p49.getExtendedPublicKey().toString(ExtendedKey.Header.ypub), slots.bip49());
        //The well-known BIP44 account 0 key of this seed
        assertEquals("xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj", slots.bip44());
    }

    @Test
    void withoutAnOpenSeedOnlyTheKeysTheWalletHoldsAreGiven() throws Exception {
        Wallet master = whirlpoolWallet();
        SentinelPairing.Slots slots = SentinelPairing.slots(master, null);
        assertNull(slots.bip44());
        assertNull(slots.bip49());
        assertEquals(ZPUB, slots.bip84());
        assertNotNull(slots.premix());
        assertEquals(List.of("Deposit BIP44", "Deposit BIP49"), slots.missingNames());
    }

    @Test
    void aWalletWithNoWhirlpoolAccountsLeavesThemOut() throws Exception {
        Wallet master = wallet("");
        SentinelPairing.Slots slots = SentinelPairing.slots(master, openKeystore(master, ""));
        assertNull(slots.premix());
        assertNull(slots.postmix());
        assertNull(slots.badBank());
        assertEquals(List.of("Premix", "Postmix", "Bad Bank"), slots.missingNames());
        assertTrue(slots.hasAnyKey());
    }

    @Test
    void aBip39PassphraseTheSeedAloneDoesNotCarryNeverGivesWrongLegacyKeys() throws Exception {
        //The wallet was made with a passphrase, but the open keystore holds the bare seed: its BIP84 key would not match the wallet's
        Wallet master = wallet("a passphrase");
        SentinelPairing.Slots wrong = SentinelPairing.slots(master, openKeystore(master, ""));
        assertNull(wrong.bip44());
        assertNull(wrong.bip49());
        assertNotNull(wrong.bip84());

        //With the passphrase in the open seed they are right
        SentinelPairing.Slots right = SentinelPairing.slots(master, openKeystore(master, "a passphrase"));
        assertNotNull(right.bip44());
        assertNotNull(right.bip49());
    }

    @Test
    void theWordsAreThreeListWordsJoinedWithHyphensAndDiffer() {
        Set<String> seen = new HashSet<>();
        for(int i = 0; i < 200; i++) {
            String words = SentinelPairing.newWords();
            assertTrue(seen.add(words), "repeated: " + words);
            //Rebuild the three words by matching against the list, as Sentinel does: a list word may itself hold a hyphen
            List<String> found = split(words);
            assertEquals(SentinelPairing.WORD_COUNT, found.size(), words);
            assertEquals(words, String.join("-", found));
        }
    }

    /** Sentinel's PairingWords.analyze, as far as a complete password goes: the list is prefix-free, so the match at each position is unique */
    private static List<String> split(String text) {
        List<String> found = new java.util.ArrayList<>();
        int pos = 0;
        while(pos < text.length()) {
            String match = null;
            for(String word : DicewareWordList.INSTANCE.getWords()) {
                if(text.startsWith(word, pos)) {
                    assertNull(match, "the list is prefix-free, so only one word can start here");
                    match = word;
                }
            }
            assertNotNull(match, "no list word at " + pos + " of " + text);
            found.add(match);
            pos += match.length();
            if(pos < text.length() && text.charAt(pos) == '-') {
                pos++;
            }
        }
        return found;
    }

    @Test
    void theEnvelopeIsWhatSentinelReads() throws Exception {
        Wallet master = whirlpoolWallet();
        SentinelPairing.Slots slots = SentinelPairing.slots(master, openKeystore(master, ""));
        String words = SentinelPairing.newWords();
        String envelope = SentinelPairing.envelope(slots, words);

        //Sentinel: outer = JSONObject(envelope); declared = external / version; payload = outer.payload
        JsonObject outer = JsonParser.parseString(envelope).getAsJsonObject();
        assertEquals("samourai.wallet.watchonly", outer.get("external").getAsString());
        assertEquals("1.0.0", outer.get("version").getAsString());
        assertTrue(outer.has("payload") && !outer.get("payload").getAsString().isEmpty());

        //Sentinel: AESUtil.decrypt(payload, CharSequenceX(password), DefaultPBKDF2Iterations)
        String decrypted = AESUtil.decrypt(outer.get("payload").getAsString(), new CharSequenceX(words), AESUtil.DefaultPBKDF2Iterations);

        //Sentinel: json.optJSONArray("watch-only"), index 0 the fingerprint, 1..6 the keys, nothing past 7
        JsonArray array = JsonParser.parseString(decrypted).getAsJsonObject().getAsJsonArray("watch-only");
        assertEquals(7, array.size(), "a longer array is reported as unrecognised entries");
        assertEquals(slots.fingerprint(), array.get(0).getAsString());
        Pattern xpub = Pattern.compile("^[xyz]pub[1-9A-HJ-NP-Za-km-z]{100,112}$");
        String[] prefixes = {"xpub", "ypub", "zpub", "zpub", "zpub", "zpub"};
        for(int i = 1; i <= 6; i++) {
            String key = array.get(i).getAsString();
            assertTrue(xpub.matcher(key).matches(), "slot " + i + ": " + key);
            assertTrue(key.startsWith(prefixes[i - 1]), "slot " + i + " is a " + prefixes[i - 1]);
        }
        assertEquals(slots.bip84(), array.get(3).getAsString());
        assertEquals(slots.premix(), array.get(4).getAsString());
        assertEquals(slots.postmix(), array.get(5).getAsString());
        assertEquals(slots.badBank(), array.get(6).getAsString());
    }

    @Test
    void aMissingKeyIsANullEntryNotAShorterArray() throws Exception {
        SentinelPairing.Slots slots = SentinelPairing.slots(wallet(""), null);
        String words = SentinelPairing.newWords();
        JsonObject outer = JsonParser.parseString(SentinelPairing.envelope(slots, words)).getAsJsonObject();
        String decrypted = AESUtil.decrypt(outer.get("payload").getAsString(), new CharSequenceX(words), AESUtil.DefaultPBKDF2Iterations);
        JsonArray array = JsonParser.parseString(decrypted).getAsJsonObject().getAsJsonArray("watch-only");
        assertEquals(7, array.size());
        assertTrue(array.get(1).isJsonNull(), "BIP44 is null, which Sentinel names as skipped");
        assertTrue(array.get(2).isJsonNull());
        assertFalse(array.get(3).isJsonNull());
        assertTrue(array.get(4).isJsonNull());
    }

    @Test
    void onlyTheRightWordsOpenIt() throws Exception {
        SentinelPairing.Slots slots = SentinelPairing.slots(wallet(""), null);
        String words = SentinelPairing.newWords();
        String other = SentinelPairing.newWords();
        String payload = JsonParser.parseString(SentinelPairing.envelope(slots, words)).getAsJsonObject().get("payload").getAsString();

        assertDoesNotThrow(() -> AESUtil.decrypt(payload, new CharSequenceX(words), AESUtil.DefaultPBKDF2Iterations));
        //A wrong password either fails to decrypt or gives something that is not the keys (Sentinel's own test allows both)
        try {
            String wrong = AESUtil.decrypt(payload, new CharSequenceX(other), AESUtil.DefaultPBKDF2Iterations);
            assertFalse(wrong.contains("watch-only"));
        } catch(Exception expected) {
            //fine
        }
    }

    @Test
    void thePublicTextHoldsNoPrivateKeyOrSeed() throws Exception {
        Wallet master = whirlpoolWallet();
        SentinelPairing.Slots slots = SentinelPairing.slots(master, openKeystore(master, ""));
        for(String slot : slots.asList().subList(1, 7)) {
            assertFalse(slot.contains("prv"), slot);
        }
        String words = SentinelPairing.newWords();
        String envelope = SentinelPairing.envelope(slots, words);
        assertFalse(envelope.contains("abandon"));
        assertFalse(envelope.contains(slots.bip84()), "the keys are only inside the ciphertext");
    }
}
