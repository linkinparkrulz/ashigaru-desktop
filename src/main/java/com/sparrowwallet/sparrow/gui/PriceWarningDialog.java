package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.swap.SwapCopy;
import com.sparrowwallet.sparrow.swap.SwapTermsCheck;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Optional;

/**
 * The confirmation before a swap goes on at a worse price than the offer the user chose: the maker's final terms give
 * more than {@link SwapTermsCheck#WARN_PERCENT}% less XMR. This is eigenwallet 5.0.4's warning before accepting an offer,
 * and, as there, a price that is worse by more than {@link SwapTermsCheck#STOP_PERCENT}% never gets here: it is refused.
 *
 * <p>Accepting only lets the user go on to the final approval, where the lock is still a separate decision. Rejecting is
 * the default button, so Enter is the safe answer, and the dialog closes as a rejection when the engine's approval
 * window runs out, since that deadline does not wait for this.
 */
public final class PriceWarningDialog {
    private PriceWarningDialog() {}

    /** A built dialog and the pieces a caller or a test needs to reach */
    record Built(Dialog<ButtonType> dialog, ButtonType accept, ButtonType reject, Label countdownLabel) {}

    /**
     * Shows the warning and waits. True only when the user chose to accept the worse price; Reject, closing the window,
     * and running out of time all answer false.
     *
     * @param seconds how long is left of the engine's approval window
     */
    public static boolean show(Window owner, SwapTermsCheck.Result result, int seconds) {
        if(seconds < 1) {
            return false;
        }
        Built built = build(owner, result, seconds);
        AppServices.moveToActiveWindowScreen(built.dialog());
        Optional<ButtonType> answer = built.dialog().showAndWait();
        return answer.isPresent() && answer.get() == built.accept();
    }

    static Built build(Window owner, SwapTermsCheck.Result result, int seconds) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Price changed");
        if(owner != null) {
            dlg.initOwner(owner);
        }
        WalletCreationFlow.styleWizardDialog(dlg, "PRICE CHANGED", SwapCopy.priceWarningTitle(), "Check the new price before you go on.");

        String offered = SwapCopy.xmrText(result.offeredPiconero());
        String expected = SwapCopy.xmrText(result.expectedPiconero());

        Label warning = new Label(SwapCopy.priceWarning(result.percentLess(), offered, expected));
        warning.getStyleClass().addAll("ag-read", "exposure-warning");
        warning.setWrapText(true);
        warning.setMinHeight(Region.USE_PREF_SIZE);
        warning.setMaxWidth(470);
        warning.setGraphic(Icons.of(FontAwesome5.Glyph.EXCLAMATION_TRIANGLE, 13));
        warning.setGraphicTextGap(10);

        VBox figures = new VBox(0,
                figure("The offer you chose", expected + " XMR", false),
                figure("The maker's final terms", offered + " XMR", false),
                figure("Difference", SwapCopy.percentText(result.percentLess()) + " less XMR", true));
        figures.getStyleClass().add("price-figures");

        Label countdown = new Label();
        countdown.getStyleClass().addAll("ag-read", "faint");
        countdown.setWrapText(true);
        countdown.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(14, warning, figures, countdown);
        content.setPadding(new Insets(20));

        ButtonType accept = new ButtonType("Accept new price", ButtonBar.ButtonData.OTHER);
        //Cancel-type, so the window's X and Esc also reject
        ButtonType reject = new ButtonType("Reject swap", ButtonBar.ButtonData.CANCEL_CLOSE);
        DialogPane pane = dlg.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().addAll(accept, reject);
        WalletCreationFlow.styleWizardButtons(pane);
        pane.setPrefWidth(520);

        //The safe answer is the one Enter gives, and the one drawn as the main button
        Button rejectButton = (Button)pane.lookupButton(reject);
        rejectButton.setDefaultButton(true);
        rejectButton.getStyleClass().remove("action-btn");
        rejectButton.getStyleClass().add("primary-btn");
        ((Button)pane.lookupButton(accept)).setDefaultButton(false);

        int[] left = {seconds};
        Runnable show = () -> countdown.setText("This closes as a rejection in " + left[0] + (left[0] == 1 ? " second." : " seconds."));
        show.run();
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            left[0]--;
            if(left[0] <= 0) {
                dlg.setResult(reject);
                dlg.close();
            } else {
                show.run();
            }
        }));
        clock.setCycleCount(seconds);
        dlg.setOnShown(event -> clock.play());
        dlg.setOnHidden(event -> clock.stop());

        return new Built(dlg, accept, reject, countdown);
    }

    private static HBox figure(String name, String value, boolean emphasised) {
        Label key = new Label(name);
        key.getStyleClass().add("price-figure-name");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label amount = new Label(value);
        amount.getStyleClass().add(emphasised ? "price-figure-worse" : "price-figure-value");
        HBox row = new HBox(10, key, grow, amount);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("price-figure");
        return row;
    }
}
