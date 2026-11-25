package com.sparrowwallet.sparrow.terminal.wallet;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.ActionListDialogBuilder;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.crypto.ChildNumber;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.io.ImportException;
import com.sparrowwallet.sparrow.terminal.SparrowTerminal;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.sparrowwallet.drongo.wallet.StandardAccount.WHIRLPOOL_POSTMIX;
import static com.sparrowwallet.sparrow.gui.WatchOnlyWalletBuilder.DEFAULT_WATCH_ONLY_FINGERPRINT;

public class WatchOnlyDialog extends NewWalletDialog {
    private static final Logger log = LoggerFactory.getLogger(WatchOnlyDialog.class);

    private final TextBox descriptor;
    private final Button importWallet;
    private int selectedAccount = 0;

    public WatchOnlyDialog(String walletName) {
        super("Create Watch Only Wallet - " + walletName, walletName);

        setHints(List.of(Hint.CENTERED));

        Panel mainPanel = new Panel();
        mainPanel.setLayoutManager(new GridLayout(2).setVerticalSpacing(0));

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ZERO));

        TerminalSize screenSize = SparrowTerminal.get().getScreen().getTerminalSize();
        int descriptorWidth = Math.min(Math.max(20, screenSize.getColumns() - 20), 120);

        mainPanel.addComponent(new Label("Output descriptor or xpub\nBIP84 Native Segwit Deposit account or Postmix account"));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ZERO));

        descriptor = new TextBox(new TerminalSize(descriptorWidth, 10));
        mainPanel.addComponent(descriptor);
        mainPanel.addComponent(new EmptySpace(TerminalSize.ZERO));

        Panel buttonPanel = new Panel();
        buttonPanel.setLayoutManager(new GridLayout(2).setHorizontalSpacing(1));
        buttonPanel.addComponent(new Button("Cancel", this::onCancel));
        importWallet = new Button("Import Wallet", () -> new ActionListDialogBuilder()
                .setTitle("Select Account Type")
                .addAction("Deposit m/84'/0'/0'", () -> {
                    selectedAccount = 0;
                    createWallet();
                })
                .addAction(String.format("Postmix m/84'/0'/%s'", WHIRLPOOL_POSTMIX.getAccountNumber()), () -> {
                    selectedAccount = WHIRLPOOL_POSTMIX.getAccountNumber();
                    createWallet();
                })
                .build()
                .showDialog(SparrowTerminal.get().getGui())
        ).setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.CENTER, GridLayout.Alignment.CENTER, true, false));

        importWallet.setEnabled(false);
        buttonPanel.addComponent(importWallet);

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ZERO));

        buttonPanel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.END, GridLayout.Alignment.CENTER,false,false)).addTo(mainPanel);
        mainPanel.addComponent(new EmptySpace(TerminalSize.ZERO));

        setComponent(mainPanel);

        descriptor.setTextChangeListener((newText, changedByUserInteraction) -> {
            String line = newText.replaceAll("\\s+", "");
            try {
                if (isValidFromOutputDescriptor(line)) {
                    importWallet.setEnabled(true);
                }
            } catch(Exception e1) {
                try {
                    if(isValidFromExtendedKey(line)) {
                        importWallet.setEnabled(true);
                    }
                } catch(Exception e2) {
                    importWallet.setEnabled(false);
                }
            }

            if(changedByUserInteraction) {
                List<String> lines = splitString(newText, descriptorWidth);
                String splitText = lines.stream().reduce((s1, s2) -> s1 + "\n" + s2).get();
                if(!newText.equals(splitText)) {
                    descriptor.setText(splitText);

                    TerminalPosition pos = descriptor.getCaretPosition();
                    if(pos.getRow() == lines.size() - 2 && pos.getColumn() == lines.get(lines.size() - 2).length()) {
                        descriptor.setCaretPosition(lines.size() - 1, lines.get(lines.size() - 1).length());
                    }
                }
            }
        });
    }

    private static boolean isValidFromExtendedKey(final String givenXPubStr) {
        final ExtendedKey givenXpub = ExtendedKey.fromDescriptor(givenXPubStr);

        final ExtendedKey.Header xpubHeader = ExtendedKey.Header.fromExtendedKey(givenXPubStr);
        final ScriptType scriptType = xpubHeader.getDefaultScriptType();
        if (isValidScript(scriptType)) {
            return isNativeDepositOrPostmixAccount(givenXpub);
        }
        return false;
    }

    private static boolean isValidFromOutputDescriptor(final String givenXPubStr) {
        final OutputDescriptor outputDescriptor = OutputDescriptor.getOutputDescriptor(givenXPubStr);
        final ScriptType scriptType = outputDescriptor.getScriptType();
        if (isValidScript(scriptType)) {
            return isNativeDepositOrPostmixAccount(outputDescriptor.getSingletonExtendedPublicKey());
        }
        return false;
    }

    private static boolean isValidScript(final ScriptType scriptType) {
        if (scriptType == ScriptType.P2PKH) return true;
        return "wpkh(".equalsIgnoreCase(scriptType.getDescriptor());
    }

    private static boolean isNativeDepositOrPostmixAccount(final ExtendedKey givenXpub) {
        final String[] possibleDerivations = {"84h/0h/0h", String.format("84h/0h/%sh", WHIRLPOOL_POSTMIX.getAccountNumber())};
        for(String possibleDerivation : possibleDerivations) {
            final List<ChildNumber> derivation = KeyDerivation.parsePath(possibleDerivation);
            final ChildNumber keyChildNumber = derivation.isEmpty() ? ChildNumber.ZERO : derivation.get(derivation.size() - 1);
            final ExtendedKey xpub = new ExtendedKey(givenXpub.getKey(), givenXpub.getParentFingerprint(), keyChildNumber);
            if(xpub.equals(givenXpub)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected List<Wallet> getWallets() {
        try {
            return getWalletFromXpub(selectedAccount);
        } catch(Exception e1) {
            try {
                return getWalletFromOutputDescriptor();
            } catch(Exception e2) {
                log.error("Could not determine wallet from descriptor: " + descriptor.getText(), e2);
            }
        }

        return Collections.emptyList();
    }


    private List<Wallet> getWalletFromXpub(final int account) {

        final Set<ScriptType> scriptTypes = new LinkedHashSet<>();
        scriptTypes.add(ScriptType.P2WPKH);
        final ExtendedKey.Header header = ExtendedKey.Header.fromExtendedKey(descriptor.getText());
        scriptTypes.add(header.getDefaultScriptType());
        scriptTypes.addAll(ScriptType.getAddressableScriptTypes(PolicyType.SINGLE));

        final ExtendedKey xpub = ExtendedKey.fromDescriptor(descriptor.getText().replaceAll("\\s+", ""));
        final List<Wallet> wallets = new ArrayList<>();
        for(ScriptType scriptType : scriptTypes) {

            String derivationPath = scriptType.getDefaultDerivationPath();
            if (account != 0) {
                final int accountIndex = StringUtils.lastIndexOf(derivationPath, "0");
                derivationPath = derivationPath.substring(0, accountIndex) + account + derivationPath.substring(accountIndex + 1);
            }

            Wallet wallet = new Wallet(walletName);
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(scriptType);

            Keystore keystore = new Keystore();
            keystore.setSource(KeystoreSource.SW_WATCH);
            keystore.setWalletModel(WalletModel.SPARROW);
            keystore.setKeyDerivation(new KeyDerivation(DEFAULT_WATCH_ONLY_FINGERPRINT, derivationPath));
            keystore.setExtendedPublicKey(xpub);
            wallet.makeLabelsUnique(keystore);
            wallet.getKeystores().add(keystore);

            wallet.setDefaultPolicy(Policy.getPolicy(wallet.getPolicyType(), wallet.getScriptType(), wallet.getKeystores(), 1));
            wallets.add(wallet);
        }

        return wallets;
    }

    private List<Wallet> getWalletFromOutputDescriptor() {
        OutputDescriptor outputDescriptor = OutputDescriptor.getOutputDescriptor(descriptor.getText().replaceAll("\\s+", ""));
        Wallet wallet = outputDescriptor.toWallet();
        wallet.setName(walletName);
        return List.of(wallet);
    }

    private List<String> splitString(String stringToSplit, int maxLength) {
        String text = stringToSplit.replaceAll("\\s+", "");
        if(stringToSplit.endsWith("\n")) {
            text += "\n";
        }

        List<String> lines = new ArrayList<>();
        while(text.length() >= maxLength) {
            int breakAt = maxLength - 1;
            lines.add(text.substring(0, breakAt));
            text = text.substring(breakAt);
        }

        if(text.equals("\n")) {
            text = "";
        }

        lines.add(text);
        return lines;
    }
}
