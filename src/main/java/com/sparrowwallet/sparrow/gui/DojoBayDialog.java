package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.net.dojo.DojoBayDirectory;
import com.sparrowwallet.sparrow.net.dojo.DojoBayDirectory.Listing;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Choosing a public Dojo from Dojo Bay, in the steps Sentinel's Dojo Bay edition takes: a privacy note, then the list (each Dojo's
 * name, country, jurisdiction, whether it is online, and who signed it), then a confirmation of the one chosen. Nothing is sent to a
 * Dojo until Connect: the list itself only comes from Dojo Bay, over Tor.
 */
public final class DojoBayDialog {
    static final String EYEBROW = "DOJO BAY";
    static final String ADDRESS_NOT_SIGNED = "This Electrum address is listed by Dojo Bay, not signed by the operator.";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private DojoBayDialog() {}

    /** Runs the steps; the Dojo the user chose to connect to, or empty */
    public static Optional<Listing> show(Window owner, Supplier<Task<DojoBayDirectory.Result>> fetch) {
        if(!privacyNote(owner)) {
            return Optional.empty();
        }
        Listing selected = null;
        while(true) {
            Optional<Listing> chosen = list(owner, fetch, selected);
            if(chosen.isEmpty()) {
                return Optional.empty();
            }
            selected = chosen.get();
            //Don't connect goes back to the list, as it was
            if(confirm(owner, selected)) {
                return chosen;
            }
        }
    }

    private static boolean privacyNote(Window owner) {
        Dialog<ButtonType> dlg = dialog(owner, "Before you browse", "Public Dojos are run by other people.");
        Label body = read("Ashigaru fetches the Dojo Bay list over Tor, from the Dojo Bay onion service. Nothing is sent to any Dojo until you choose one and press Connect.");
        Label risk = read("The Dojo you connect to sees the addresses your wallets look up, as any Electrum server does, though not your IP address. "
                + "Choose an operator you trust, or run your own Dojo.");
        risk.getStyleClass().add("faint");
        VBox content = new VBox(10, body, risk);
        content.setPadding(new Insets(20));
        ButtonType cont = new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        finish(dlg, content, 480, cont, cancel);
        return dlg.showAndWait().filter(answer -> answer == cont).isPresent();
    }

    static final class ListView extends VBox {
        final javafx.scene.control.ListView<Listing> rows = new javafx.scene.control.ListView<>();
        final Label status = new Label();
        final Label why = new Label();

        ListView() {
            super(10);
            rows.setCellFactory(view -> new ListingCell());
            rows.setPrefHeight(340);
            rows.setPlaceholder(read("No Dojos to show yet."));
            rows.getStyleClass().add("dojobay-list");
            status.getStyleClass().addAll("ag-read", "faint");
            status.setWrapText(true);
            why.getStyleClass().addAll("ag-read", "dojobay-why");
            why.setWrapText(true);
            why.setMinHeight(Region.USE_PREF_SIZE);
            why.setVisible(false);
            why.setManaged(false);
            getChildren().addAll(status, rows, why);
            VBox.setVgrow(rows, Priority.ALWAYS);
        }

        void show(DojoBayDirectory.Result result, boolean fresh, Listing select) {
            if(result == null) {
                rows.getItems().clear();
                return;
            }
            if(!result.trusted()) {
                rows.getItems().clear();
                status.setText("The list was not used. " + result.refused().getText() + " Ashigaru only shows a Dojo Bay list signed by its known operator.");
                return;
            }
            rows.getItems().setAll(result.listings());
            long verified = result.listings().stream().filter(Listing::verified).count();
            status.setText(verified + " of " + result.listings().size() + " Dojos verified" + (fresh ? "" : ", from the list read at " + TIME.format(result.fetchedAt()))
                    + ". A dimmed Dojo cannot be chosen.");
            if(select != null) {
                result.listings().stream().filter(l -> l.indexerUrl().equals(select.indexerUrl())).findFirst().ifPresent(l -> rows.getSelectionModel().select(l));
            }
        }
    }

