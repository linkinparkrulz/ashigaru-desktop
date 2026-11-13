package com.sparrowwallet.sparrow.gui;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.ServerType;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * The path a request takes out of this machine: you, Tor, the server, the Bitcoin network. For a
 * wallet that sends everything over Tor this is the interface, so it is drawn rather than left as a
 * line of status text. Each hop shows its own state and the one fact worth knowing about it.
 *
 * <p>Self-contained: it reads its state from AppServices and Config, and listens for connection
 * events only while it is on screen.
 */
public class NetworkPath extends HBox {
    private static final PseudoClass OK = PseudoClass.getPseudoClass("ok");
    private static final PseudoClass BUSY = PseudoClass.getPseudoClass("busy");

    private final Hop you = new Hop("You", FontAwesome5.Glyph.LAPTOP);
    private final Hop tor = new Hop("Tor", FontAwesome5.Glyph.BULLSEYE);
    private final Hop server = new Hop("Server", FontAwesome5.Glyph.SERVER);
    private final Hop bitcoin = new Hop("Bitcoin", FontAwesome5.Glyph.GLOBE);
    private boolean torFailed;

    public NetworkPath() {
        getStyleClass().add("network-path");
        for(Hop hop : new Hop[] {you, tor, server, bitcoin}) {
            HBox.setHgrow(hop, Priority.ALWAYS);
            hop.setMaxWidth(Double.MAX_VALUE);
            hop.setPrefWidth(100);
            getChildren().add(hop);
        }
        you.set(true, false, "Ready", "Receive-only on this machine");
        sceneProperty().addListener((obs, old, scene) -> {
            if(scene != null && old == null) {
                EventManager.get().register(this);
                refresh();
            } else if(scene == null && old != null) {
                EventManager.get().unregister(this);
            }
        });
        refresh();
    }

    private void refresh() {
        Config config = Config.get();
        boolean connected = AppServices.isConnected();
        boolean connecting = AppServices.isConnecting();
        boolean torRunning = AppServices.isTorRunning();

        if(config.isUseProxy()) {
            tor.set(true, false, "Your own proxy", "The bundled Tor is not used");
        } else if(torRunning) {
            torFailed = false;
            tor.set(true, false, "Connected", "Bundled Tor, every connection");
        } else if(torFailed) {
            tor.set(false, false, "Failed to start", "Use Restart Tor below");
        } else {
            tor.set(false, connecting, connecting ? "Starting…" : "Not running", "Bundled Tor, every connection");
        }

        boolean ownServer = config.getServerType() != ServerType.PUBLIC_ELECTRUM_SERVER;
        server.title.setText(ownServer ? "Your server" : "Public server");
        String name = config.hasServer() ? config.getServerDisplayName() : "None selected";
        String learns = ownServer ? "Your addresses stay with you" : "Sees your addresses, not your IP";
        server.set(connected, connecting && !connected, connected ? name : connecting ? "Connecting…" : name, learns);

        Integer height = AppServices.getCurrentBlockHeight();
        boolean synced = connected && height != null && height > 0;
        bitcoin.set(synced, false, synced ? String.format(Locale.ROOT, "Block %,d", height) : "No chain tip yet",
                com.sparrowwallet.drongo.Network.get().getName().toUpperCase(Locale.ROOT));
    }

    @Subscribe public void connection(ConnectionEvent event) { Platform.runLater(this::refresh); }
    @Subscribe public void disconnection(DisconnectionEvent event) { Platform.runLater(this::refresh); }
    @Subscribe public void connectionStart(ConnectionStartEvent event) { Platform.runLater(this::refresh); }
    @Subscribe public void connectionFailed(ConnectionFailedEvent event) { Platform.runLater(this::refresh); }
    @Subscribe public void newBlock(NewBlockEvent event) { Platform.runLater(this::refresh); }
    @Subscribe public void torReady(TorReadyStatusEvent event) { torFailed = false; Platform.runLater(this::refresh); }
    @Subscribe public void torFailed(TorFailedStatusEvent event) { torFailed = true; Platform.runLater(this::refresh); }

    /** One hop: an icon in a ring, its name, its state, and one fact about it. */
    private static final class Hop extends VBox {
        private final Label title;
        private final Label state = new Label();
        private final Label fact = new Label();
        private final StackPane ring;

        Hop(String name, FontAwesome5.Glyph glyph) {
            super(7);
            getStyleClass().add("path-hop");
            ring = new StackPane(Icons.of(glyph, 14));
            ring.getStyleClass().add("path-ring");
            title = new Label(name);
            title.getStyleClass().add("path-title");
            HBox head = new HBox(10, ring, title);
            head.setAlignment(Pos.CENTER_LEFT);
            state.getStyleClass().add("path-state");
            fact.getStyleClass().add("path-fact");
            fact.setWrapText(true);
            fact.setMinHeight(Region.USE_PREF_SIZE);
            getChildren().addAll(head, state, fact);
        }

        void set(boolean ok, boolean busy, String stateText, String factText) {
            state.setText(stateText);
            fact.setText(factText);
            pseudoClassStateChanged(OK, ok);
            pseudoClassStateChanged(BUSY, busy);
        }
    }
}
