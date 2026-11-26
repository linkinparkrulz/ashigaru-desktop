package com.sparrowwallet.sparrow.net.dojo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.paynym.Bip47Signing;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Dojo Bay directory read as Sentinel's Dojo Bay edition reads it: the operator file first, then each listing on its own */
class DojoBayDirectoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final String BASE = "http://dojobayeryasshgghz537de5ckgd5hhi4z5sdeil3roeh65fwhdnu2yd.onion/";
    private static final String PAIRING_URL = "http://pairdojoabc.onion/v2";
    private static final String INDEXER = "tcp://fulcrumabc123.onion:50001";

    private static Wallet wallet(String mnemonic) throws Exception {
        Wallet wallet = new Wallet("signer");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        wallet.getKeystores().add(Keystore.fromSeed(new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39),
                ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        return wallet;
    }

    private static Wallet operator() throws Exception {
        return wallet("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about");
    }

    private static Wallet someoneElse() throws Exception {
        return wallet("legal winner thank year wave sausage worth useful legal winner thank yellow");
    }

    private static String block(Wallet signer, String message) {
        ECKey key = Bip47Signing.notificationKey(signer);
        String signature = key.signMessage(message, ScriptType.P2PKH);
        String address = signer.getPaymentCode().getNotificationAddress().toString();
        return "-----BEGIN BITCOIN SIGNED MESSAGE-----\n" + message + "\n-----BEGIN BITCOIN SIGNATURE-----\nVersion: Bitcoin-qt (1.0)\nAddress: "
                + address + "\n\n" + signature + "\n-----END BITCOIN SIGNATURE-----";
    }

    private static String operatorJson(String onion, String code, String signedBlock) {
        JsonObject doc = new JsonObject();
        doc.addProperty("onion", onion);
        doc.addProperty("paymentCode", code);
        doc.addProperty("verifySigned", signedBlock);
        return doc.toString();
    }

    private static String payload(String version, boolean explorer) {
        return "{\"pairing\":{\"type\":\"dojo.api\",\"version\":\"" + version + "\",\"apikey\":\"k\",\"url\":\"" + PAIRING_URL + "\"}"
                + (explorer ? ",\"explorer\":{\"type\":\"explorer.oxt_onion\",\"url\":\"http://explorerabc.onion\"}" : "") + "}";
    }

    private static JsonObject node(Wallet signer, String signedPayload, String listedPayload, String listedCode) {
        String code = signer.getPaymentCode().toString();
        String message = Bip47Signing.DOJO_LISTING_TAG + "\n" + signedPayload + "\n\nBIP47: " + code;
        JsonObject node = new JsonObject();
        node.addProperty("id", "d1");
        node.addProperty("name", "yellow");
        node.addProperty("paynym", "+91xTx93x3");
        node.addProperty("network", "mainnet");
        node.addProperty("status", "active");
        node.addProperty("country", "is");
        node.addProperty("jurisdiction", "Iceland");
        node.addProperty("version", "1.27.0");
        node.addProperty("indexer_url", INDEXER);
        node.addProperty("paymentCode", listedCode == null ? code : listedCode);
        node.add("payload", JsonParser.parseString(listedPayload));
        node.addProperty("signed", block(signer, message));
        return node;
    }

    @Test
    void theOperatorFileMustBeSignedByThePinnedCodeForThisAddress() throws Exception {
        Wallet op = operator();
        String code = op.getPaymentCode().toString();
        String good = operatorJson(BASE, code, block(op, BASE + "\n\nBIP47: " + code));
        assertNull(DojoBayDirectory.checkOperator(good, BASE, code));

        //Someone else's code, however well signed, is not the directory's operator
        Wallet other = someoneElse();
        String otherCode = other.getPaymentCode().toString();
        String impostor = operatorJson(BASE, otherCode, block(other, BASE + "\n\nBIP47: " + otherCode));
        assertEquals(DojoBayDirectory.Problem.OPERATOR_CODE_MISMATCH, DojoBayDirectory.checkOperator(impostor, BASE, code));

        //Signed for another address
        String elsewhere = "http://elsewhere.onion/";
        assertEquals(DojoBayDirectory.Problem.WRONG_ADDRESS,
                DojoBayDirectory.checkOperator(operatorJson(elsewhere, code, block(op, elsewhere + "\n\nBIP47: " + code)), BASE, code));

        //Naming one code, signed by another
        assertEquals(DojoBayDirectory.Problem.OPERATOR_UNVERIFIED,
                DojoBayDirectory.checkOperator(operatorJson(BASE, code, block(other, BASE + "\n\nBIP47: " + code)), BASE, code));

        assertEquals(DojoBayDirectory.Problem.OPERATOR_MISSING, DojoBayDirectory.checkOperator("{}", BASE, code));
        assertEquals(DojoBayDirectory.Problem.OPERATOR_MISSING, DojoBayDirectory.checkOperator("not json", BASE, code));
    }

    @Test
    void aListingSignedAsListedIsVerified() throws Exception {
        Wallet op = operator();
        DojoBayDirectory.Listing listing = DojoBayDirectory.checkListing(node(op, payload("1.27.0", true), payload("1.27.0", true), null), TODAY);
        assertTrue(listing.verified(), String.valueOf(listing.problem()));
        assertEquals(op.getPaymentCode().toString(), listing.signerCode());
        assertEquals("http://explorerabc.onion", listing.explorerUrl());
        assertEquals("IS", listing.country());
        assertTrue(listing.online());
        //The Electrum address is not in what was signed: said in the confirmation step, not in the name
        assertFalse(listing.addressSigned());
        assertEquals("yellow (+91xTx93x3)", listing.server().getAlias());
    }

    @Test
    void aDifferentVersionAloneIsAllowedButOtherChangesAreNot() throws Exception {
        Wallet op = operator();
        assertTrue(DojoBayDirectory.checkListing(node(op, payload("1.26.0", true), payload("1.27.0", true), null), TODAY).verified());

        DojoBayDirectory.Listing noExplorer = DojoBayDirectory.checkListing(node(op, payload("1.27.0", true), payload("1.27.0", false), null), TODAY);
        assertEquals(DojoBayDirectory.Problem.PAYLOAD_MISMATCH, noExplorer.problem());

        String moved = payload("1.27.0", true).replace(PAIRING_URL, "http://attacker.onion/v2");
        assertEquals(DojoBayDirectory.Problem.PAYLOAD_MISMATCH,
                DojoBayDirectory.checkListing(node(op, payload("1.27.0", true), moved, null), TODAY).problem());
    }

    @Test
    void theDirectoryCannotPutOneOperatorsCodeOnAnothersListing() throws Exception {
        Wallet op = operator();
        String otherCode = someoneElse().getPaymentCode().toString();
        DojoBayDirectory.Listing listing = DojoBayDirectory.checkListing(node(op, payload("1", true), payload("1", true), otherCode), TODAY);
        assertEquals(DojoBayDirectory.Problem.SIGNER_NOT_CODE, listing.problem());
        assertNull(listing.signerCode());
    }

    @Test
    void unsignedOrForeignTextIsNotAListing() throws Exception {
        Wallet op = operator();
        JsonObject unsigned = node(op, payload("1", true), payload("1", true), null);
        unsigned.remove("signed");
        assertEquals(DojoBayDirectory.Problem.MISSING_SIGNATURE, DojoBayDirectory.checkListing(unsigned, TODAY).problem());

        JsonObject notPairing = node(op, "{\"hello\":1}", "{\"hello\":1}", null);
        assertEquals(DojoBayDirectory.Problem.NOT_A_PAIRING, DojoBayDirectory.checkListing(notPairing, TODAY).problem());
    }

    @Test
    void theListShowsVerifiedDojosFirstAndOnlyThoseWithAnElectrumServer() throws Exception {
        Wallet op = operator();
        JsonObject good = node(op, payload("1", true), payload("1", true), null);
        JsonObject bad = node(op, payload("1", true), payload("1", false), null);
        bad.addProperty("name", "aardvark");
        bad.addProperty("indexer_url", "tcp://otherfulcrum.onion:50001");
        JsonObject noIndexer = node(op, payload("1", true), payload("1", true), null);
        noIndexer.remove("indexer_url");
        String directory = "{\"generated_at\":\"x\",\"nodes\":[" + bad + "," + noIndexer + "," + good + "]}";

        List<DojoBayDirectory.Listing> listings = DojoBayDirectory.readListings(directory, TODAY);
        assertEquals(2, listings.size());
        assertTrue(listings.get(0).verified());
        assertFalse(listings.get(1).verified());
    }
}