    private static Optional<Listing> list(Window owner, Supplier<Task<DojoBayDirectory.Result>> fetch, Listing select) {
        Dialog<ButtonType> dlg = dialog(owner, "Choose a Dojo", "Community Dojos for " + Network.get().getName() + ", as listed on Dojo Bay.");
        ListView view = new ListView();
        ButtonType review = new ButtonType("Review", ButtonBar.ButtonData.OK_DONE);
        ButtonType refresh = new ButtonType("Refresh list", ButtonBar.ButtonData.LEFT);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        VBox content = new VBox(view);
        content.setPadding(new Insets(16, 20, 12, 20));
        finish(dlg, content, 620, review, refresh, cancel);

        Button reviewBtn = (Button)dlg.getDialogPane().lookupButton(review);
        reviewBtn.setDisable(true);
        view.rows.getSelectionModel().selectedItemProperty().addListener((obs, old, listing) -> {
            reviewBtn.setDisable(listing == null || !listing.verified());
            //A dimmed Dojo says why it cannot be chosen
            boolean unverified = listing != null && !listing.verified();
            view.why.setText(unverified ? listing.displayName() + " cannot be chosen: " + listing.problem().getText() : "");
            view.why.setVisible(unverified);
            view.why.setManaged(unverified);
        });

        //The last list read this session shows at once; a fresh one replaces it when it arrives
        DojoBayDirectory.Result last = DojoBayDirectory.lastResult();
        view.show(last, false, select);
        Button refreshBtn = (Button)dlg.getDialogPane().lookupButton(refresh);
        Runnable load = () -> {
            Task<DojoBayDirectory.Result> task = fetch.get();
            refreshBtn.setDisable(true);
            view.status.setText((view.rows.getItems().isEmpty() ? "" : view.status.getText() + " ") + "Fetching the list over Tor… this can take a minute.");
            task.setOnSucceeded(e -> {
                refreshBtn.setDisable(false);
                Listing current = view.rows.getSelectionModel().getSelectedItem();
                view.show(task.getValue(), true, current != null ? current : select);
            });
            task.setOnFailed(e -> {
                refreshBtn.setDisable(false);
                DojoBayDirectory.Result shown = DojoBayDirectory.lastResult();
                String reason = task.getException() == null ? "" : " (" + task.getException().getMessage() + ")";
                view.status.setText("Dojo Bay could not be reached" + reason + "."
                        + (shown != null && shown.trusted() ? " Showing the list read at " + TIME.format(shown.fetchedAt()) + "." : ""));
            });
            Thread thread = new Thread(task, "dojo-bay-directory");
            thread.setDaemon(true);
            thread.start();
        };
        //Refresh fetches again and keeps the window open
        refreshBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            load.run();
        });
        load.run();

        Optional<ButtonType> answer = dlg.showAndWait();
        if(answer.isPresent() && answer.get() == review) {
            Listing listing = view.rows.getSelectionModel().getSelectedItem();
            return listing != null && listing.verified() ? Optional.of(listing) : Optional.empty();
        }
        return Optional.empty();
    }

    static VBox confirmContent(Listing listing) {
        List<Node> rows = new ArrayList<>();
        rows.add(row("Dojo", listing.displayName()));
        String place = place(listing);
        if(!place.isEmpty()) {
            rows.add(row("Where", place));
        }
        rows.add(row("Network", Network.get().getName() + (listing.online() ? " · online" : " · offline when last checked")));
        rows.add(row("Electrum server", listing.indexerUrl()));
        if(listing.explorerUrl() != null) {
            rows.add(row("Block explorer", listing.explorerUrl()));
        }
        TextField signer = new TextField(listing.signerCode());
        signer.setEditable(false);
        signer.getStyleClass().add("address-field");
        rows.add(labelled("Signed by", signer));
        VBox content = new VBox(10);
        content.getChildren().addAll(rows);
        if(!listing.addressSigned()) {
            Label note = read(ADDRESS_NOT_SIGNED);
            note.setGraphic(Icons.of(FontAwesome5.Glyph.INFO_CIRCLE, 12));
            note.setGraphicTextGap(9);
            note.getStyleClass().add("dojobay-note");
            content.getChildren().add(note);
        }
        content.setPadding(new Insets(20));
        return content;
    }

    private static boolean confirm(Window owner, Listing listing) {
        Dialog<ButtonType> dlg = dialog(owner, "Connect to " + listing.displayName() + "?", "Check these are the details you expect.");
        ButtonType connect = new ButtonType("Connect", ButtonBar.ButtonData.OK_DONE);
        ButtonType dont = new ButtonType("Don't connect", ButtonBar.ButtonData.CANCEL_CLOSE);
        finish(dlg, confirmContent(listing), 560, connect, dont);
        return dlg.showAndWait().filter(answer -> answer == connect).isPresent();
    }

    static String place(Listing listing) {
        List<String> parts = new ArrayList<>();
        if(listing.country() != null) {
            parts.add(listing.country());
        }
        if(listing.jurisdiction() != null && !listing.jurisdiction().isBlank()) {
            parts.add(listing.jurisdiction());
        }
        return String.join(" · ", parts);
    }

    /** One line under a Dojo's name: version, jurisdiction, online or offline */
    static String subtitle(Listing listing) {
        List<String> parts = new ArrayList<>();
        if(listing.version() != null && !listing.version().isBlank()) {
            parts.add("v" + listing.version());
        }
        if(listing.jurisdiction() != null && !listing.jurisdiction().isBlank()) {
            parts.add(listing.jurisdiction());
        }
        parts.add(listing.online() ? "online" : "offline");
        return String.join(" • ", parts);
    }

    private static final class ListingCell extends ListCell<Listing> {
        @Override
        protected void updateItem(Listing listing, boolean empty) {
            super.updateItem(listing, empty);
            getStyleClass().remove("unverified");
            if(empty || listing == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label name = new Label(listing.displayName());
            name.getStyleClass().add("dojobay-name");
            HBox top = new HBox(8, name);
            top.setAlignment(Pos.CENTER_LEFT);
            if(listing.country() != null) {
                Label chip = new Label(listing.country());
                chip.getStyleClass().add("dojobay-chip");
                top.getChildren().add(chip);
            }
            Label sub = new Label(subtitle(listing));
            sub.getStyleClass().addAll("dojobay-sub", listing.online() ? "online" : "offline");
            Label signer = new Label(listing.verified() ? "Signed by " + DojoBayDirectory.shortCode(listing.signerCode()) : "Not verified");
            signer.getStyleClass().add("dojobay-signer");
            VBox box = new VBox(3, top, sub, signer);
            setGraphic(box);
            setText(null);
            if(!listing.verified()) {
                getStyleClass().add("unverified");
            }
        }
    }

    private static Dialog<ButtonType> dialog(Window owner, String title, String subtitle) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Dojo Bay");
        if(owner != null) {
            dlg.initOwner(owner);
        }
        WalletCreationFlow.styleWizardDialog(dlg, EYEBROW, title, subtitle);
        return dlg;
    }

    private static void finish(Dialog<ButtonType> dlg, Node content, double width, ButtonType... buttons) {
        DialogPane pane = dlg.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().addAll(buttons);
        WalletCreationFlow.styleWizardButtons(pane);
        pane.setPrefWidth(width);
        AppServices.moveToActiveWindowScreen(dlg);
    }

    private static Label read(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("ag-read");
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static HBox row(String key, String value) {
        Label v = new Label(value);
        v.getStyleClass().add("dojobay-value");
        v.setWrapText(true);
        v.setMinHeight(Region.USE_PREF_SIZE);
        return labelled(key, v);
    }

    private static HBox labelled(String key, Node value) {
        Label k = new Label(key);
        k.getStyleClass().add("dojobay-key");
        k.setMinWidth(120);
        k.setPrefWidth(120);
        HBox row = new HBox(12, k, value);
        HBox.setHgrow(value, Priority.ALWAYS);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }
}
