package com.sparrowwallet.sparrow.control;

import com.samourai.whirlpool.client.mix.listener.MixFailReason;
import com.samourai.whirlpool.client.mix.listener.MixStep;
import com.samourai.whirlpool.client.wallet.beans.MixProgress;
import com.samourai.whirlpool.protocol.beans.Utxo;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.gui.AshigaruWalletController.UtxoRow;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import com.sparrowwallet.sparrow.whirlpool.Whirlpool;
import com.sparrowwallet.sparrow.whirlpool.WhirlpoolException;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.util.Duration;
import org.controlsfx.glyphfont.Glyph;

import java.util.Locale;

public class AshigaruMixStatusCell extends TableCell<UtxoRow, UtxoEntry.MixStatus> {
    private static final int ERROR_DISPLAY_MILLIS = 5 * 60 * 1000;

    public AshigaruMixStatusCell() {
        super();
        setAlignment(Pos.CENTER_RIGHT);
        setContentDisplay(ContentDisplay.LEFT);
        setGraphicTextGap(8);
        getStyleClass().add("mixstatus-cell");
    }

    @Override
    protected void updateItem(UtxoEntry.MixStatus mixStatus, boolean empty) {
        super.updateItem(mixStatus, empty);

        if(empty || mixStatus == null) {
            setText(null);
            setGraphic(null);
            setContextMenu(null);
            setTooltip(null);
            return;
        }

        setText(Integer.toString(mixStatus.getMixesDone()));

        if(mixStatus.getNextMixUtxo() != null) {
            setContextMenu(null);
            setMixSuccess(mixStatus.getNextMixUtxo());
        } else if(mixStatus.getMixFailReason() != null) {
            setContextMenu(new MixStatusContextMenu(mixStatus.getUtxoEntry(),
                    mixStatus.getMixProgress() != null && mixStatus.getMixProgress().getMixStep() != MixStep.FAIL));
            setMixFail(mixStatus.getMixFailReason(), mixStatus.getMixError(), mixStatus.getMixErrorTimestamp());
        } else if(mixStatus.getMixProgress() != null) {
            setContextMenu(new MixStatusContextMenu(mixStatus.getUtxoEntry(),
                    mixStatus.getMixProgress().getMixStep() != MixStep.FAIL));
            setMixProgress(mixStatus.getUtxoEntry(), mixStatus.getMixProgress());
        } else {
            setContextMenu(new MixStatusContextMenu(mixStatus.getUtxoEntry(), false));
            setGraphic(null);
            setTooltip(null);
        }
    }

    private void setMixSuccess(Utxo nextMixUtxo) {
        ProgressIndicator indicator = getProgressIndicator();
        indicator.setProgress(-1);
        setGraphic(indicator);
        Tooltip tt = new Tooltip("Waiting for broadcast of "
                + nextMixUtxo.getHash().substring(0, 8) + "…:" + nextMixUtxo.getIndex());
        setTooltip(tt);
    }

    private void setMixFail(MixFailReason reason, String mixError, Long errorTimestamp) {
        if(reason == MixFailReason.CANCEL) {
            setGraphic(null);
            setTooltip(null);
            return;
        }

        long elapsed = errorTimestamp == null ? 0L : System.currentTimeMillis() - errorTimestamp;
        if(elapsed >= ERROR_DISPLAY_MILLIS) {
            return;
        }

        Glyph failGlyph = MixStatusCell.getFailGlyph();
        setGraphic(failGlyph);
        Tooltip tt = new Tooltip(reason.getMessage()
                + (mixError == null ? "" : ": " + mixError)
                + "\nMix failures are generally caused by peers disconnecting during a mix."
                + "\nMake sure your internet connection is stable and the computer is configured to prevent sleeping.");
        setTooltip(tt);

        Duration fadeDuration = Duration.millis(ERROR_DISPLAY_MILLIS - elapsed);
        double fadeFrom = 1.0 - ((double) elapsed / ERROR_DISPLAY_MILLIS);
        Timeline timeline = AnimationUtil.getSlowFadeOut(failGlyph, fadeDuration, fadeFrom, 10);
        timeline.setOnFinished(e -> setTooltip(null));
        timeline.play();
    }

    private void setMixProgress(UtxoEntry utxoEntry, MixProgress mixProgress) {
        if(mixProgress.getMixStep() == MixStep.FAIL) {
            setGraphic(null);
            setTooltip(null);
            return;
        }

        ProgressIndicator indicator = getProgressIndicator();
        double pct = mixProgress.getMixStep().getProgressPercent();
        indicator.setProgress(pct >= 100 ? -1 : pct / 100.0);
        setGraphic(indicator);

        String raw = mixProgress.getMixStep().getMessage();
        String status = raw.substring(0, 1).toUpperCase(Locale.ROOT) + raw.substring(1);
        Tooltip tt = new Tooltip(status);
        setTooltip(tt);

        if(mixProgress.getMixStep() == MixStep.REGISTERED_INPUT) {
            tt.setOnShowing(event -> {
                Whirlpool whirlpool = AppServices.getWhirlpoolServices().getWhirlpool(utxoEntry.getWallet());
                Whirlpool.RegisteredInputsService svc =
                        new Whirlpool.RegisteredInputsService(whirlpool, mixProgress.getPoolId());
                svc.setOnSucceeded(e -> {
                    if(svc.getValue() != null) {
                        tt.setText(status + " (1 of " + svc.getValue() + ")");
                    }
                });
                svc.start();
            });
        }
    }

    private ProgressIndicator getProgressIndicator() {
        if(getGraphic() instanceof ProgressIndicator pi) {
            return pi;
        }
        return new ProgressBar();
    }

    private static class MixStatusContextMenu extends ContextMenu {
        MixStatusContextMenu(UtxoEntry utxoEntry, boolean isMixing) {
            Whirlpool pool = AppServices.getWhirlpoolServices().getWhirlpool(utxoEntry.getWallet());

            if(isMixing) {
                MenuItem stop = new MenuItem("Stop Mixing");
                stop.setGraphic(MixStatusCell.getStopGlyph());
                if(pool != null) stop.disableProperty().bind(pool.mixingProperty().not());
                stop.setOnAction(e -> {
                    hide();
                    Whirlpool w = AppServices.getWhirlpoolServices().getWhirlpool(utxoEntry.getWallet());
                    if(w != null) {
                        try { w.mixStop(utxoEntry.getHashIndex()); }
                        catch(WhirlpoolException ex) {
                            AppServices.showErrorDialog("Error stopping mixing UTXO", ex.getMessage());
                        }
                    }
                });
                getItems().add(stop);
            } else {
                MenuItem mixNow = new MenuItem("Mix Now");
                mixNow.setGraphic(MixStatusCell.getMixGlyph());
                if(pool != null) mixNow.disableProperty().bind(pool.mixingProperty().not());
                mixNow.setOnAction(e -> {
                    hide();
                    Whirlpool w = AppServices.getWhirlpoolServices().getWhirlpool(utxoEntry.getWallet());
                    if(w != null) {
                        try { w.mix(utxoEntry.getHashIndex()); }
                        catch(WhirlpoolException ex) {
                            AppServices.showErrorDialog("Error mixing UTXO", ex.getMessage());
                        }
                    }
                });
                getItems().add(mixNow);
            }
        }
    }
}
