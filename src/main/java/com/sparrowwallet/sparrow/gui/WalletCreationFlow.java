package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.DicewareWordList;
import com.sparrowwallet.sparrow.control.HelpLabel;
import com.sparrowwallet.sparrow.control.LifeHashIcon;
import com.sparrowwallet.sparrow.control.SeedEntryDialog;
import com.sparrowwallet.sparrow.control.ViewPasswordField;
import com.sparrowwallet.sparrow.event.StorageEvent;
import com.sparrowwallet.sparrow.event.TimedEvent;
import com.sparrowwallet.sparrow.io.Bip39;
import com.sparrowwallet.sparrow.io.ImportException;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.io.StorageException;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import com.sparrowwallet.sparrow.wallet.KeystoreController;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolServices;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;


/**
 * Implements the wallet creation / restore flow as native JavaFX dialogs,
 * mirroring what the TUI does in MasterActionListBox + NewWalletDialog.
 */
public class WalletCreationFlow {
    private static final double WIZARD_DIALOG_WIDTH = 480;
    private static final Logger log = LoggerFactory.getLogger(WalletCreationFlow.class);

    private final Stage owner;
    private final AshigaruMainController mainController;

    public WalletCreationFlow(Stage owner, AshigaruMainController mainController) {
        this.owner = owner;
        this.mainController = mainController;
    }

    // -------------------------------------------------------------------------
    // Shared cohesive dialog styling (reused across the create/restore journey,
    // and by AshigaruMainController for the open/unlock + delete prompts)
    // -------------------------------------------------------------------------

    /** Themes the dialog and gives it a consistent custom header (eyebrow + title + subtitle). */
    static void styleWizardDialog(Dialog<?> dlg, String eyebrow, String title, String subtitle) {
        DialogPane pane = dlg.getDialogPane();
        AppServices.addAshigaruStylesheets(pane.getStylesheets());

        VBox header = new VBox(2);
        header.getStyleClass().add("wizard-header");
        header.setPadding(new Insets(16, 20, 16, 20));
        if(eyebrow != null) {
            Label eyebrowLabel = new Label(eyebrow);
            eyebrowLabel.getStyleClass().add("wizard-eyebrow");
            header.getChildren().add(eyebrowLabel);
        }
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("wizard-title");
        header.getChildren().add(titleLabel);
        if(subtitle != null) {
            Label subtitleLabel = new Label(subtitle);
            subtitleLabel.getStyleClass().add("wizard-subtitle");
            bindWrap(subtitleLabel);
            header.getChildren().add(subtitleLabel);
        }
        pane.setHeaderText(null);
        pane.setHeader(header);
    }

    /** Content width these wizard dialogs wrap text to (they are a fixed ~460-480 wide, non-resizable). */
    private static final double WIZARD_WRAP_WIDTH = 410;

    /**
     * Makes a wrapping label actually wrap instead of clipping to an ellipsis.
     *
     * <p>Two parts, and the height one is what actually matters: a wrapText Label asked for its
     * preferred height without a width (prefHeight(-1)) reports the height of a <em>single line</em>,
     * so its parent allocates one line of vertical space, the label then wraps at its real width, and
     * everything past that first line is ellipsized. Setting minHeight to USE_PREF_SIZE makes the
     * parent query minHeight(width) at the actual layout width, which returns the true wrapped height
     * and forces enough room.
     *
     * <p>That covers layout but not measurement. A dialog decides how large to be by asking its
     * content for a preferred size <em>before</em> anything has a width, and minHeight(USE_PREF_SIZE)
     * contributes nothing to that pass - which is why these dialogs, once their fixed height caps
     * were removed, sized themselves off a single line of text. Setting a concrete prefWidth as well
     * gives computePrefHeight(-1) a width to wrap against, so the true height reaches the dialog and
     * it sizes itself correctly with no cap to fight the text.
     */
    private static void bindWrap(Label label) {
        label.setWrapText(true);
        label.setMaxWidth(WIZARD_WRAP_WIDTH);
        label.setMinHeight(Region.USE_PREF_SIZE);
    }

    /** Gives the dialog's buttons a consistent primary/secondary hierarchy. */
    static void styleWizardButtons(DialogPane pane) {
        for(ButtonType bt : pane.getButtonTypes()) {
            Node n = pane.lookupButton(bt);
            if(n instanceof Button b) {
                if(bt.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                    if(!b.getStyleClass().contains("primary-btn")) b.getStyleClass().add("primary-btn");
                } else if(!b.getStyleClass().contains("action-btn")) {
                    b.getStyleClass().add("action-btn");
                }
            }
        }
    }

    /** A large, full-width selectable card button (title + description). */
    private Button typeCard(String title, String desc) {
        Label t = new Label(title);
        t.getStyleClass().add("type-card-title");
        cardWrap(t);
        Label d = new Label(desc);
        d.getStyleClass().add("type-card-desc");
        cardWrap(d);
        VBox box = new VBox(4, t, d);
        box.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(box);
        b.getStyleClass().add("type-card");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }

