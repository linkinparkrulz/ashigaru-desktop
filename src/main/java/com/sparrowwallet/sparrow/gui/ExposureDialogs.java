package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.ServerType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.util.Optional;

/**
 * The two places the app says out loud who can see what: a plain list of what each outside party
 * learns, and a stop before any link that would open in the system browser, outside Tor.
 */
public final class ExposureDialogs {
    private ExposureDialogs() {}

    /** Opened from the connection pill. Fixed copy, apart from the server's name and kind. */
    public static void showWhoLearnsWhat() {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle("Who learns what");
        dlg.initOwner(AshigaruGui.get().getMainStage());
        WalletCreationFlow.styleWizardDialog(dlg, "CONNECTION", "Who learns what", "With the server and settings you have now.");

        boolean ownServer = Config.get().getServerType() != ServerType.PUBLIC_ELECTRUM_SERVER;
        String serverName = Config.get().hasServer() ? Config.get().getServerDisplayName() : "none selected";

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.getStyleClass().add("exposure-grid");
        grid.setPadding(new Insets(10, 20, 6, 20));
        ColumnConstraints who = new ColumnConstraints(150);
        ColumnConstraints what = new ColumnConstraints();
        what.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(who, what);

        int row = 0;
        row = addParty(grid, row, "Your internet provider", null,
                "That you use Tor", "Who you connect to");
        row = addParty(grid, row, ownServer ? "Your own server" : "Server", serverName,
                "Your addresses and balances", ownServer ? "Nothing leaves your own node" : "Your IP address");
        row = addParty(grid, row, "Whirlpool coordinator", null,
                "The coins you register for a mix", "Which mixed output is yours, or your IP address");
        row = addParty(grid, row, "GitHub", "update check",
                Config.get().isUpdateCheckEnabled() ? "That someone asked for the latest release" : "Nothing. The update check is off",
                Config.get().isUpdateCheckEnabled() ? "Your IP address" : null);
        if(com.sparrowwallet.sparrow.swap.SwapService.get().isEnabled()) {
            //Swaps add three parties. All of them are reached over the app's own Tor.
            row = addParty(grid, row, "Swap makers", "Badbank to XMR",
                    "The offer you ask about, the Bitcoin you lock and the Monero address you receive to",
                    "Your IP address, or which wallet the coin came from");
            row = addParty(grid, row, "Monero nodes", "the ones ticked in Settings",
                    "That someone is checking whether a maker's XMR is locked", "Your IP address");
            row = addParty(grid, row, "Price source", "CoinGecko, or Kraken",
                    "That someone asked the BTC to XMR price", "Your IP address");
        }
        addParty(grid, row, "Explorer links", "open in your browser",
                "The transaction you open and your IP address. Your browser does not use Ashigaru's Tor connection.", null);

        DialogPane pane = dlg.getDialogPane();
        pane.setContent(grid);
        pane.getButtonTypes().add(ButtonType.CLOSE);
        WalletCreationFlow.styleWizardButtons(pane);
        pane.setPrefWidth(540);
        AppServices.moveToActiveWindowScreen(dlg);
        dlg.setResultConverter(bt -> null);
        dlg.showAndWait();
    }

    private static int addParty(GridPane grid, int row, String who, String detail, String sees, String not) {
        Label name = new Label(who);
        name.getStyleClass().add("exposure-who");
        name.setWrapText(true);
        VBox left = new VBox(2, name);
        if(detail != null) {
            Label sub = new Label(detail);
            sub.getStyleClass().add("exposure-detail");
            sub.setWrapText(true);
            left.getChildren().add(sub);
        }
        VBox right = new VBox(4, fact("SEES", sees, "sees"));
        if(not != null) {
            right.getChildren().add(fact("NOT", not, "not"));
        }
        left.getStyleClass().add("exposure-cell");
        right.getStyleClass().add("exposure-cell");
        if(row > 0) {
            left.getStyleClass().add("divided");
            right.getStyleClass().add("divided");
        }
        grid.addRow(row, left, right);
        return row + 1;
    }

    private static HBox fact(String key, String text, String styleClass) {
        Label k = new Label(key);
        k.getStyleClass().addAll("exposure-key", styleClass);
        k.setMinWidth(40);
        Label v = new Label(text);
        v.getStyleClass().add("ag-read");
        v.setWrapText(true);
        v.setMaxWidth(300);
        v.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox box = new HBox(8, k, v);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /**
     * Every link that leaves the app goes through here. Explorer and exposure links used to open the
     * system browser directly, which sends the transaction id from the user's own IP address. The
     * safe answer, copying the link, is the default button.
     */
    public static void openOutsideTor(String url) {
        String host = hostOf(url);
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Leaving Tor");
        dlg.initOwner(AshigaruGui.get().getMainStage());
        WalletCreationFlow.styleWizardDialog(dlg, "LEAVING TOR", "Open in your browser?",
                host + " will see this request and your IP address.");

        TextField link = new TextField(url);
        link.setEditable(false);
        link.getStyleClass().add("address-field");
        Label warn = new Label("Your browser does not use Ashigaru's Tor connection. Copy the link and open it in Tor Browser instead.");
        warn.getStyleClass().addAll("ag-read", "exposure-warning");
        warn.setWrapText(true);
        warn.setMaxWidth(400);
        warn.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        warn.setGraphic(Icons.of(FontAwesome5.Glyph.EXCLAMATION_TRIANGLE, 13));
        warn.setGraphicTextGap(10);
        VBox content = new VBox(12, link, warn);
        content.setPadding(new Insets(20));

        ButtonType copy = new ButtonType("Copy link", ButtonBar.ButtonData.OK_DONE);
        ButtonType open = new ButtonType("Open anyway", ButtonBar.ButtonData.OTHER);
        DialogPane pane = dlg.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().addAll(open, copy);
        WalletCreationFlow.styleWizardButtons(pane);
        pane.setPrefWidth(480);
        AppServices.moveToActiveWindowScreen(dlg);

        Optional<ButtonType> answer = dlg.showAndWait();
        if(answer.isPresent() && answer.get() == copy) {
            ClipboardContent clip = new ClipboardContent();
            clip.putString(url);
            Clipboard.getSystemClipboard().setContent(clip);
        } else if(answer.isPresent() && answer.get() == open) {
            AppServices.get().getApplication().getHostServices().showDocument(url);
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "This site" : host;
        } catch(IllegalArgumentException e) {
            return "This site";
        }
    }
}
