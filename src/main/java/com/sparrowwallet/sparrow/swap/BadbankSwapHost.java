package com.sparrowwallet.sparrow.swap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.address.P2WSHAddress;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Answers the swap engine from the open wallet's Badbank account. It builds the lock transaction
 * itself from Badbank coins, signs only transactions it built, asks before signing and asks again
 * before the lock is published. Anything it does not recognise is refused.
 *
 * <p>All wallet work happens on the JavaFX thread, where the rest of Ashigaru touches the wallet.
 */
public class BadbankSwapHost implements SwapHost {
    //Seconds the engine waits for the lock approval (PRE_BTC_LOCK_APPROVAL_TIMEOUT_SECS is 180);
    //the dialog closes, as a rejection, a little before so the answer is never late
    private static final int APPROVAL_DIALOG_SECS = 170;

    private final Wallet badbank;
    private final Window owner;
    private final BiConsumer<String, JsonObject> notifications;
    //Lock transactions this host built, by txid: the only ones it will sign
    private final Map<Sha256Hash, FundedLock> funded = new ConcurrentHashMap<>();

    public BadbankSwapHost(Wallet badbank, Window owner, BiConsumer<String, JsonObject> notifications) {
        if(badbank.getStandardAccountType() != StandardAccount.WHIRLPOOL_BADBANK) {
            throw new IllegalArgumentException("Swaps are funded from Badbank only");
        }
        this.badbank = badbank;
        this.owner = owner;
        this.notifications = notifications;
    }