    /**
     * Wraps a label inside a type card. Deliberately imposes no width: binding the card content to
     * b.widthProperty() looks like the right fix for a Button laying its graphic out at the graphic's
     * preferred width, but the button has no width while the dialog is measuring, so the binding
     * resolves to zero and the labels report a line per character. The dialog's own preferred height
     * is what keeps these compact; minHeight is what stops them clipping.
     */
    private static void cardWrap(Label label) {
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
    }

    /** Entry point — call from the JavaFX UI thread. */
    public void start() {
        String walletName = askWalletName();
        if (walletName == null) return;

        String walletType = askWalletType();
        if (walletType == null) return;

        if ("Hot Wallet".equals(walletType)) {
            showBip39Dialog(walletName);
        } else {
            showWatchOnlyDialog(walletName);
        }
    }

    // -------------------------------------------------------------------------
    // Step 1 – wallet name
    // -------------------------------------------------------------------------

    private String askWalletName() {
        while (true) {
            Dialog<ButtonType> dlg = new Dialog<>();
            dlg.setTitle("Create Wallet");
            dlg.initOwner(owner);
            styleWizardDialog(dlg, "NEW / RESTORE WALLET", "Name your wallet",
                    "Choose a name to identify this wallet.");

            ButtonType continueType = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
            dlg.getDialogPane().getButtonTypes().addAll(continueType, ButtonType.CANCEL);

            Label lbl = new Label("Wallet name");
            lbl.getStyleClass().add("field-label");
            TextField nameField = new TextField();
            nameField.setPromptText("e.g. Savings");
            VBox content = new VBox(8, lbl, nameField);
            content.setPadding(new Insets(20));
            content.setPrefWidth(WIZARD_DIALOG_WIDTH);
            dlg.getDialogPane().setContent(content);
            dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);
            dlg.getDialogPane().setPrefHeight(180);
            AppServices.moveToActiveWindowScreen(dlg);

            Button continueNode = (Button) dlg.getDialogPane().lookupButton(continueType);
            continueNode.setDisable(true);
            nameField.textProperty().addListener((o, a, b) -> continueNode.setDisable(b.trim().isEmpty()));
            styleWizardButtons(dlg.getDialogPane());
            Platform.runLater(nameField::requestFocus);

            dlg.setResultConverter(bt -> bt);
            Optional<ButtonType> result = dlg.showAndWait();
            if (result.isEmpty() || result.get() != continueType) return null;

            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                showError("Invalid Name", "Please enter a name for the wallet.");
                continue;
            }
            if (Storage.walletExists(name)) {
                showError("Wallet Exists", "A wallet named \"" + name + "\" already exists. Choose a different name.");
                continue;
            }
            return name;
        }
    }

    // -------------------------------------------------------------------------
    // Step 2 – wallet type
    // -------------------------------------------------------------------------

    private String askWalletType() {
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle("Create Wallet");
        dlg.initOwner(owner);
        styleWizardDialog(dlg, "NEW / RESTORE WALLET", "Choose wallet type",
                "How do you want to set up this wallet?");
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        Button hot = typeCard("Hot Wallet", "Create a new wallet or restore from a BIP39 seed phrase.");
        Button watch = typeCard("Watch Only", "Import an xpub or output descriptor to watch addresses.");
        hot.setOnAction(e -> { dlg.setResult("Hot Wallet"); dlg.close(); });
        watch.setOnAction(e -> { dlg.setResult("Watch Only"); dlg.close(); });

        VBox content = new VBox(12, hot, watch);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setPrefHeight(240);
        AppServices.moveToActiveWindowScreen(dlg);
        styleWizardButtons(dlg.getDialogPane());

        dlg.setResultConverter(bt -> null);
        return dlg.showAndWait().orElse(null);
    }

    // -------------------------------------------------------------------------
    // Step 3a – BIP39 hot wallet dialog
    // -------------------------------------------------------------------------

    private void showBip39Dialog(String walletName) {
        // Step 1: seed length
        Integer count = askWordCount();
        if (count == null) return;

        // Step 2: enter / generate the seed on a numbered word grid (reused widget,
        // with live BIP39 autocomplete, paste-fill, Generate New, and checksum gating).
        SeedEntryDialog seedDialog = new SeedEntryDialog(walletName, count);
        seedDialog.initOwner(owner);
        Optional<List<String>> seedResult = seedDialog.showAndWait();
        if (seedResult.isEmpty() || seedResult.get() == null) return;
        List<String> words = seedResult.get();

        // Step 3: BIP39 passphrase. A freshly generated seed gets the guided, dice-first flow; a typed-in
        // seed is a restore, whose passphrase (if any) already exists and must be accepted as it is.
        String passphrase = seedDialog.isGenerated()
                ? askPassphrase(walletName, words)
                : askBip39Passphrase(walletName, words, true);
        if (passphrase == null) return;

        try {
            Bip39 importer = new Bip39();
            Wallet wallet = new Wallet(walletName);
            wallet.setPolicyType(PolicyType.SINGLE);
            wallet.setScriptType(ScriptType.P2WPKH);
            Keystore keystore = importer.getKeystore(ScriptType.P2WPKH.getDefaultDerivation(), words, passphrase);
            wallet.getKeystores().add(keystore);
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
            discoverAndSave(walletName, List.of(wallet));
        } catch (ImportException e) {
            showError("Invalid Seed", "Could not import wallet from seed: " + e.getMessage());
        }
    }

    private Integer askWordCount() {
        Dialog<Integer> dlg = new Dialog<>();
        dlg.setTitle("Create Wallet");
        dlg.initOwner(owner);
        styleWizardDialog(dlg, "NEW / RESTORE WALLET", "Seed length",
                "How many words is your seed phrase?");
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        Button w12 = typeCard("12 words", "Standard length, used by most wallets.");
        Button w24 = typeCard("24 words", "Maximum entropy — recommended for new wallets.");
        w12.setOnAction(e -> { dlg.setResult(12); dlg.close(); });
        w24.setOnAction(e -> { dlg.setResult(24); dlg.close(); });

        VBox content = new VBox(12, w12, w24);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setPrefHeight(240);
        AppServices.moveToActiveWindowScreen(dlg);
        styleWizardButtons(dlg.getDialogPane());

        dlg.setResultConverter(bt -> null);
        return dlg.showAndWait().orElse(null);
    }

    /**
     * Typed passphrase dialog. For a new wallet the passphrase is required and hand-typed input gets a
     * weakness hint; for a restore the user enters whatever passphrase the wallet was made with, so any
     * value is accepted, including none.
     */
    private String askBip39Passphrase(String walletName, List<String> words, boolean restore) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle((restore ? "Restore BIP39 Wallet – " : "Create BIP39 Wallet – ") + walletName);
        dlg.initOwner(owner);
        styleWizardDialog(dlg, "NEW / RESTORE WALLET", "BIP39 passphrase", restore
                ? "Enter the passphrase this wallet was created with. Leave it blank if it has none."
                : "Add a passphrase. It becomes part of your wallet — you'll need it every time you open it.");

        Label passLabel = new Label("BIP39 Passphrase:");
        passLabel.getStyleClass().add("field-label");
        ViewPasswordField passField = new ViewPasswordField();
        passField.setPromptText(restore ? "Leave blank for none" : "Enter passphrase");

        Label passConfirmLabel = new Label("Confirm Passphrase:");
        passConfirmLabel.getStyleClass().add("field-label");
        ViewPasswordField passConfirmField = new ViewPasswordField();
        passConfirmField.setPromptText("Re-enter passphrase");

        // Advisory (never blocking) weak-passphrase hint for hand-typed input.
        Label weaknessLabel = new Label();
        weaknessLabel.getStyleClass().add("passphrase-weakness");
        bindWrap(weaknessLabel);
        weaknessLabel.managedProperty().bind(weaknessLabel.visibleProperty());
        weaknessLabel.setVisible(false);
        if (!restore) {
            passField.textProperty().addListener((obs, old, text) -> {
                Optional<String> weakness = DicewareWordList.passphraseWeakness(text);
                weaknessLabel.setText(weakness.orElse(""));
                weaknessLabel.setVisible(weakness.isPresent());
            });
        }

        ObjectProperty<byte[]> masterFingerprint = new SimpleObjectProperty<>();

        HBox fingerprintBox = new HBox(10);
        fingerprintBox.setAlignment(Pos.CENTER_LEFT);
        Label fingerprintLabel = new Label("Master fingerprint:");
        fingerprintLabel.getStyleClass().add("field-label");
        TextField fingerprintHex = new TextField();
        fingerprintHex.setDisable(true);
        fingerprintHex.setMaxWidth(80);
        fingerprintHex.getStyleClass().addAll("fixed-width", "fingerprint-hex");
        masterFingerprint.addListener((obs, oldVal, newVal) ->
                fingerprintHex.setText(newVal != null ? Utils.bytesToHex(newVal) : ""));
        LifeHashIcon lifeHashIcon = new LifeHashIcon();
        lifeHashIcon.dataProperty().bind(masterFingerprint);
        HelpLabel helpLabel = new HelpLabel();
        helpLabel.setHelpText("All passphrases create valid wallets." +
                "\nThe master fingerprint identifies the keystore and changes as the passphrase changes." +
                "\nMake sure you recognise it before proceeding.");
        Button copyFpBtn = new Button("⎘");
        copyFpBtn.getStyleClass().add("copy-icon-btn");
        copyFpBtn.setPrefSize(28, 28);
        copyFpBtn.disableProperty().bind(masterFingerprint.isNull());
        copyFpBtn.setOnAction(e -> {
            if (fingerprintHex.getText().isEmpty()) return;
            ClipboardContent cc = new ClipboardContent();
            cc.putString(fingerprintHex.getText());
            Clipboard.getSystemClipboard().setContent(cc);
            copyFpBtn.setText("✓");
            PauseTransition pause = new PauseTransition(javafx.util.Duration.seconds(1.5));
            pause.setOnFinished(ev -> copyFpBtn.setText("⎘"));
            pause.play();
        });
        fingerprintBox.getChildren().addAll(fingerprintLabel, fingerprintHex, copyFpBtn, lifeHashIcon, helpLabel);

        VBox content = new VBox(12, passLabel, passField, passConfirmLabel, passConfirmField, weaknessLabel, fingerprintBox);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);

        ButtonType createType = new ButtonType(restore ? "Restore Wallet" : "Create Wallet", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);
        Button createNode = (Button) dlg.getDialogPane().lookupButton(createType);
        createNode.setDisable(true);
        styleWizardButtons(dlg.getDialogPane());

        Bip39 importer = new Bip39();
        String seedText = String.join(" ", words);
        Runnable update = () -> {
            boolean valid = (restore || !passField.getText().isEmpty())
                    && passField.getText().equals(passConfirmField.getText());
            createNode.setDisable(!valid);
            masterFingerprint.set(computeFingerprint(importer, seedText, passField.getText()));
        };
        passField.textProperty().addListener((obs, old, text) -> update.run());
        passConfirmField.textProperty().addListener((obs, old, text) -> update.run());
        update.run();

        dlg.setResultConverter(bt -> bt);
        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != createType) return null;
        return passField.getText();
    }

    private byte[] computeFingerprint(Bip39 importer, String seedText, String passphrase) {
        String[] words = seedText.trim().split("\\s+");
        if (words.length < 12) return null;
        try {
            Keystore ks = importer.getKeystore(ScriptType.P2WPKH.getDefaultDerivation(), Arrays.asList(words), passphrase);
            return ks.getExtendedMasterPrivateKey().getKey().getFingerprint();
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Step 3 – passphrase (guided, dice-first)
    // -------------------------------------------------------------------------

    private static final int DICEWARE_MIN_WORDS = 6;

    /**
     * Joins rolled words with a dash rather than a space. The passphrase has to be copied off the
     * screen by hand and typed back in later, possibly into another wallet, and a space is the one
     * separator a person cannot verify they reproduced: one space or two look identical on paper, and
     * a trailing one is invisible. A dash is transcribed exactly or not at all.
     *
     * <p>Every use of the passphrase goes through here, so the phrase shown on screen, the string the
     * fingerprint is derived from and the passphrase the wallet is created with cannot drift apart.
     */
    private static final String DICEWARE_SEPARATOR = "-";

    private static String dicewarePassphrase(List<String> passWords) {
        return String.join(DICEWARE_SEPARATOR, passWords);
    }

    private enum DiceChoice { HAVE_DICE, NO_DICE }

    /**
     * Orchestrates the passphrase step: teach why a passphrase matters and branch on whether the user
     * has physical dice. Returns the chosen passphrase, or null if the user cancels / decides to wait.
     */
    private String askPassphrase(String walletName, List<String> words) {
        DiceChoice choice = askHaveDice(walletName);
        if (choice == null) return null;
        if (choice == DiceChoice.HAVE_DICE) {
            return askDicewarePassphrase(walletName, words);
        }
        Boolean continueWithout = askNoDice(walletName);
        if (continueWithout == null || !continueWithout) return null; // "wait" => cancel creation
        return askBip39Passphrase(walletName, words, false);
    }

    /** "A passphrase, not a password" intro + "Do you have dice?" choice. */
    private DiceChoice askHaveDice(String walletName) {
        Dialog<DiceChoice> dlg = new Dialog<>();
        dlg.setTitle("Create BIP39 Wallet – " + walletName);
        dlg.initOwner(owner);
        styleWizardDialog(dlg, "NEW / RESTORE WALLET", "A passphrase, not a password",
                "Do you have dice handy?");
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        Label body = new Label("A password is a single word — too short to protect your wallet. "
                + "A passphrase is several words, and the strongest way to choose them is to roll physical dice.");
        body.getStyleClass().add("wizard-subtitle");
        bindWrap(body);

        Button yes = typeCard("Yes, I have dice", "Roll your own passphrase — the most secure option.");
        Button no = typeCard("No, I don't have dice", "Type a passphrase instead, or come back with dice.");
        yes.setDisable(DicewareWordList.INSTANCE == null);
        yes.setOnAction(e -> { dlg.setResult(DiceChoice.HAVE_DICE); dlg.close(); });
        no.setOnAction(e -> { dlg.setResult(DiceChoice.NO_DICE); dlg.close(); });

        VBox content = new VBox(14, body, yes, no);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setPrefHeight(300);
        AppServices.moveToActiveWindowScreen(dlg);
        styleWizardButtons(dlg.getDialogPane());

        dlg.setResultConverter(bt -> null);
        return dlg.showAndWait().orElse(null);
    }

    /** No dice: wait until they have some, or continue to the typed passphrase dialog. */
    private Boolean askNoDice(String walletName) {
        Dialog<Boolean> dlg = new Dialog<>();
        dlg.setTitle("Create BIP39 Wallet – " + walletName);
        dlg.initOwner(owner);
        styleWizardDialog(dlg, "NEW / RESTORE WALLET", "No dice?",
                "Dice give you the strongest passphrase.");
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        Button wait = typeCard("Wait — I'll get dice first",
                "Stop here and create your wallet once you have five dice.");
        Button cont = typeCard("Continue without dice",
                "Type your own passphrase now. Make it long — several unrelated words.");
        wait.setOnAction(e -> { dlg.setResult(Boolean.FALSE); dlg.close(); });
        cont.setOnAction(e -> { dlg.setResult(Boolean.TRUE); dlg.close(); });

        VBox content = new VBox(12, wait, cont);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setPrefHeight(300);
        AppServices.moveToActiveWindowScreen(dlg);
        styleWizardButtons(dlg.getDialogPane());

        dlg.setResultConverter(bt -> null);
        return dlg.showAndWait().orElse(null);
    }

    /**
     * Dice path: roll one word per screen (minimum {@value #DICEWARE_MIN_WORDS}), then review the
     * assembled phrase + fingerprint and either add another word or create the wallet. Physical dice
     * only — the app never generates the rolls. Returns the space-joined passphrase, or null to cancel.
     */
    private String askDicewarePassphrase(String walletName, List<String> seedWords) {
        if (DicewareWordList.INSTANCE == null) {
            showError("Wordlist unavailable", "The diceware wordlist could not be loaded.");
            return null;
        }
        List<String> passWords = new ArrayList<>();
        while (passWords.size() < DICEWARE_MIN_WORDS) {
            String word = rollWordDialog(walletName, passWords.size() + 1);
            if (word == null) return null;
            passWords.add(word);
        }
        while (true) {
            int action = reviewDicewareDialog(walletName, seedWords, passWords);
            if (action < 0) return null;                 // cancel
            if (action == 1) return dicewarePassphrase(passWords); // create wallet
            String word = rollWordDialog(walletName, passWords.size() + 1); // add another word
            if (word != null) passWords.add(word);       // null add => back to review unchanged
        }
    }

    /** One word: five dice inputs (1–6) resolving live to its EFF word. Returns the word, or null. */
    private String rollWordDialog(String walletName, int wordNumber) {
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle("Create BIP39 Wallet – " + walletName);
        dlg.initOwner(owner);
        styleWizardDialog(dlg, "ROLL YOUR PASSPHRASE", "Word " + wordNumber,
                "Roll five dice and enter each result (1–6).");

        List<TextField> dieFields = new ArrayList<>();
        HBox diceRow = new HBox(8);
        diceRow.setAlignment(Pos.CENTER_LEFT);
        Label wordLabel = new Label("—");
        wordLabel.getStyleClass().add("diceware-word");

        ButtonType addType = new ButtonType("Add word", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);
        Button addNode = (Button) dlg.getDialogPane().lookupButton(addType);
        addNode.setDisable(true);

        Runnable recompute = () -> {
            StringBuilder sb = new StringBuilder();
            for (TextField f : dieFields) sb.append(f.getText());
            Optional<String> word = DicewareWordList.INSTANCE.wordForRoll(sb.toString());
            wordLabel.setText(word.orElse("—"));
            addNode.setDisable(word.isEmpty());
        };

        for (int i = 0; i < 5; i++) {
            TextField die = new TextField();
            die.setPrefWidth(46);
            die.setAlignment(Pos.CENTER);
            die.getStyleClass().add("dice-die-input");
            die.setTextFormatter(new TextFormatter<>(c -> {
                String proposed = c.getControlNewText();
                return (proposed.length() <= 1 && proposed.matches("[1-6]?")) ? c : null;
            }));
            final int idx = i;
            die.textProperty().addListener((obs, old, val) -> {
                if (val.length() == 1 && idx < 4) dieFields.get(idx + 1).requestFocus();
                recompute.run();
            });
            dieFields.add(die);
            diceRow.getChildren().add(die);
        }

        Label arrow = new Label("→");
        arrow.getStyleClass().add("diceware-arrow");
        HBox resolvedRow = new HBox(10, diceRow, arrow, wordLabel);
        resolvedRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Each word adds " + String.format("%.1f", DicewareWordList.BITS_PER_WORD)
                + " bits of entropy.");
        hint.getStyleClass().add("diceware-instruction");

        VBox content = new VBox(14, resolvedRow, hint);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);
        AppServices.moveToActiveWindowScreen(dlg);
        styleWizardButtons(dlg.getDialogPane());
        Platform.runLater(() -> dieFields.get(0).requestFocus());

        dlg.setResultConverter(bt -> {
            if (bt != addType) return null;
            StringBuilder sb = new StringBuilder();
            for (TextField f : dieFields) sb.append(f.getText());
            return DicewareWordList.INSTANCE.wordForRoll(sb.toString()).orElse(null);
        });
        return dlg.showAndWait().orElse(null);
    }

    /** Review the rolled phrase + fingerprint. Returns 1 = create wallet, 0 = add a word, -1 = cancel. */
    private int reviewDicewareDialog(String walletName, List<String> seedWords, List<String> passWords) {
        Dialog<Integer> dlg = new Dialog<>();
        dlg.setTitle("Create BIP39 Wallet – " + walletName);
        dlg.initOwner(owner);
        long bits = Math.round(passWords.size() * DicewareWordList.BITS_PER_WORD);
        styleWizardDialog(dlg, "ROLL YOUR PASSPHRASE", "Your passphrase",
                passWords.size() + " words · " + bits + " bits of entropy");

        Label phrase = new Label(dicewarePassphrase(passWords));
        phrase.getStyleClass().add("diceware-passphrase");
        bindWrap(phrase);

        ObjectProperty<byte[]> masterFingerprint = new SimpleObjectProperty<>();
        HBox fingerprintBox = new HBox(10);
        fingerprintBox.setAlignment(Pos.CENTER_LEFT);
        Label fingerprintLabel = new Label("Master fingerprint:");
        fingerprintLabel.getStyleClass().add("field-label");
        TextField fingerprintHex = new TextField();
        fingerprintHex.setDisable(true);
        fingerprintHex.setMaxWidth(80);
        fingerprintHex.getStyleClass().addAll("fixed-width", "fingerprint-hex");
        masterFingerprint.addListener((obs, oldVal, newVal) ->
                fingerprintHex.setText(newVal != null ? Utils.bytesToHex(newVal) : ""));
        LifeHashIcon lifeHashIcon = new LifeHashIcon();
        lifeHashIcon.dataProperty().bind(masterFingerprint);
        CopyButton copyFpBtn = new CopyButton();
        copyFpBtn.disableProperty().bind(masterFingerprint.isNull());
        copyFpBtn.setOnAction(e -> {
            if (fingerprintHex.getText().isEmpty()) return;
            copyFpBtn.copy(fingerprintHex.getText());
        });
        fingerprintBox.getChildren().addAll(fingerprintLabel, fingerprintHex, copyFpBtn, lifeHashIcon);

        Bip39 importer = new Bip39();
        masterFingerprint.set(computeFingerprint(importer, String.join(" ", seedWords), dicewarePassphrase(passWords)));

        Label warning = new Label("Write down your passphrase AND this master fingerprint, exactly — "
                + "including the dashes between the words, which are part of the passphrase. "
                + "They are never stored — if you lose them, your funds cannot be recovered. "
                + "The fingerprint is how you check you have typed the passphrase back correctly.");
        warning.getStyleClass().add("passphrase-warning");
        bindWrap(warning);

        VBox content = new VBox(14, phrase, fingerprintBox, warning);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);

        ButtonType addType = new ButtonType("Add another word", ButtonBar.ButtonData.LEFT);
        ButtonType createType = new ButtonType("Create Wallet", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(addType, createType, ButtonType.CANCEL);
        AppServices.moveToActiveWindowScreen(dlg);
        styleWizardButtons(dlg.getDialogPane());

        dlg.setResultConverter(bt -> bt == createType ? 1 : bt == addType ? 0 : -1);
        return dlg.showAndWait().orElse(-1);
    }

    // -------------------------------------------------------------------------
    // Step 3b – Watch Only wallet dialog
    // -------------------------------------------------------------------------

    private void showWatchOnlyDialog(String walletName) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Create Watch Only Wallet – " + walletName);
        dlg.initOwner(owner);
        styleWizardDialog(dlg, "NEW / RESTORE WALLET", "Watch-only wallet",
                "Import an xpub or output descriptor to watch addresses without spending.");

        Label hint = new Label("Output descriptor or xpub\n(BIP84 Native Segwit Deposit or Postmix account)");
        hint.getStyleClass().add("field-label");
        TextArea descriptorArea = new TextArea();
        descriptorArea.getStyleClass().add("mono-area");
        descriptorArea.setWrapText(true);
        descriptorArea.setPrefRowCount(6);
        descriptorArea.setPromptText("Paste your xpub or output descriptor here…");

        VBox content = new VBox(10, hint, descriptorArea);
        content.setPadding(new Insets(20));
        content.setPrefWidth(WIZARD_DIALOG_WIDTH);
        dlg.getDialogPane().setContent(content);
        dlg.getDialogPane().setPrefWidth(WIZARD_DIALOG_WIDTH);

        ButtonType importType = new ButtonType("Import Wallet", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(importType, ButtonType.CANCEL);

        Button importNode = (Button) dlg.getDialogPane().lookupButton(importType);
        importNode.setDisable(true);
        styleWizardButtons(dlg.getDialogPane());
        descriptorArea.textProperty().addListener((obs, old, text) ->
                importNode.setDisable(!isValidDescriptorOrXpub(text.replaceAll("\\s+", ""))));

        dlg.setResultConverter(bt -> bt);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isEmpty() || result.get() != importType) return;

        String raw = descriptorArea.getText().replaceAll("\\s+", "");
        List<Wallet> wallets = buildWatchOnlyWallets(walletName, raw);
        if (wallets.isEmpty()) {
            showError("Invalid Input", "Could not parse the descriptor or xpub.");
            return;
        }

        discoverAndSave(walletName, wallets);
    }

    private boolean isValidDescriptorOrXpub(String text) {
        if (text.isEmpty()) return false;
        try {
            OutputDescriptor.getOutputDescriptor(text);
            return true;
        } catch (Exception e1) {
            try {
                ExtendedKey.fromDescriptor(text);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private List<Wallet> buildWatchOnlyWallets(String walletName, String raw) {
        try {
            OutputDescriptor desc = OutputDescriptor.getOutputDescriptor(raw);
            Wallet wallet = desc.toWallet();
            wallet.setName(walletName);
            return List.of(wallet);
        } catch (Exception e1) {
            try {
                ExtendedKey xpub = ExtendedKey.fromDescriptor(raw);
                Wallet wallet = new Wallet(walletName);
                wallet.setPolicyType(PolicyType.SINGLE);
                wallet.setScriptType(ScriptType.P2WPKH);
                Keystore keystore = new Keystore();
                keystore.setSource(KeystoreSource.SW_WATCH);
                keystore.setWalletModel(WalletModel.SPARROW);
                keystore.setKeyDerivation(new KeyDerivation(
                        KeystoreController.DEFAULT_WATCH_ONLY_FINGERPRINT,
                        ScriptType.P2WPKH.getDefaultDerivationPath()));
                keystore.setExtendedPublicKey(xpub);
                wallet.makeLabelsUnique(keystore);
                wallet.getKeystores().add(keystore);
                wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), 1));
                return List.of(wallet);
            } catch (Exception e2) {
                log.error("Could not build watch only wallet from: " + raw, e2);
                return Collections.emptyList();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Account discovery + save (mirrors TUI NewWalletDialog logic)
    // -------------------------------------------------------------------------

    private void discoverAndSave(String walletName, List<Wallet> wallets) {
        if (wallets.isEmpty()) return;

        if (AppServices.onlineProperty().get()) {
            ElectrumServer.WalletDiscoveryService svc = new ElectrumServer.WalletDiscoveryService(wallets);

            Dialog<Void> progress = new Dialog<>();
            progress.setTitle(walletName);
            progress.initOwner(owner);
            progress.initModality(Modality.APPLICATION_MODAL);
            styleWizardDialog(progress, "NEW / RESTORE WALLET", "Discovering accounts",
                    "Looking for previous transactions on the blockchain…");

            // Cancel button — gives user an immediate escape hatch
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            progress.getDialogPane().getButtonTypes().add(cancelType);
            styleWizardButtons(progress.getDialogPane());

            Label descLabel = new Label("Looking for previous transactions on the blockchain.");
            bindWrap(descLabel);

            Label statusLabel = new Label();
            statusLabel.textProperty().bind(svc.messageProperty());
            statusLabel.setOpacity(0.65);

            ProgressBar bar = new ProgressBar();
            bar.setPrefWidth(320);
            bar.progressProperty().bind(svc.progressProperty());

            VBox content = new VBox(10, descLabel, bar, statusLabel);
            progress.getDialogPane().setContent(content);

            // Guard — wallet is saved exactly once regardless of which path fires first
            AtomicBoolean proceeded = new AtomicBoolean(false);
            Consumer<Wallet> proceed = wallet -> {
                if (!proceeded.compareAndSet(false, true)) return;
                try { addWhirlpoolAccounts(wallet); } catch (Exception ex) { log.error("Whirlpool setup failed", ex); }
                // Platform.runLater lets the discovery dialog fully close before the save dialog opens
                Platform.runLater(() -> saveWallet(walletName, wallet));
            };

            // Helper to close dialog and stop the timeout — always called exactly once
            Runnable finish = () -> {
                progress.setOnHiding(null);
                progress.close();
            };

            // 2-minute auto-timeout: close dialog + proceed directly, don't wait for onCancelled
            // (the blocking Electrum socket may not respond to thread interruption)
            PauseTransition timeout = new PauseTransition(Duration.seconds(120));
            timeout.setOnFinished(e -> { finish.run(); svc.cancel(); proceed.accept(wallets.get(0)); });

            svc.setOnSucceeded(e -> { timeout.stop(); finish.run(); proceed.accept(svc.getValue().orElseGet(() -> wallets.get(0))); });
            svc.setOnFailed(e -> { timeout.stop(); finish.run(); log.error("Account discovery failed", e.getSource().getException()); proceed.accept(wallets.get(0)); });
            svc.setOnCancelled(e -> { timeout.stop(); finish.run(); proceed.accept(wallets.get(0)); });

            // Cancel button: same as timeout — close + proceed immediately
            Button cancelBtn = (Button) progress.getDialogPane().lookupButton(cancelType);
            cancelBtn.setOnAction(e -> { e.consume(); finish.run(); svc.cancel(); proceed.accept(wallets.get(0)); });

            svc.start();
            timeout.play();
            progress.show(); // non-blocking — callbacks close it when done

            // Prevent the window X-button from closing the dialog while discovery runs
            ((Stage) progress.getDialogPane().getScene().getWindow()).setOnCloseRequest(Event::consume);
        } else {
            Wallet wallet = wallets.get(0);
            try { addWhirlpoolAccounts(wallet); } catch (Exception ex) { log.error("Whirlpool setup failed", ex); }
            saveWallet(walletName, wallet);
        }
    }

    private void addWhirlpoolAccounts(Wallet wallet) {
        Storage tempStorage = new Storage(Storage.getWalletFile(wallet.getName()));
        WalletForm tempForm = new WalletForm(tempStorage, wallet);
        WhirlpoolServices.prepareWhirlpoolWallet(wallet, tempForm.getWalletId(), tempStorage);
    }

    private void saveWallet(String walletName, Wallet wallet) {
        // Ask for optional password
        Dialog<String> pwDlg = new Dialog<>();
        pwDlg.setTitle("Wallet Password");
        pwDlg.initOwner(owner);
        styleWizardDialog(pwDlg, "NEW / RESTORE WALLET", "Protect your wallet",
                "Add an optional password to encrypt this wallet. Leave blank for none.");

        ButtonType okType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
        pwDlg.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);
        styleWizardButtons(pwDlg.getDialogPane());

        PasswordField pwField = new PasswordField();
        pwField.setPromptText("Leave blank for no password");
        Label pwLabel = new Label("Password (optional):");
        pwLabel.getStyleClass().add("field-label");
        VBox content = new VBox(8, pwLabel, pwField);
        content.setPadding(new Insets(20));
        pwDlg.getDialogPane().setContent(content);
        Platform.runLater(pwField::requestFocus);
        pwDlg.setResultConverter(bt -> bt == okType ? pwField.getText() : null);

        Optional<String> pwResult = pwDlg.showAndWait();
        if (pwResult.isEmpty()) return; // cancelled

        String password = pwResult.get();
        Storage storage = new Storage(Storage.getWalletFile(wallet.getName()));

        if (password.isEmpty()) {
            new Thread(new Task<Void>() {
                @Override
                protected Void call() throws Exception {
                    storage.setEncryptionPubKey(Storage.NO_PASSWORD_KEY);
                    storage.saveWallet(wallet);
                    storage.restorePublicKeysFromSeed(wallet, null);
                    for (Wallet child : wallet.getChildWallets()) {
                        storage.saveWallet(child);
                        storage.restorePublicKeysFromSeed(child, null);
                    }
                    return null;
                }

                @Override
                protected void succeeded() {
                    Platform.runLater(() -> registerWallets(storage, wallet));
                }

                @Override
                protected void failed() {
                    log.error("Error saving wallet", getException());
                    Platform.runLater(() -> showError("Save Error",
                            "Could not save wallet: " + getException().getMessage()));
                }
            }).start();
        } else {
            String walletPath = Storage.getWalletFile(wallet.getName()).getAbsolutePath();
            Storage.KeyDerivationService kds = new Storage.KeyDerivationService(storage, new SecureString(password));
            EventManager.get().post(new StorageEvent(walletPath, TimedEvent.Action.START, "Encrypting wallet…"));

            kds.setOnSucceeded(e -> {
                ECKey encFull = kds.getValue();
                EventManager.get().post(new StorageEvent(walletPath, TimedEvent.Action.END, "Done"));

                new Thread(new Task<Void>() {
                    @Override
                    protected Void call() throws Exception {
                        Key key = null;
                        try {
                            ECKey encPub = ECKey.fromPublicOnly(encFull);
                            key = new Key(encFull.getPrivKeyBytes(), storage.getKeyDeriver().getSalt(),
                                    EncryptionType.Deriver.ARGON2);
                            wallet.encrypt(key);
                            storage.setEncryptionPubKey(encPub);
                            storage.saveWallet(wallet);
                            storage.restorePublicKeysFromSeed(wallet, key);
                            for (Wallet child : wallet.getChildWallets()) {
                                if (!child.isNested()) child.encrypt(key);
                                storage.saveWallet(child);
                                storage.restorePublicKeysFromSeed(child, key);
                            }
                        } catch (IOException | StorageException | MnemonicException ex) {
                            log.error("Error saving encrypted wallet", ex);
                            throw ex;
                        } finally {
                            encFull.clear();
                            if (key != null) key.clear();
                        }
                        return null;
                    }

                    @Override
                    protected void succeeded() {
                        Platform.runLater(() -> registerWallets(storage, wallet));
                    }

                    @Override
                    protected void failed() {
                        log.error("Error saving encrypted wallet", getException());
                        Platform.runLater(() -> showError("Save Error",
                                "Could not save wallet: " + getException().getMessage()));
                    }
                }).start();
            });

            kds.setOnFailed(e -> {
                EventManager.get().post(new StorageEvent(walletPath, TimedEvent.Action.END, "Failed"));
                Platform.runLater(() -> showError("Encryption Error", kds.getException().getMessage()));
            });

            kds.start();
        }
    }

    private void registerWallets(Storage storage, Wallet masterWallet) {
        if (mainController != null) {
            mainController.setPendingSelectFile(storage.getWalletFile());
        }
        AshigaruGui.addWallet(storage, masterWallet);
        for (Wallet child : masterWallet.getChildWallets()) {
            AshigaruGui.addWallet(storage, child);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.initOwner(owner);
        AppServices.addAshigaruStylesheets(alert.getDialogPane().getStylesheets());
        alert.showAndWait();
    }
}
