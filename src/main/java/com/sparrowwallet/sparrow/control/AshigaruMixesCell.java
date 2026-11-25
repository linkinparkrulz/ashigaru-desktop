package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.sparrow.gui.AshigaruWalletController;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import javafx.geometry.Pos;
import javafx.scene.control.TableCell;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

public class AshigaruMixesCell extends TableCell<AshigaruWalletController.UtxoRow, UtxoEntry.MixStatus> {
    private static final Image WHIRLPOOL_GIF =
            new Image("/image/Ashigaru_Whirlpool_Logo_White.png", 24, 24, true, true);

    private final ImageView imageView = new ImageView(WHIRLPOOL_GIF);

    public AshigaruMixesCell() {
        setAlignment(Pos.CENTER);
    }

    @Override
    protected void updateItem(UtxoEntry.MixStatus mixStatus, boolean empty) {
        super.updateItem(mixStatus, empty);

        if(empty || getTableRow() == null || getTableRow().getItem() == null) {
            setText(null);
            setGraphic(null);
            setTooltip(null);
            return;
        }

        AshigaruWalletController.UtxoRow row = getTableRow().getItem();
        UtxoEntry utxoEntry = row.utxoEntry;

        if(mixStatus != null && (mixStatus.getMixProgress() != null || mixStatus.getNextMixUtxo() != null)) {
            setText(null);
            setGraphic(imageView);
            if(mixStatus.getMixProgress() != null && mixStatus.getMixProgress().getMixStep() != null) {
                setTooltip(new Tooltip(mixStatus.getMixProgress().getMixStep().name()));
            } else if(mixStatus.getNextMixUtxo() != null) {
                setTooltip(new Tooltip("Queued for next mix"));
            } else {
                setTooltip(null);
            }
        } else {
            setGraphic(null);
            setTooltip(null);
            if(utxoEntry != null && utxoEntry.getMixStatus() != null) {
                setText(String.valueOf(utxoEntry.getMixStatus().getMixesDone()));
            } else {
                setText("-");
            }
        }
    }
}