    @Override
    public CompletableFuture<JsonElement> handle(String kind, JsonObject payload) {
        CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                switch(kind) {
                    case "wallet_info" -> answer.complete(walletInfo());
                    case "fund_lock" -> answer.complete(fundLock(payload));
                    case "max_giveable" -> answer.complete(maxGiveable());
                    case "fee_rate" -> answer.complete(feeRate());
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

    @Override
    public void onNotification(String kind, JsonObject payload) {
        Platform.runLater(() -> notifications.accept(kind, payload));
    }

    public long getBalance() {
        return spendableTxos().keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
    }

    public String getFreshAddress() {
        return badbank.getFreshNode(KeyPurpose.RECEIVE).getAddress().toString();
    }

    /** The fee for a lock of this size at Ashigaru's current fee rate, to agree with the maker up front */
    public long estimateLockFee(long btcSats) throws InsufficientFundsException {
        return buildLock(placeholderLockAddress(), btcSats, null).getFee();
    }

    private JsonObject walletInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("balanceSats", getBalance());
        info.addProperty("freshAddress", getFreshAddress());
        return info;
    }

    private JsonObject fundLock(JsonObject payload) throws Exception {
        Address lockAddress = Address.fromString(payload.get("address").getAsString());
        long amount = payload.get("amountSats").getAsLong();
        Long fee = payload.has("feeSats") && !payload.get("feeSats").isJsonNull() ? payload.get("feeSats").getAsLong() : null;
        //Change goes to Badbank's own change address rather than the one the engine suggests: Badbank either way

        WalletTransaction walletTransaction = buildLock(lockAddress, amount, fee);
        checkLockTransaction(walletTransaction.getTransaction(), lockAddress, amount);
        PSBT psbt = walletTransaction.createPSBT();
        funded.put(psbt.getTransaction().getTxId(), new FundedLock(psbt, amount, walletTransaction.getFee()));

        JsonObject answer = new JsonObject();
        answer.addProperty("psbtHex", Utils.bytesToHex(psbt.serialize()));
        return answer;
    }

    private WalletTransaction buildLock(Address lockAddress, long amount, Long fee) throws InsufficientFundsException {
        //drongo wants the minimum rate when the fee is fixed, so selection does not underestimate values
        double feeRate = fee != null ? Transaction.DEFAULT_MIN_RELAY_FEE : AppServices.getDefaultFeeRate();
        List<Payment> payments = List.of(new Payment(lockAddress, "Swap lock", amount, false));
        long noInputsFee = badbank.getNoInputsFee(payments, feeRate);
        return badbank.createWalletTransaction(List.of(new KnapsackUtxoSelector(noInputsFee)), txoFilters(), payments,
                Collections.emptyList(), Collections.emptySet(), feeRate, AppServices.getMinimumRelayFeeRate(), fee,
                AppServices.getCurrentBlockHeight(), Config.get().isGroupByAddress(), Config.get().isIncludeMempoolOutputs());
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
        double feeRate = AppServices.getDefaultFeeRate();
        Map<BlockTransactionHashIndex, WalletNode> txos = spendableTxos();
        long total = txos.keySet().stream().mapToLong(BlockTransactionHashIndex::getValue).sum();
        long max = Math.max(0, badbank.getMaxSpendable(List.of(placeholderLockAddress()), feeRate, txos));
        JsonObject answer = new JsonObject();
        answer.addProperty("amountSats", max);
        answer.addProperty("feeSats", max == 0 ? 0 : total - max);
        return answer;
    }

    private JsonObject feeRate() {
        JsonObject answer = new JsonObject();
        answer.addProperty("satPerVb", (long)Math.ceil(AppServices.getDefaultFeeRate()));
        answer.addProperty("minRelaySatPerVb", (long)Math.ceil(AppServices.getMinimumRelayFeeRate()));
        return answer;
    }

    private void sign(JsonObject payload, CompletableFuture<JsonElement> answer) throws Exception {
        PSBT requested = new PSBT(Utils.hexToBytes(payload.get("psbtHex").getAsString()));
        FundedLock lock = funded.get(requested.getTransaction().getTxId());
        if(lock == null) {
            throw new IllegalStateException("Not a lock transaction Ashigaru built");
        }
        PSBT ours = lock.psbt();

        Alert confirm = alert(Alert.AlertType.CONFIRMATION, "Sign the swap lock?",
                "Ashigaru built a transaction locking " + btc(lock.amount()) + " BTC from Badbank, with a network fee of "
                        + lock.fee() + " sats.\n\nSigning does not publish it. You will see the final terms, including "
                        + "how much XMR you receive, and nothing is locked unless you approve them.",
                new ButtonType("Sign", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        Optional<ButtonType> choice = confirm.showAndWait();
        if(choice.isEmpty() || choice.get().getButtonData() != ButtonBar.ButtonData.OK_DONE) {
            answer.completeExceptionally(new IllegalStateException("Signing cancelled"));
            return;
        }

        if(!badbank.isEncrypted()) {
            completeSigned(badbank, ours, answer, false);
            return;
        }

        WalletPasswordDialog dialog = new WalletPasswordDialog(badbank.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
        dialog.initOwner(owner);
        Optional<SecureString> password = dialog.showAndWait();
        if(password.isEmpty()) {
            answer.completeExceptionally(new IllegalStateException("Signing cancelled"));
            return;
        }
        Storage.DecryptWalletService decrypt = new Storage.DecryptWalletService(badbank.copy(), password.get());
        decrypt.setOnSucceeded(event -> completeSigned(decrypt.getValue(), ours, answer, true));
        decrypt.setOnFailed(event -> {
            AppServices.showErrorDialog("Incorrect Password", decrypt.getException().getMessage());
            answer.completeExceptionally(decrypt.getException());
        });
        decrypt.start();
    }

    private void completeSigned(Wallet signingWallet, PSBT unsigned, CompletableFuture<JsonElement> answer, boolean clear) {
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
            if(clear) {
                signingWallet.clearPrivate();
            }
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

        String refundTerms = fullRefund || amnesty == 0
                ? "If the swap fails, the full amount comes back to Badbank, less network fees."
                : "If the swap fails, " + btc(lockAmount - amnesty) + " BTC comes back to Badbank by itself. The maker holds "
                + btc(amnesty) + " BTC (" + percent(amnesty, lockAmount) + ") as an anti-spam deposit, returned only with its cooperation.";
        ButtonType approveButton = new ButtonType("Approve", ButtonBar.ButtonData.OK_DONE);
        ButtonType rejectButton = new ButtonType("Reject", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = alert(Alert.AlertType.CONFIRMATION, "Approve the swap?",
                "Lock: " + btc(lockAmount) + " BTC from Badbank\n"
                        + "Network fee: " + networkFee + " sats\n"
                        + "You receive: " + xmr(piconero) + " XMR\n\n"
                        + refundTerms + "\n\n"
                        + "Once locked, the swap can only be cancelled after about 4 hours, and the refund must then be "
                        + "claimed within about 24 hours. Keep Ashigaru open until the swap finishes.\n\n"
                        + "This closes as a rejection in " + APPROVAL_DIALOG_SECS + " seconds.",
                approveButton, rejectButton);
        PauseTransition expiry = new PauseTransition(Duration.seconds(APPROVAL_DIALOG_SECS));
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

    private static String percent(long part, long whole) {
        return String.format(Locale.ROOT, "%.1f%%", whole == 0 ? 0 : 100d * part / whole);
    }
}
