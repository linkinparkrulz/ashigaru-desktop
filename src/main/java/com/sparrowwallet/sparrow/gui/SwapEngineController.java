package com.sparrowwallet.sparrow.gui;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.swap.SwapEngine;
import com.sparrowwallet.sparrow.swap.SwapEngine.Quote;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Developer preview of the embedded swap engine: load the native library, start it on Ashigaru's Tor
 * and list maker quotes. Read-only on mainnet - the engine build behind it refuses every funding call.
 */
public class SwapEngineController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(SwapEngineController.class);

    //One engine per app run: the native library keeps a single engine and loading it again is pointless
    private static SwapEngine engine;
    private static File libraryFile;

    @FXML private Button chooseLibraryBtn;
    @FXML private Button startBtn;
    @FXML private Button refreshBtn;
    @FXML private Label statusLabel;
    @FXML private TextArea quotesArea;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if(engine != null) {
            startBtn.setDisable(true);
            refreshBtn.setDisable(false);
            statusLabel.setText("Engine running from " + libraryFile.getName() + ". Makers keep being discovered in the background.");
        } else if(libraryFile != null) {
            startBtn.setDisable(false);
            statusLabel.setText("Library: " + libraryFile.getAbsolutePath());
        }
    }

    @FXML
    private void onChooseLibrary() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the swap engine library");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Native library", "*.so", "*.dylib", "*.dll"));
        File chosen = chooser.showOpenDialog(chooseLibraryBtn.getScene().getWindow());
        if(chosen != null) {
            libraryFile = chosen;
            startBtn.setDisable(engine != null);
            statusLabel.setText("Library: " + chosen.getAbsolutePath());
        }
    }

    @FXML
    private void onStart() {
        HostAndPort torProxy = AppServices.getTorProxy();
        if(torProxy == null) {
            statusLabel.setText("Tor is not running. The swap engine only connects through Tor - connect first, then start it.");
            return;
        }

        startBtn.setDisable(true);
        chooseLibraryBtn.setDisable(true);
        statusLabel.setText("Starting the engine through Tor at " + torProxy + "…");
        File library = libraryFile;
        Service<SwapEngine> startService = new Service<>() {
            @Override
            protected Task<SwapEngine> createTask() {
                return new Task<>() {
                    @Override
                    protected SwapEngine call() throws Exception {
                        SwapEngine loaded = SwapEngine.load(library);
                        //Quote discovery only: a throwaway identity per run keeps this preview unlinkable
                        //across runs. Real swaps will derive their seed from the wallet seed instead.
                        byte[] seed = new byte[32];
                        new SecureRandom().nextBytes(seed);
                        File dataDir = new File(new File(Storage.getSparrowHome(), "swaps"), "experimental");
                        loaded.start(dataDir, torProxy, seed);
                        return loaded;
                    }
                };
            }
        };
        startService.setOnSucceeded(event -> {
            engine = startService.getValue();
            refreshBtn.setDisable(false);
            statusLabel.setText("Engine running. Makers are discovered over the next minute or two - refresh to see quotes.");
        });
        startService.setOnFailed(event -> {
            log.error("Could not start the swap engine", event.getSource().getException());
            startBtn.setDisable(false);
            chooseLibraryBtn.setDisable(false);
            statusLabel.setText("Could not start the engine: " + event.getSource().getException().getMessage());
        });
        startService.start();
    }

    @FXML
    private void onRefresh() {
        try {
            List<Quote> quotes = engine.listQuotes();
            quotesArea.setText(format(quotes));
            statusLabel.setText(quotes.size() + " maker" + (quotes.size() == 1 ? "" : "s") + " with quotes so far.");
        } catch(Exception e) {
            log.error("Could not list quotes", e);
            statusLabel.setText("Could not list quotes: " + e.getMessage());
        }
    }

    private static String format(List<Quote> quotes) {
        if(quotes.isEmpty()) {
            return "No quotes yet.";
        }

        StringBuilder text = new StringBuilder();
        for(Quote quote : quotes) {
            text.append(String.format(Locale.ROOT, "%s  %s BTC/XMR  min %s  max %s BTC%n",
                    quote.peerId(), btc(quote.priceSats()), btc(quote.minSats()), btc(quote.maxSats())));
            text.append("    ").append(quote.multiaddr());
            if(quote.version() != null) {
                text.append("  v").append(quote.version());
            }
            text.append(System.lineSeparator());
            text.append("    refund policy: ").append(quote.refundPolicy()).append(System.lineSeparator());
        }
        return text.toString();
    }

    private static String btc(long sats) {
        return String.format(Locale.ROOT, "%.8f", sats / 100_000_000d);
    }
}
