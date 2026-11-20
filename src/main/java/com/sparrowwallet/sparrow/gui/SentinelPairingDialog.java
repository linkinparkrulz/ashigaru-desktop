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
    private static final int QR_SIZE = 440;

    private SentinelPairingDialog() {}

    /** A built dialog and the pieces a test needs to reach */
    record Built(Dialog<ButtonType> dialog, Label wordsLabel, ImageView qrView, Button newWordsButton, Button copyWordsButton,
                 Button copyPairingButton, Label includedLabel, Label missingLabel, State state) {}

    /** What the window is showing now: the words, and the text the QR holds */
    static final class State {
        String words;
        String envelope;
    }

    static void show(Window owner, SentinelPairing.Slots slots) {
        build(owner, slots).dialog().showAndWait();
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
        qrView.setFitWidth(QR_SIZE);
        qrView.setFitHeight(QR_SIZE);
        qrView.setPreserveRatio(true);
        qrView.setSmooth(false);
        VBox qrBox = new VBox(qrView);
        qrBox.setAlignment(Pos.CENTER);
        qrBox.setMinSize(QR_SIZE, QR_SIZE);
        qrBox.setMaxSize(QR_SIZE, QR_SIZE);

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
            state.words = SentinelPairing.newWords();
            state.envelope = SentinelPairing.envelope(slots, state.words);
            wordsLabel.setText(state.words);
            qrView.setImage(QrImages.toFx(QrImages.dense(state.envelope, QR_SIZE)));
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
        pane.setPrefWidth(860);

        AppServices.moveToActiveWindowScreen(dlg);
        return new Built(dlg, wordsLabel, qrView, newWords, copyWords, copyPairing, included, missing, state);
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
