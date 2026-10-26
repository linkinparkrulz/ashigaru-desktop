package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.AshigaruTerminal;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.swap.SwapService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogsController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(LogsController.class);

    private static final String LOG_FILE_NAME = "ashigaru.log";

    //Only the tail of the log is shown - installs that predate log rotation can have a very large file,
    //and neither the read nor the TextArea should be unbounded.
    private static final int MAX_LINES = 5000;
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    //Matches the leading date of the logback pattern: %date [%thread] %level %logger.%method(%file:%line) - %msg
    private static final Pattern ENTRY_START = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} \\[[^]]*] (TRACE|DEBUG|INFO|WARN|ERROR)\\b");

    //Values that identify a user or their coins. Ordered longest-match-first so an xpub is not
    //partially consumed by the base58 address pattern.
    private static final List<Redaction> REDACTIONS = List.of(
            new Redaction(Pattern.compile("\\bPM8T[1-9A-HJ-NP-Za-km-z]{100,}"), "[paymentcode]"),
            new Redaction(Pattern.compile("\\b[xyztuvXYZTUV](?:pub|prv)[1-9A-HJ-NP-Za-km-z]{100,}"), "[xpub]"),
            new Redaction(Pattern.compile("\\b(?:bc|tb|bcrt)1[023456789acdefghjklmnpqrstuvwxyz]{20,}\\b"), "[address]"),
            new Redaction(Pattern.compile("\\b[13][1-9A-HJ-NP-Za-km-z]{25,34}\\b"), "[address]"),
            new Redaction(Pattern.compile("\\b[0-9a-fA-F]{64}\\b"), "[txid]"),
            new Redaction(Pattern.compile("\\b[a-z2-7]{16,56}\\.onion\\b"), "[onion]"),
            new Redaction(Pattern.compile("\\bm(?:/\\d+['h]?)+"), "[path]")
    );

    //The swap engine's lines, as SwapService keeps them: HH:mm:ss, two spaces, the level unless INFO, the message
    private static final Pattern ENGINE_LEVEL = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}  (WARN|ERROR)\\b");

    private record Redaction(Pattern pattern, String replacement) {}

    @FXML private HBox sourceBar;
    @FXML private ToggleGroup sourceGroup;
    @FXML private ToggleButton engineToggle;
    @FXML private ToggleGroup levelGroup;
    @FXML private ToggleButton allToggle;
    @FXML private ToggleButton warnToggle;
    @FXML private ToggleButton errorToggle;
    @FXML private TextField searchField;
    @FXML private CheckBox rawCheck;
    @FXML private Label warningLabel;
    @FXML private Label statusLabel;
    @FXML private Button refreshBtn;
    @FXML private TextArea logArea;

    //The unmodified tail as read from disk, or the swap engine's log. Everything shown is derived from this.
    private List<String> logLines = List.of();

    //Follows the swap engine while its log is shown
    private final Consumer<String> engineListener = line -> Platform.runLater(() -> {
        if(isEngine()) {
            List<String> lines = new ArrayList<>(logLines);
            lines.addAll(splitLines(line));
            logLines = lines;
            render();
        }
    });

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        allToggle.setUserData("ALL");
        warnToggle.setUserData("WARN");
        errorToggle.setUserData("ERROR");
        levelGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if(selected == null) {
                //never allow an empty selection
                if(old != null) {
                    old.setSelected(true);
                }
                return;
            }
            render();
        });

        searchField.textProperty().addListener((obs, old, text) -> render());
        rawCheck.selectedProperty().addListener((obs, old, raw) -> {
            warningLabel.setVisible(raw);
            warningLabel.setManaged(raw);
            render();
        });

        //The swap engine's log only exists in builds with swaps, once they have been turned on
        SwapService swaps = SwapService.get();
        boolean engine = swaps.isEnabled() || !swaps.getLog().isEmpty();
        sourceBar.setVisible(engine);
        sourceBar.setManaged(engine);
        sourceGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if(selected == null) {
                if(old != null) {
                    old.setSelected(true);
                }
                return;
            }
            loadLog();
        });
        swaps.addLogListener(engineListener);
        logArea.sceneProperty().addListener((obs, old, scene) -> {
            if(scene == null) {
                swaps.removeLogListener(engineListener);
            }
        });

        loadLog();
    }

    private boolean isEngine() {
        return sourceGroup.getSelectedToggle() == engineToggle;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for(String line : text.split("\\R")) {
            if(!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    @FXML
    private void onRefresh() {
        loadLog();
    }

    @FXML
    private void onCopy() {
        String text = getShareableText();
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        showStatus("Copied " + countLines(logArea.getText()) + " lines to the clipboard.");
    }

    @FXML
    private void onSave() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Log");
        fileChooser.setInitialDirectory(Storage.getSparrowHome());
        fileChooser.setInitialFileName("ashigaru-log-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()) + ".txt");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text Files (*.txt)", "*.txt"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));

        File file = fileChooser.showSaveDialog(refreshBtn.getScene().getWindow());
        if(file == null) {
            return;
        }

        try {
            Files.writeString(file.toPath(), getShareableText(), StandardCharsets.UTF_8);
            showStatus("Saved to " + file.getAbsolutePath());
        } catch(IOException e) {
            log.error("Could not save log to " + file.getAbsolutePath(), e);
            showStatus("Could not save log: " + e.getMessage());
        }
    }

    @FXML
    private void onOpenFolder() {
        File dir = Storage.getSparrowHome();
        try {
            AppServices.get().getApplication().getHostServices().showDocument(dir.toURI().toString());
        } catch(Exception e) {
            log.warn("Could not open log folder", e);
            showStatus("Could not open the folder. It is at " + dir.getAbsolutePath());
        }
    }

    private void loadLog() {
        if(isEngine()) {
            logLines = splitLines(SwapService.get().getLog());
            render();
            showStatus(logLines.isEmpty() ? "The swap engine has not logged anything since Ashigaru started."
                    : "The swap engine's log since Ashigaru started. Routine connection messages are in the application log only.");
            return;
        }

        File logFile = getLogFile();
        if(!logFile.exists()) {
            logLines = List.of();
            logArea.setText("");
            showStatus("No log file yet at " + logFile.getAbsolutePath());
            return;
        }

        refreshBtn.setDisable(true);
        showStatus("Reading " + logFile.getName() + "…");

        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                return tail(logFile, MAX_LINES, MAX_BYTES);
            }
        };

        task.setOnSucceeded(event -> {
            logLines = task.getValue();
            refreshBtn.setDisable(false);
            render();
            showStatus("Showing the last " + logLines.size() + " lines of " + logFile.getAbsolutePath());
        });

        task.setOnFailed(event -> {
            refreshBtn.setDisable(false);
            Throwable e = task.getException();
            //Deliberately at warn and without the file contents - this path writes to the file being read.
            log.warn("Could not read log file", e);
            showStatus("Could not read the log file: " + (e == null ? "unknown error" : e.getMessage()));
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Reads at most maxLines from the end of the file, scanning back over at most maxBytes, so that a
     * multi-gigabyte log left over from before rotation cannot exhaust memory.
     */
    static List<String> tail(File file, int maxLines, long maxBytes) throws IOException {
        try(RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            raf.seek(start);

            if(start > 0) {
                //The seek probably landed mid-line, so discard the partial line.
                raf.readLine();
            }

            //read() may return short, which would silently drop the newest lines, so ask for exactly
            //what is left between the file pointer and the length observed above.
            long remaining = length - raf.getFilePointer();
            byte[] buffer = new byte[(int)Math.min(remaining, maxBytes)];
            if(buffer.length == 0) {
                return List.of();
            }
            raf.readFully(buffer);

            String content = new String(buffer, StandardCharsets.UTF_8);
            //Drop the trailing newline first, so it does not count as an extra (empty) line against maxLines
            if(content.endsWith("\n")) {
                content = content.substring(0, content.length() - 1);
            }
            if(content.isEmpty()) {
                return List.of();
            }

            Deque<String> lines = new ArrayDeque<>();
            for(String line : content.split("\n", -1)) {
                lines.addLast(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                if(lines.size() > maxLines) {
                    lines.removeFirst();
                }
            }

            return new ArrayList<>(lines);
        }
    }

    private void render() {
        List<String> lines = isEngine() ? filterEngineByLevel(logLines, selectedLevel()) : filterByLevel(logLines, selectedLevel());
        lines = filterBySearch(lines, searchField.getText());

        String text = String.join("\n", lines);
        if(!rawCheck.isSelected()) {
            text = redact(text, walletNames());
        }

        logArea.setText(text);
        //Show the newest lines, but keep each line's start in view. positionCaret() would scroll right
        //to the caret's column on a long last line. Deferred so the new text has been laid out and the
        //maximum scroll value is known.
        Platform.runLater(() -> {
            logArea.setScrollTop(Double.MAX_VALUE);
            logArea.setScrollLeft(0);
        });
    }

    private String selectedLevel() {
        Toggle selected = levelGroup.getSelectedToggle();
        return selected == null ? "ALL" : (String)selected.getUserData();
    }

    /**
     * Keeps entries at or above the given level. Lines that do not start a new entry (stack trace
     * frames, wrapped messages) inherit the level of the entry above them so traces stay whole.
     */
    static List<String> filterByLevel(List<String> lines, String minLevel) {
        if("ALL".equals(minLevel)) {
            return lines;
        }

        List<String> filtered = new ArrayList<>();
        boolean keeping = false;
        for(String line : lines) {
            Matcher matcher = ENTRY_START.matcher(line);
            if(matcher.find()) {
                keeping = atOrAbove(matcher.group(1), minLevel);
            }
            if(keeping) {
                filtered.add(line);
            }
        }

        return filtered;
    }

    /** The swap engine's lines are one per entry, with the level after the time */
    static List<String> filterEngineByLevel(List<String> lines, String minLevel) {
        if("ALL".equals(minLevel)) {
            return lines;
        }
        List<String> filtered = new ArrayList<>();
        for(String line : lines) {
            Matcher matcher = ENGINE_LEVEL.matcher(line);
            if(matcher.find() && atOrAbove(matcher.group(1), minLevel)) {
                filtered.add(line);
            }
        }
        return filtered;
    }

    private static boolean atOrAbove(String level, String minLevel) {
        if("ERROR".equals(minLevel)) {
            return "ERROR".equals(level);
        }
        return "ERROR".equals(level) || "WARN".equals(level);
    }

    /**
     * Keeps matching entries together with their continuation lines, so searching for a message still
     * shows the stack trace that followed it.
     */
    static List<String> filterBySearch(List<String> lines, String search) {
        if(search == null || search.isBlank()) {
            return lines;
        }

        String needle = search.toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        boolean keeping = false;
        for(String line : lines) {
            boolean entryStart = ENTRY_START.matcher(line).find();
            boolean matches = line.toLowerCase(Locale.ROOT).contains(needle);
            if(entryStart) {
                keeping = matches;
            }
            if(keeping || matches) {
                filtered.add(line);
            }
        }

        return filtered;
    }

    /**
     * Replaces anything that would identify a user or link them to coins, so the displayed log can be
     * handed to a developer as-is.
     */
    static String redact(String text, Collection<String> walletNames) {
        String redacted = text;
        for(Redaction redaction : REDACTIONS) {
            redacted = redaction.pattern().matcher(redacted).replaceAll(redaction.replacement());
        }

        //Wallet names are user-chosen and so cannot be matched by shape.
        for(String name : walletNames) {
            if(name != null && !name.isBlank()) {
                redacted = Pattern.compile(Pattern.quote(name)).matcher(redacted).replaceAll("[wallet]");
            }
        }

        return redacted;
    }

    private static Collection<String> walletNames() {
        Set<String> names = new TreeSet<>(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        try {
            for(Wallet wallet : AppServices.get().getOpenWallets().keySet()) {
                if(wallet.getName() != null) {
                    names.add(wallet.getName());
                }
                if(wallet.getMasterName() != null) {
                    names.add(wallet.getMasterName());
                }
            }
        } catch(Exception e) {
            log.debug("Could not determine open wallet names for redaction", e);
        }

        return names;
    }

    /**
     * Returns exactly what is on screen, prefixed with the environment details a bug report needs.
     * No wallet data and no server address is included.
     */
    private String getShareableText() {
        return buildDiagnostics() + "\n" + logArea.getText();
    }

    private String buildDiagnostics() {
        StringBuilder builder = new StringBuilder();
        builder.append("--- Ashigaru diagnostics ---\n");
        builder.append("Version:  ").append(AshigaruTerminal.APP_VERSION).append(AshigaruTerminal.APP_VERSION_SUFFIX).append("\n");
        builder.append("OS:       ").append(System.getProperty("os.name")).append(" ").append(System.getProperty("os.version")).append(" (").append(System.getProperty("os.arch")).append(")\n");
        builder.append("Java:     ").append(System.getProperty("java.version")).append("\n");
        builder.append("Network:  ").append(Network.get().getName()).append("\n");
        builder.append("Proxy:    ").append(AppServices.getProxy() != null ? "enabled" : "disabled").append("\n");
        builder.append("Redacted: ").append(rawCheck.isSelected() ? "no - this log contains addresses and wallet identifiers" : "yes").append("\n");
        builder.append("----------------------------\n");
        return builder.toString();
    }

    private static File getLogFile() {
        return new File(Storage.getSparrowHome(), LOG_FILE_NAME);
    }

    private static int countLines(String text) {
        return text == null || text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    private void showStatus(String message) {
        statusLabel.setText(message);
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }
}
