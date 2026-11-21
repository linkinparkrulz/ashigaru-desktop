package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Tools > Pair with Sentinel: the wallet's public keys as a QR for Sentinel to scan, and the three one-time words that
 * open it. What is in the pairing and how it is made is {@link SentinelPairing}; this shows it. Nothing here is saved,
 * logged or sent anywhere, and the words are new each time the window opens.
 */
final class SentinelPairingDialog {
    /** The QR is drawn about this wide, in whole pixels per module; the box holds a little more so that the layout does not move */
    private static final int QR_SIZE = 440;
    private static final int QR_BOX = 480;

    private SentinelPairingDialog() {}

    /** A built dialog and the pieces a test needs to reach */
    record Built(Dialog<ButtonType> dialog, Label wordsLabel, ImageView qrView, Button newWordsButton, Button copyWordsButton,
                 Button copyPairingButton, Label includedLabel, Label missingLabel, State state) {}

    /** New words, what they make, and its picture */
    record Pairing(String words, String envelope, java.awt.image.BufferedImage qr) {}

    /**
     * Makes a pairing whose QR a plain reader gets back out. The words are random, so a pairing whose picture does not read
     * (see {@link QrImages#reads}) is simply replaced by one with other words; it takes a few tries at most.
     */
    static Pairing readablePairing(SentinelPairing.Slots slots, int size) {
        Pairing last = null;
        for(int attempt = 0; attempt < 30; attempt++) {
            String words = SentinelPairing.newWords();
            String envelope = SentinelPairing.envelope(slots, words);
            last = new Pairing(words, envelope, QrImages.dense(envelope, size));
            if(last.qr() != null && QrImages.reads(last.qr(), envelope)) {
                return last;
            }
        }
        return last;
    }

    /** What the window is showing now: the words, and the text the QR holds */
    static final class State {
        String words;
        String envelope;
    }

    static void show(Window owner, SentinelPairing.Slots slots) {
        Dialog<ButtonType> dialog = build(owner, slots).dialog();
        AppServices.moveToActiveWindowScreen(dialog);
        dialog.showAndWait();
    }

    static Built build(Window owner, SentinelPairing.Slots slots) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Pair with Sentinel");
        if(owner != null) {
            dlg.initOwner(owner);
        }
        WalletCreationFlow.styleWizardDialog(dlg, "PAIR WITH SENTINEL", "Scan this in Sentinel",
                "Sentinel then watches this wallet's accounts without being able to spend from them.");

        State state = new State();
        ImageView qrView = new ImageView();
        qrView.setPreserveRatio(true);
        qrView.setSmooth(false);
        VBox qrBox = new VBox(qrView);
        qrBox.setAlignment(Pos.CENTER);
        qrBox.setMinSize(QR_BOX, QR_BOX);
        qrBox.setMaxSize(QR_BOX, QR_BOX);

        Label wordsHeading = new Label("Type these three words in Sentinel");
        wordsHeading.getStyleClass().addAll("ag-read", "faint");
        Label wordsLabel = new Label();
        wordsLabel.getStyleClass().add("sentinel-words");
        wordsLabel.setWrapText(true);
        wordsLabel.setMinHeight(Region.USE_PREF_SIZE);

        Button copyWords = new Button("Copy words");
        copyWords.getStyleClass().add("secondary-btn");
        Button newWords = new Button("New words");
        newWords.getStyleClass().add("secondary-btn");
        Button copyPairing = new Button("Copy pairing text");
        copyPairing.getStyleClass().add("secondary-btn");
        HBox wordButtons = new HBox(8, copyWords, newWords);
        wordButtons.setAlignment(Pos.CENTER_LEFT);

        Label steps = new Label("1. In Sentinel, choose to add a wallet from an Ashigaru pairing.\n"
                + "2. Scan the picture on the left, or paste the pairing text.\n"
                + "3. Type the three words above when Sentinel asks for the password.");
        steps.getStyleClass().add("ag-read");
        steps.setWrapText(true);
        steps.setMinHeight(Region.USE_PREF_SIZE);

        Label included = new Label();
        included.getStyleClass().addAll("ag-read", "faint");
        included.setWrapText(true);
        included.setMinHeight(Region.USE_PREF_SIZE);
        Label missing = new Label();
        missing.getStyleClass().addAll("ag-read", "faint");
        missing.setWrapText(true);
        missing.setMinHeight(Region.USE_PREF_SIZE);

        Label privacy = new Label("The picture holds this wallet's public keys, never a seed or private key. Anyone who has both the picture "
                + "and the three words can see every address and balance of these accounts, so show it only to your own phone. "
                + "The words are new each time this window opens and are not saved.");
        privacy.getStyleClass().addAll("ag-read", "exposure-warning");
        privacy.setWrapText(true);
        privacy.setMinHeight(Region.USE_PREF_SIZE);
        privacy.setGraphic(Icons.of(FontAwesome5.Glyph.EXCLAMATION_TRIANGLE, 13));
        privacy.setGraphicTextGap(10);

        VBox right = new VBox(12, wordsHeading, wordsLabel, wordButtons, steps, copyPairing, included, missing, privacy);
        right.setMinWidth(300);
        right.setPrefWidth(340);
        HBox.setHgrow(right, Priority.ALWAYS);

        HBox content = new HBox(22, qrBox, right);
        content.setPadding(new Insets(20));

        Runnable refresh = () -> {
            Pairing pairing = readablePairing(slots, QR_SIZE);
            state.words = pairing.words();
            state.envelope = pairing.envelope();
            wordsLabel.setText(state.words);
            javafx.scene.image.Image qr = QrImages.toFx(pairing.qr());
            qrView.setImage(qr);
            if(qr != null) {
                //Shown at its own size: no pixel is stretched
                qrView.setFitWidth(qr.getWidth());
                qrView.setFitHeight(qr.getHeight());
            }
        };
        refresh.run();

        copyWords.setOnAction(event -> copy(state.words));
        copyPairing.setOnAction(event -> copy(state.envelope));
        newWords.setOnAction(event -> refresh.run());

        included.setText("In this pairing: " + String.join(", ", slots.includedNames()) + ".");
        boolean any = !slots.missingNames().isEmpty();
        missing.setText(any ? "Not in it: " + String.join(", ", slots.missingNames()) + ". "
                + "Sentinel will say it skipped them; the rest comes across." : "");
        missing.setVisible(any);
        missing.setManaged(any);

        DialogPane pane = dlg.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        WalletCreationFlow.styleWizardButtons(pane);
        pane.setPrefWidth(900);

        return new Built(dlg, wordsLabel, qrView, newWords, copyWords, copyPairing, included, missing, state);
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
