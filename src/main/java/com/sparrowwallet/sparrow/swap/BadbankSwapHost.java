package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.P2WSHAddress;
import com.sparrowwallet.drongo.crypto.ECDSASignature;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Storage;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import javafx.util.Duration;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Answers the swap engine for one swap of one Badbank coin. The lock transaction spends exactly that
 * coin and pays it all into the lock, less the network fee: no change, so nothing of the coin stays
 * behind.
 *
 * <p>The wallet password is asked once, when the swap is started ({@link #unlock}). From eigenwallet 5
 * the maker wants proof, during the setup, that the taker controls the coin, so the keys are needed
 * before the final terms exist. They are held only until the lock is signed (or the swap fails), and
 * are used for two things: an ownership proof for the one coin (a signature over a challenge that can
 * never be a transaction), and the signature of the one lock transaction this host built. Nothing is
 * published until the user approves the final terms, which is asked separately. Anything the host does
 * not recognise is refused.
 *
 * <p>All wallet work happens on the JavaFX thread, where the rest of Ashigaru touches the wallet.
 */
public class BadbankSwapHost {
    /**
     * Seconds the engine waits for the lock approval (PRE_BTC_LOCK_APPROVAL_TIMEOUT_SECS, 90 in eigenwallet 5);
     * the review closes, as a rejection, a little before so the answer is never late
     */
    static final int APPROVAL_SECS = 85;
    /** The keys are never held longer than this, whatever happens to the swap */
    private static final int KEYS_HELD_MAX_MINUTES = 15;
    /** eigenwallet's absolute minimum transaction fee, the lowest a maker accepts for the lock */
    static final long MIN_LOCK_FEE_SATS = 1000;
    /** eigenwallet's dust limit: the lock must be worth more than this */
    private static final long DUST_SATS = 546;

    private final Wallet badbank;
    private final BlockTransactionHashIndex coin;
    private final Window owner;
    //The wallet with its keys decrypted, from the password at the start until the lock is signed
    private Wallet signer;
    //Whether signer is a copy this host made, and so must be wiped
    private boolean signerIsCopy;
    private PauseTransition keysExpiry;
    //Lock transactions this host built, by txid: the only ones it will sign
    private final Map<Sha256Hash, FundedLock> funded = new ConcurrentHashMap<>();
    //Where reviews are shown, asked each time because the Swaps screen comes and goes: null means ask in a dialog
    private Supplier<ReviewPresenter> presenters = () -> null;
    //The dialog form of the review now in the Swaps screen, used if that screen goes away before it is answered
    private volatile Runnable dialogFallback;
    //Told the maker's terms when they reach the approval, so they are kept with the swap
    private TermsListener termsListener = (piconero, amnestySats, fullRefund) -> {};
    //What the final review shows about this swap
    private String xmrAddress = "";
    private String maker = "";

    public BadbankSwapHost(Wallet badbank, BlockTransactionHashIndex coin, Window owner) {
        if(badbank.getStandardAccountType() != StandardAccount.WHIRLPOOL_BADBANK) {
            throw new IllegalArgumentException("Swaps are funded from Badbank only");
        }
        this.badbank = badbank;
        this.coin = coin;
        this.owner = owner;
    }

    /** Reviews go to the presenter this supplies while it is showing, and to dialogs otherwise */
    public void setPresenterSource(Supplier<ReviewPresenter> presenters) {
        this.presenters = presenters;
    }

    /** Hears the terms the maker offers, before the user decides */
    public interface TermsListener {
        void terms(long piconero, long amnestySats, boolean fullRefund);
    }

    public void setTermsListener(TermsListener listener) {
        this.termsListener = listener;
    }

    /** Where the XMR goes and who the maker is, for the final review */
    public void setSwapContext(String xmrAddress, String maker) {
        this.xmrAddress = xmrAddress == null ? "" : xmrAddress;
        this.maker = maker == null ? "" : maker;
    }

    /**
     * The Swaps screen went away with a review on it. Ask in a dialog instead, so the request is not
     * left waiting for a screen nobody is looking at.
     */
    public void reviewPresenterGone() {
        Runnable fallback = dialogFallback;
        dialogFallback = null;
        if(fallback != null) {
            Platform.runLater(fallback);
        }
    }

    public Wallet getBadbank() {
        return badbank;
    }

    public BlockTransactionHashIndex getCoin() {
        return coin;
    }

    public long getCoinValue() {
        return coin.getValue();
    }

    public CompletableFuture<JsonElement> handle(String kind, JsonObject payload) {
        CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                switch(kind) {
                    case "wallet_info" -> answer.complete(walletInfo());
                    case "fund_lock" -> answer.complete(fundLock(payload));
                    case "max_giveable" -> answer.complete(maxGiveable());
                    case "fee_rate" -> answer.complete(feeRate());
                    case "prove" -> answer.complete(prove(payload));
                    case "sign" -> sign(payload, answer);
                    case "approve" -> approve(payload, answer);
                    default -> answer.completeExceptionally(new IllegalArgumentException("Unknown request " + kind));
                }
            } catch(Exception e) {
                answer.completeExceptionally(e);
            }
        });
        return answer;
    }

    public String getFreshAddress() {
        return badbank.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString();
    }

    /**
     * The network fee for locking the whole coin at Ashigaru's current fee rate. The swap amount is
     * the coin's value less this, and it is agreed with the maker up front.
     */
    public long estimateLockFee() {
        long max = badbank.getMaxSpendable(List.of(placeholderLockAddress()), AppServices.getDefaultFeeRate(), coinTxo());
        long fee = lockFee(coin.getValue() - max);
        if(max <= 0 || coin.getValue() - fee <= DUST_SATS) {
            throw new IllegalStateException("This coin is too small to pay the network fee");
        }
        return fee;
    }

    /**
     * Makers refuse a lock paying less than half their own fee estimate, and eigenwallet never
     * estimates below 1000 sats (bitcoin_wallet::MIN_ABSOLUTE_TX_FEE_SATS), so a lock at a low fee
     * rate is rejected. Pay at least that, as eigenwallet's own taker does.
     */
    static long lockFee(long rateFee) {
        return Math.max(rateFee, MIN_LOCK_FEE_SATS);
    }

    private Map<BlockTransactionHashIndex, WalletNode> coinTxo() {
        Map<BlockTransactionHashIndex, WalletNode> txos = spendableTxos();
        return txos.entrySet().stream()
                .filter(entry -> sameOutpoint(entry.getKey(), coin))
                .findFirst()
                .map(entry -> Map.of(entry.getKey(), entry.getValue()))
                .orElseThrow(() -> new IllegalStateException("The coin is no longer spendable in Badbank"));
    }

    private static boolean sameOutpoint(BlockTransactionHashIndex a, BlockTransactionHashIndex b) {
        //Not equals(): that also compares the height, which changes as the coin confirms
        return a.getHash().equals(b.getHash()) && a.getIndex() == b.getIndex();
    }

    private JsonObject walletInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("balanceSats", coin.getValue());
        info.addProperty("freshAddress", getFreshAddress());
        return info;
    }

    private JsonObject fundLock(JsonObject payload) throws Exception {
        Address lockAddress = Address.fromString(payload.get("address").getAsString());
        long amount = payload.get("amountSats").getAsLong();
        //The maker accepts a limited number of inputs; this lock has exactly one
        if(payload.has("maxInputs") && !payload.get("maxInputs").isJsonNull() && payload.get("maxInputs").getAsLong() < 1) {
            throw new IllegalStateException("The maker does not accept any inputs");
        }
        long fee = payload.has("feeSats") && !payload.get("feeSats").isJsonNull()
                ? payload.get("feeSats").getAsLong() : coin.getValue() - amount;
        //The whole coin goes in: lock plus fee must be exactly its value, so there is no change
        if(amount + fee != coin.getValue()) {
            throw new IllegalStateException("The lock (" + amount + " sats) and fee (" + fee + " sats) do not spend exactly the "
                    + coin.getValue() + " sats coin");
        }

        WalletTransaction walletTransaction = buildLock(lockAddress, amount, fee);
        checkLockTransaction(walletTransaction.getTransaction(), lockAddress, amount);
        if(walletTransaction.getTransaction().getOutputs().size() != 1 || walletTransaction.getTransaction().getInputs().size() != 1) {
            throw new IllegalStateException("The lock must spend only the chosen coin and have no change");
        }
        PSBT psbt = walletTransaction.createPSBT();
        funded.put(psbt.getTransaction().getTxId(), new FundedLock(psbt, amount, walletTransaction.getFee()));

        JsonObject answer = new JsonObject();
        answer.addProperty("psbtHex", Utils.bytesToHex(psbt.serialize()));
        return answer;
    }

    private WalletTransaction buildLock(Address lockAddress, long amount, long fee) throws InsufficientFundsException {
        coinTxo();
        //drongo wants the minimum rate when the fee is fixed, so selection does not underestimate values
        List<Payment> payments = List.of(new Payment(lockAddress, "Swap lock", amount, false));
        return badbank.createWalletTransaction(List.of(new PresetUtxoSelector(List.of(coin), true, true)), txoFilters(), payments,
                Collections.emptyList(), Collections.emptySet(), Transaction.DEFAULT_MIN_RELAY_FEE,
                AppServices.getMinimumRelayFeeRate(), fee, AppServices.getCurrentBlockHeight(), false, true);
    }

    /**
     * The maker refuses a lock transaction with more than two outputs, or one that does not pay the
     * lock address exactly once with the agreed amount, so check before anything is signed.
     */
    static void checkLockTransaction(Transaction transaction, Address lockAddress, long amount) {
        List<TransactionOutput> outputs = transaction.getOutputs();
        if(outputs.size() > 2) {
            throw new IllegalStateException("Lock transaction has " + outputs.size() + " outputs; at most 2 are allowed");
        }
        byte[] lockScript = lockAddress.getOutputScript().getProgram();
        List<TransactionOutput> lockOutputs = outputs.stream()
                .filter(output -> Arrays.equals(output.getScript().getProgram(), lockScript)).toList();
        if(lockOutputs.size() != 1) {
            throw new IllegalStateException("Lock transaction must pay the lock address exactly once");
        }
        if(lockOutputs.get(0).getValue() != amount) {
            throw new IllegalStateException("Lock output pays " + lockOutputs.get(0).getValue() + " instead of " + amount);
        }
    }

    private JsonObject maxGiveable() {
        long fee = estimateLockFee();
        JsonObject answer = new JsonObject();
        answer.addProperty("amountSats", coin.getValue() - fee);
        answer.addProperty("feeSats", fee);
        return answer;
    }

    private JsonObject feeRate() {
        JsonObject answer = new JsonObject();
        answer.addProperty("satPerVb", (long)Math.ceil(AppServices.getDefaultFeeRate()));
        answer.addProperty("minRelaySatPerVb", (long)Math.ceil(AppServices.getMinimumRelayFeeRate()));
        return answer;
    }

    /**
     * Asks for the wallet password, once, and holds the decrypted keys for this swap's setup. The
     * returned future completes when they are ready, and fails if the user cancels or the password is
     * wrong. A wallet without a password needs no asking.
     */
    public CompletableFuture<Void> unlock() {
        CompletableFuture<Void> unlocked = new CompletableFuture<>();
        Platform.runLater(() -> {
            if(signer != null) {
                unlocked.complete(null);
                return;
            }
            if(!badbank.isEncrypted()) {
                hold(badbank, false);
                unlocked.complete(null);
                return;
            }

            WalletPasswordDialog dialog = new WalletPasswordDialog(badbank.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
            dialog.initOwner(owner);
            Optional<SecureString> password = dialog.showAndWait();
            if(password.isEmpty()) {
                unlocked.completeExceptionally(new IllegalStateException("Cancelled"));
                return;
            }
            Storage.DecryptWalletService decrypt = new Storage.DecryptWalletService(badbank.copy(), password.get());
            decrypt.setOnSucceeded(event -> {
                hold(decrypt.getValue(), true);
                unlocked.complete(null);
            });
            decrypt.setOnFailed(event -> {
                AppServices.showErrorDialog("Incorrect Password", decrypt.getException().getMessage());
                unlocked.completeExceptionally(decrypt.getException());
            });
            decrypt.start();
        });
        return unlocked;
    }

    private void hold(Wallet unlocked, boolean copy) {
        signer = unlocked;
        signerIsCopy = copy;
        keysExpiry = new PauseTransition(Duration.minutes(KEYS_HELD_MAX_MINUTES));
        keysExpiry.setOnFinished(event -> release());
        keysExpiry.play();
    }

    /** Wipes the keys held for this swap. Safe to call more than once, from any thread. */
    public void release() {
        Runnable wipe = () -> {
            if(keysExpiry != null) {
                keysExpiry.stop();
                keysExpiry = null;
            }
            if(signer != null && signerIsCopy) {
                signer.clearPrivate();
            }
            signer = null;
            signerIsCopy = false;
        };
        if(Platform.isFxApplicationThread()) {
            wipe.run();
        } else {
            Platform.runLater(wipe);
        }
    }

    /** True while the keys are held */
    boolean isUnlocked() {
        return signer != null;
    }

    /**
     * Proves to the maker that this wallet controls the coin the lock spends, by signing the 32 byte
     * challenge the engine gives with the key behind each input. The challenge is a hash the engine
     * derived from the swap, both parties' keys and the lock transaction's id, so a proof is of no use
     * for anything else. Only inputs of the lock this host built are proved, and only native segwit.
     */
    private JsonObject prove(JsonObject payload) throws Exception {
        if(signer == null) {
            throw new IllegalStateException("The wallet is locked");
        }
        PSBT requested = new PSBT(Utils.hexToBytes(payload.get("psbtHex").getAsString()));
        if(!funded.containsKey(requested.getTransaction().getTxId())) {
            throw new IllegalStateException("Not a lock transaction Ashigaru built");
        }
        byte[] challenge = Utils.hexToBytes(payload.get("challengeHex").getAsString());

        Map<BlockTransactionHashIndex, WalletNode> txos = spendableTxos();
        JsonArray proofs = new JsonArray();
        for(TransactionInput input : requested.getTransaction().getInputs()) {
            WalletNode node = txos.entrySet().stream()
                    .filter(entry -> entry.getKey().getHash().equals(input.getOutpoint().getHash()) && entry.getKey().getIndex() == input.getOutpoint().getIndex())
                    .map(Map.Entry::getValue).findFirst()
                    .orElseThrow(() -> new IllegalStateException("A lock input is not a coin of Badbank"));
            ECKey key = signer.getKeystores().get(0).getKey(node);
            proofs.add(ownershipProof(key, challenge, node.getOutputScript().getProgram()));
        }
        JsonObject answer = new JsonObject();
        answer.add("proofs", proofs);
        return answer;
    }

    /**
     * An ECDSA signature over the challenge with the key that controls a native segwit output, and that
     * key, as the maker expects them: a compressed public key and the 64 byte compact signature (low S,
     * which drongo produces). The key must hash to the output's script, or nothing is proved.
     */
    static JsonObject ownershipProof(ECKey key, byte[] challenge, byte[] outputScript) {
        if(challenge.length != 32) {
            throw new IllegalArgumentException("The challenge must be 32 bytes");
        }
        byte[] pubKey = key.getPubKey(true);
        byte[] expected = Arrays.copyOfRange(outputScript, 2, outputScript.length);
        if(outputScript.length != 22 || outputScript[0] != 0 || outputScript[1] != 20 || !Arrays.equals(expected, Utils.sha256hash160(pubKey))) {
            throw new IllegalStateException("The coin is not a native segwit output of this key");
        }
        ECDSASignature signature = key.signEcdsa(Sha256Hash.wrap(challenge));
        byte[] compact = new byte[64];
        System.arraycopy(toFixed(signature.r), 0, compact, 0, 32);
        System.arraycopy(toFixed(signature.s), 0, compact, 32, 32);
        JsonObject proof = new JsonObject();
        proof.addProperty("publicKey", Utils.bytesToHex(pubKey));
        proof.addProperty("signature", Utils.bytesToHex(compact));
        return proof;
    }

    private static byte[] toFixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, fixed, 32 - length, length);
        return fixed;
    }

    /**
     * Signs the lock this host built, with the keys unlocked when the swap was started. The signed
     * transaction goes back to the engine, which holds it until the user approves the final terms and
     * only then publishes it. The keys are wiped as soon as it is signed.
     */
    private void sign(JsonObject payload, CompletableFuture<JsonElement> answer) throws Exception {
        PSBT requested = new PSBT(Utils.hexToBytes(payload.get("psbtHex").getAsString()));
        FundedLock lock = funded.get(requested.getTransaction().getTxId());
        if(lock == null) {
            throw new IllegalStateException("Not a lock transaction Ashigaru built");
        }
        if(signer == null) {
            throw new IllegalStateException("The wallet is locked");
        }
        completeSigned(signer, lock.psbt(), answer);
    }

    private void completeSigned(Wallet signingWallet, PSBT unsigned, CompletableFuture<JsonElement> answer) {
        try {
            //Sign a copy so a failure leaves the funded PSBT as it was
            PSBT psbt = new PSBT(unsigned.serialize());
            signingWallet.sign(psbt);
            signingWallet.finalise(psbt);
            JsonObject signed = new JsonObject();
            signed.addProperty("txHex", Utils.bytesToHex(psbt.extractTransaction().bitcoinSerialize()));
            answer.complete(signed);
        } catch(Exception e) {
            answer.completeExceptionally(e);
        } finally {
            release();
        }
    }

    private void approve(JsonObject payload, CompletableFuture<JsonElement> answer) {
        JsonObject request = payload.getAsJsonObject("request");
        String type = request == null || !request.has("type") ? null : request.get("type").getAsString();
        if(!"LockBitcoin".equals(type)) {
            throw new IllegalArgumentException("Ashigaru only approves Bitcoin locks, not " + type);
        }

        JsonObject details = request.getAsJsonObject("content");
        long lockAmount = details.get("btc_lock_amount").getAsLong();
        long networkFee = details.get("btc_network_fee").getAsLong();
        long piconero = details.get("xmr_receive_amount").getAsLong();
        long amnesty = details.has("btc_amnesty_amount") ? details.get("btc_amnesty_amount").getAsLong() : 0;
        boolean fullRefund = details.has("has_full_refund_signature") && details.get("has_full_refund_signature").getAsBoolean();
        if(funded.values().stream().noneMatch(lock -> lock.amount() == lockAmount)) {
            throw new IllegalStateException("The lock amount does not match any lock transaction Ashigaru built");
        }

        termsListener.terms(piconero, amnesty, fullRefund);
        ReviewPresenter presenter = presenters.get();
        if(presenter == null) {
            approveWithDialog(lockAmount, networkFee, piconero, amnesty, fullRefund, APPROVAL_SECS, answer);
            return;
        }

        AtomicBoolean answered = new AtomicBoolean();
        Instant expiresAt = Instant.now().plusSeconds(APPROVAL_SECS);
        PauseTransition expiry = new PauseTransition(Duration.seconds(APPROVAL_SECS));
        SwapReview.Decision decision = new SwapReview.Decision() {
            @Override
            public void confirm() {
                finish(true);
            }

            @Override
            public void decline() {
                finish(false);
            }

            private void finish(boolean approved) {
                if(answered.compareAndSet(false, true)) {
                    expiry.stop();
                    dialogFallback = null;
                    presenter.dismiss();
                    answer.complete(new JsonPrimitive(approved));
                }
            }
        };
        //An unanswered approval is a rejection, a little before the engine's own timeout
        expiry.setOnFinished(event -> decision.decline());
        dialogFallback = () -> {
            if(answered.compareAndSet(false, true)) {
                expiry.stop();
                //Only the time that is left: the engine's clock kept running while the review was on screen
                long left = Math.max(1, java.time.Duration.between(Instant.now(), expiresAt).getSeconds());
                approveWithDialog(lockAmount, networkFee, piconero, amnesty, fullRefund, (int)left, answer);
            }
        };
        expiry.play();
        presenter.present(SwapReview.commit(coin.getValue(), lockAmount, networkFee, piconero, amnesty, fullRefund, xmrAddress, maker, expiresAt, decision));
    }

    /** The dialog form of the final approval, used when the Swaps screen is not showing */
    private void approveWithDialog(long lockAmount, long networkFee, long piconero, long amnesty, boolean fullRefund, int seconds,
                                   CompletableFuture<JsonElement> answer) {
        ButtonType approveButton = new ButtonType("Approve", ButtonBar.ButtonData.OK_DONE);
        ButtonType rejectButton = new ButtonType("Reject", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = alert(Alert.AlertType.CONFIRMATION, "Approve the swap?",
                "Lock: " + btc(lockAmount) + " BTC from Badbank\n"
                        + "Network fee: " + networkFee + " sats\n"
                        + "You receive: " + xmr(piconero) + " XMR\n\n"
                        + recoveryTerms(lockAmount, amnesty, fullRefund) + "\n\n"
                        + "This closes as a rejection in " + seconds + " seconds.",
                approveButton, rejectButton);
        PauseTransition expiry = new PauseTransition(Duration.seconds(seconds));
        expiry.setOnFinished(event -> {
            alert.setResult(rejectButton);
            alert.close();
        });
        expiry.play();
        Optional<ButtonType> choice = alert.showAndWait();
        expiry.stop();
        answer.complete(new JsonPrimitive(choice.isPresent() && choice.get() == approveButton));
    }

    private record FundedLock(PSBT psbt, long amount, long fee) {}

    private Alert alert(Alert.AlertType type, String header, String content, ButtonType... buttons) {
        Alert alert = new Alert(type, content, buttons);
        alert.setTitle("Swap");
        alert.setHeaderText(header);
        alert.initOwner(owner);
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
        return alert;
    }

    private Map<BlockTransactionHashIndex, WalletNode> spendableTxos() {
        return badbank.getWalletTxos(txoFilters());
    }

    private List<TxoFilter> txoFilters() {
        return List.of(new SpentTxoFilter(), new FrozenTxoFilter(), new CoinbaseTxoFilter(badbank));
    }

    /** Same output size as the real lock, a P2WSH 2-of-2, for fee estimates before it exists */
    static Address placeholderLockAddress() {
        return new P2WSHAddress(new byte[32]);
    }

    static String btc(long sats) {
        return String.format(Locale.ROOT, "%.8f", sats / 100_000_000d);
    }

    static String xmr(long piconero) {
        return String.format(Locale.ROOT, "%.12f", piconero / 1_000_000_000_000d);
    }

    /**
     * What happens to the BTC if the swap fails, in the words every swap screen uses. The refund is an
     * estimate (the amount less any anti-spam deposit, then less network fees); a deposit the maker may
     * keep is stated first, in its own paragraph, because it is the consequence most easily missed.
     */
    static String recoveryTerms(long lockAmount, long amnesty, boolean fullRefund) {
        boolean deposit = !fullRefund && amnesty > 0;
        String refund = SwapCopy.refundTerms(btc(deposit ? lockAmount - amnesty : lockAmount));
        String terms = deposit ? SwapCopy.depositTerms(btc(amnesty), percent(amnesty, lockAmount)) + "\n\n" + refund : refund;
        return terms + "\n\n" + SwapCopy.keepRunning();
    }

    static String percent(long part, long whole) {
        return String.format(Locale.ROOT, "%.1f%%", whole == 0 ? 0 : 100d * part / whole);
    }
}
