package com.sparrowwallet.sparrow.io;

import com.google.gson.*;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.KeystoreLabelsChangedEvent;
import com.sparrowwallet.sparrow.event.WalletEntryLabelsChangedEvent;
import com.sparrowwallet.sparrow.event.WalletUtxoStatusChangedEvent;
import com.sparrowwallet.sparrow.wallet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class WalletLabels implements WalletImport, WalletExport {
    private static final Logger log = LoggerFactory.getLogger(WalletLabels.class);

    private final List<WalletForm> walletForms;

    public WalletLabels() {
        this.walletForms = Collections.emptyList();
    }

    public WalletLabels(List<WalletForm> walletForms) {
        this.walletForms = walletForms;
    }

    @Override
    public boolean isEncrypted(File file) {
        return false;
    }

    @Override
    public String getName() {
        return "Labels";
    }

    @Override
    public WalletModel getWalletModel() {
        return WalletModel.LABELS;
    }

    @Override
    public void exportWallet(Wallet wallet, OutputStream outputStream, String password) throws ExportException {
        List<Label> labels = new ArrayList<>();
        List<Wallet> allWallets = wallet.isMasterWallet() ? wallet.getAllWallets() : wallet.getMasterWallet().getAllWallets();
        for(Wallet exportWallet : allWallets) {
            OutputDescriptor outputDescriptor = OutputDescriptor.getOutputDescriptor(exportWallet);
            String origin = outputDescriptor.toString(true, false, false);

            for(Keystore keystore : exportWallet.getKeystores()) {
                if(keystore.getLabel() != null && !keystore.getLabel().isEmpty()) {
                    labels.add(new Label(LabelType.xpub, keystore.getExtendedPublicKey().toString(), keystore.getLabel(), null, null, null, null, null, null));
                }
            }

            Integer currentHeight = AppServices.getCurrentBlockHeight();

            for(BlockTransaction blkTx : exportWallet.getWalletTransactions().values()) {
                if(blkTx.getLabel() != null && !blkTx.getLabel().isEmpty()) {
                    Long height = null;
                    String time = null;
                    if(blkTx.getHeight() > 0) {
                        // Only include height once the tx has at least 6 confirmations
                        if(currentHeight != null && currentHeight > 0 && (currentHeight - blkTx.getHeight() + 1) >= 6) {
                            height = (long) blkTx.getHeight();
                        }
                        if(blkTx.getDate() != null) {
                            time = DateTimeFormatter.ISO_INSTANT.format(blkTx.getDate().toInstant().atOffset(ZoneOffset.UTC));
                        }
                    }
                    labels.add(new Label(LabelType.tx, blkTx.getHashAsString(), blkTx.getLabel(), origin, null, height, time, null, null));
                }
            }

            for(WalletNode addressNode : exportWallet.getWalletAddresses().values()) {
                if(addressNode.getLabel() != null && !addressNode.getLabel().isEmpty()) {
                    String keypath = addressNode.getDerivationPath();
                    labels.add(new Label(LabelType.addr, addressNode.getAddress().toString(), addressNode.getLabel(), null, null, null, null, keypath, null));
                }
            }

            for(BlockTransactionHashIndex txo : exportWallet.getWalletTxos().keySet()) {
                Boolean spendable = txo.isSpent() ? null : txo.getStatus() == Status.FROZEN ? Boolean.FALSE : Boolean.TRUE;
                Long value = txo.getValue();
                if(txo.getLabel() != null && !txo.getLabel().isEmpty()) {
                    labels.add(new Label(LabelType.output, txo.toString(), txo.getLabel(), null, spendable, null, null, null, value));
                } else if(!txo.isSpent()) {
                    labels.add(new Label(LabelType.output, txo.toString(), null, null, spendable, null, null, null, value));
                }

                if(txo.isSpent() && txo.getSpentBy().getLabel() != null && !txo.getSpentBy().getLabel().isEmpty()) {
                    labels.add(new Label(LabelType.input, txo.getSpentBy().toString(), txo.getSpentBy().getLabel(), null, null, null, null, null, null));
                }
            }

            // Include detached labels (labels for entries not yet seen in the wallet)
            for(Map.Entry<String, String> detached : exportWallet.getDetachedLabels().entrySet()) {
                if(detached.getValue() == null || detached.getValue().isEmpty()) continue;
                LabelType detachedType = detached.getKey().matches("[0-9a-fA-F]{64}") ? LabelType.tx : LabelType.addr;
                labels.add(new Label(detachedType, detached.getKey(), detached.getValue(), origin, null, null, null, null, null));
            }
        }

        try {
            // new Gson() omits null fields by default — intentional to keep JSONL output clean
            Gson gson = new Gson();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

            for(Label label : labels) {
                writer.write(gson.toJson(label) + "\n");
            }

            writer.flush();
        } catch(Exception e) {
            log.error("Error exporting labels", e);
            throw new ExportException("Error exporting labels", e);
        }
    }

    @Override
    public String getWalletExportDescription() {
        return "Exports a file containing labels from this wallet in the BIP329 standard format.";
    }

    @Override
    public String getExportFileExtension(Wallet wallet) {
        return "jsonl";
    }

    @Override
    public boolean isWalletExportScannable() {
        return false;
    }

    @Override
    public boolean walletExportRequiresDecryption() {
        return false;
    }

    @Override
    public String getWalletImportDescription() {
        return "Imports a file containing labels in the BIP329 standard format to the currently selected wallet.";
    }

    @Override
    public Wallet importWallet(InputStream inputStream, String password) throws ImportException {
        if(walletForms.isEmpty()) {
            throw new IllegalStateException("No wallets to import labels for");
        }

        // Custom deserializer handles spendable as either boolean (spec-compliant) or
        // string "true"/"false" (old Sparrow exports) for backward compatibility
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(Label.class, new LabelDeserializer())
                .create();
        List<Label> labels = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String line;
            while((line = reader.readLine()) != null) {
                Label label;
                try {
                    label = gson.fromJson(line, Label.class);
                } catch(Exception e) {
                    continue;
                }

                if(label == null || label.type == null || label.ref == null) {
                    continue;
                }

                if(label.type == LabelType.output) {
                    if((label.label == null || label.label.isEmpty()) && label.spendable == null) {
                        continue;
                    }
                } else if(label.label == null || label.label.isEmpty()) {
                    continue;
                }

                // Warn and truncate labels exceeding the 255-char DB column limit
                if(label.label != null && label.label.length() > 255) {
                    log.warn("Label for {} exceeds 255 characters and will be truncated", label.ref);
                    label.label = label.label.substring(0, 255);
                }

                labels.add(label);
            }
        } catch(Exception e) {
            throw new ImportException("Error importing labels file", e);
        }

        Map<Wallet, List<Keystore>> changedWalletKeystores = new LinkedHashMap<>();
        Map<Wallet, List<Entry>> changedWalletEntries = new LinkedHashMap<>();
        Map<Wallet, List<BlockTransactionHashIndex>> changedWalletUtxoStatuses = new LinkedHashMap<>();

        for(WalletForm walletForm : walletForms) {
            Wallet wallet = walletForm.getWallet();
            if(!wallet.isValid()) {
                continue;
            }

            OutputDescriptor outputDescriptor = OutputDescriptor.getOutputDescriptor(wallet);
            String origin = outputDescriptor.toString(true, false, false);

            List<Entry> transactionEntries = walletForm.getWalletTransactionsEntry().getChildren();
            List<Entry> addressEntries = new ArrayList<>();
            addressEntries.addAll(walletForm.getNodeEntry(KeyPurpose.RECEIVE).getChildren());
            addressEntries.addAll(walletForm.getNodeEntry(KeyPurpose.CHANGE).getChildren());
            List<Entry> utxoEntries = walletForm.getWalletUtxosEntry().getChildren();

            for(Label label : labels) {
                if(label.origin != null && !label.origin.equals(origin)) {
                    continue;
                }

                if(label.type == LabelType.xpub) {
                    for(Keystore keystore : wallet.getKeystores()) {
                        if(keystore.getExtendedPublicKey().toString().equals(label.ref)) {
                            keystore.setLabel(label.label);
                            List<Keystore> changedKeystores = changedWalletKeystores.computeIfAbsent(wallet, w -> new ArrayList<>());
                            changedKeystores.add(keystore);
                        }
                    }
                }

                if(label.type == LabelType.tx) {
                    for(Entry entry : transactionEntries) {
                        if(entry instanceof TransactionEntry transactionEntry) {
                            BlockTransaction blkTx = transactionEntry.getBlockTransaction();
                            if(blkTx.getHashAsString().equals(label.ref)) {
                                transactionEntry.getBlockTransaction().setLabel(label.label);
                                transactionEntry.labelProperty().set(label.label);
                                addChangedEntry(changedWalletEntries, entry);
                            }
                        }
                    }
                }

                if(label.type == LabelType.addr) {
                    for(Entry addressEntry : addressEntries) {
                        if(addressEntry instanceof NodeEntry nodeEntry) {
                            WalletNode addressNode = nodeEntry.getNode();
                            if(addressNode.getAddress().toString().equals(label.ref)) {
                                nodeEntry.getNode().setLabel(label.label);
                                nodeEntry.labelProperty().set(label.label);
                                addChangedEntry(changedWalletEntries, addressEntry);
                            }
                        }
                    }
                }

                if(label.type == LabelType.output || label.type == LabelType.input) {
                    for(Entry entry : transactionEntries) {
                        for(Entry hashIndexEntry : entry.getChildren()) {
                            if(hashIndexEntry instanceof TransactionHashIndexEntry txioEntry) {
                                BlockTransactionHashIndex reference = txioEntry.getHashIndex();
                                if((label.type == LabelType.output && txioEntry.getType() == HashIndexEntry.Type.OUTPUT && reference.toString().equals(label.ref))
                                        || (label.type == LabelType.input && txioEntry.getType() == HashIndexEntry.Type.INPUT && reference.toString().equals(label.ref))) {
                                    if(label.label != null && !label.label.isEmpty()) {
                                        reference.setLabel(label.label);
                                        txioEntry.labelProperty().set(label.label);
                                        addChangedEntry(changedWalletEntries, txioEntry);
                                    }

                                    if(label.type == LabelType.output && !reference.isSpent()) {
                                        if(Boolean.FALSE.equals(label.spendable) && reference.getStatus() != Status.FROZEN) {
                                            reference.setStatus(Status.FROZEN);
                                            addChangedUtxo(changedWalletUtxoStatuses, txioEntry);
                                        } else if(Boolean.TRUE.equals(label.spendable) && reference.getStatus() == Status.FROZEN) {
                                            reference.setStatus(null);
                                            addChangedUtxo(changedWalletUtxoStatuses, txioEntry);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    for(Entry addressEntry : addressEntries) {
                        for(Entry entry : addressEntry.getChildren()) {
                            updateHashIndexEntryLabel(label, entry);
                            for(Entry spentEntry : entry.getChildren()) {
                                updateHashIndexEntryLabel(label, spentEntry);
                            }
                        }
                    }
                    for(Entry entry : utxoEntries) {
                        updateHashIndexEntryLabel(label, entry);
                    }
                }
            }
        }

        for(Map.Entry<Wallet, List<Keystore>> walletKeystores : changedWalletKeystores.entrySet()) {
            Wallet wallet = walletKeystores.getKey();
            Storage storage = AppServices.get().getOpenWallets().get(wallet);
            EventManager.get().post(new KeystoreLabelsChangedEvent(wallet, wallet, storage.getWalletId(wallet), walletKeystores.getValue()));
        }

        for(Map.Entry<Wallet, List<Entry>> walletEntries : changedWalletEntries.entrySet()) {
            EventManager.get().post(new WalletEntryLabelsChangedEvent(walletEntries.getKey(), walletEntries.getValue(), false));
        }

        for(Map.Entry<Wallet, List<BlockTransactionHashIndex>> walletUtxos : changedWalletUtxoStatuses.entrySet()) {
            EventManager.get().post(new WalletUtxoStatusChangedEvent(walletUtxos.getKey(), walletUtxos.getValue()));
        }

        return walletForms.get(0).getWallet();
    }

    private static void updateHashIndexEntryLabel(Label label, Entry entry) {
        if(entry instanceof HashIndexEntry hashIndexEntry) {
            BlockTransactionHashIndex reference = hashIndexEntry.getHashIndex();
            if((label.type == LabelType.output && hashIndexEntry.getType() == HashIndexEntry.Type.OUTPUT && reference.toString().equals(label.ref))
                    || (label.type == LabelType.input && hashIndexEntry.getType() == HashIndexEntry.Type.INPUT && reference.toString().equals(label.ref))) {
                if(label.label != null && !label.label.isEmpty()) {
                    hashIndexEntry.labelProperty().set(label.label);
                }
            }
        }
    }

    private static void addChangedEntry(Map<Wallet, List<Entry>> changedEntries, Entry entry) {
        List<Entry> entries = changedEntries.computeIfAbsent(entry.getWallet(), wallet -> new ArrayList<>());
        entries.add(entry);
    }

    private static void addChangedUtxo(Map<Wallet, List<BlockTransactionHashIndex>> changedUtxos, TransactionHashIndexEntry utxoEntry) {
        List<BlockTransactionHashIndex> utxos = changedUtxos.computeIfAbsent(utxoEntry.getWallet(), w -> new ArrayList<>());
        utxos.add(utxoEntry.getHashIndex());
    }

    @Override
    public boolean isWalletImportScannable() {
        return false;
    }

    @Override
    public boolean exportsAllWallets() {
        return true;
    }

    private enum LabelType {
        tx, addr, pubkey, input, output, xpub
    }

    private static class Label {
        public Label(LabelType type, String ref, String label, String origin, Boolean spendable,
                     Long height, String time, String keypath, Long value) {
            this.type = type;
            this.ref = ref;
            this.label = label;
            this.origin = origin;
            this.spendable = spendable;
            this.height = height;
            this.time = time;
            this.keypath = keypath;
            this.value = value;
        }

        LabelType type;
        String ref;
        String label;
        String origin;
        Boolean spendable;  // BIP-329 requires JSON boolean (not string)
        Long height;        // block height; omitted if < 6 confirmations
        String time;        // ISO-8601 timestamp
        String keypath;     // BIP32 derivation path for addr records
        Long value;         // satoshis for output records
    }

    /** Deserializes a Label from JSONL, accepting spendable as boolean or legacy string form. */
    private static class LabelDeserializer implements JsonDeserializer<Label> {
        @Override
        public Label deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            LabelType type = null;
            if(obj.has("type") && !obj.get("type").isJsonNull()) {
                try { type = LabelType.valueOf(obj.get("type").getAsString()); } catch(Exception ignored) {}
            }

            String ref = obj.has("ref") && !obj.get("ref").isJsonNull() ? obj.get("ref").getAsString() : null;
            String label = obj.has("label") && !obj.get("label").isJsonNull() ? obj.get("label").getAsString() : null;
            String origin = obj.has("origin") && !obj.get("origin").isJsonNull() ? obj.get("origin").getAsString() : null;
            String keypath = obj.has("keypath") && !obj.get("keypath").isJsonNull() ? obj.get("keypath").getAsString() : null;
            String time = obj.has("time") && !obj.get("time").isJsonNull() ? obj.get("time").getAsString() : null;

            Long height = null;
            if(obj.has("height") && !obj.get("height").isJsonNull()) {
                try { height = obj.get("height").getAsLong(); } catch(Exception ignored) {}
            }

            Long value = null;
            if(obj.has("value") && !obj.get("value").isJsonNull()) {
                try { value = obj.get("value").getAsLong(); } catch(Exception ignored) {}
            }

            // Accept spendable as boolean (spec) or string (old Sparrow exports)
            Boolean spendable = null;
            if(obj.has("spendable") && !obj.get("spendable").isJsonNull()) {
                JsonElement sp = obj.get("spendable");
                if(sp.isJsonPrimitive()) {
                    JsonPrimitive prim = sp.getAsJsonPrimitive();
                    if(prim.isBoolean()) {
                        spendable = prim.getAsBoolean();
                    } else {
                        spendable = Boolean.parseBoolean(prim.getAsString());
                    }
                }
            }

            return new Label(type, ref, label, origin, spendable, height, time, keypath, value);
        }
    }
}
