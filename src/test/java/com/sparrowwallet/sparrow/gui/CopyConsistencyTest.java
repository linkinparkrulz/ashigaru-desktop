package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The copy rules in {@code docs/COPY.md}, enforced. Each one is a literal that should never reach a screen again, so a
 * failure here is a string to change rather than a rule to argue with. Only the files that hold Ashigaru's own wording
 * are read: the inherited Sparrow screens and the hardware-wallet import descriptions are not this app's voice.
 */
class CopyConsistencyTest {
    private static final Path GUI_JAVA = Path.of("src/main/java/com/sparrowwallet/sparrow/gui");
    private static final Path GUI_FXML = Path.of("src/main/resources/com/sparrowwallet/sparrow/gui");

    /** A literal in Java, and the same text in an FXML attribute a person reads. */
    private static final Pattern JAVA_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FXML_TEXT = Pattern.compile("(?:text|promptText|title)=\"([^\"]*)\"");

    private record Ban(String literal, String rule) {}

    private static final Ban ELLIPSIS = new Ban("...", "Use the single character …, never three full stops");
    private static final Ban PLEASE = new Ban("Please ", "Do not say please; say what to do");
    private static final Ban FAILED_TO = new Ban("Failed to", "Say \"Could not <verb>\" instead");
    private static final Ban ERROR_VERBING = new Ban("Error fetching", "Name what failed, not \"Error <verb>ing\"");
    private static final Ban NEW_RESTORE = new Ban("New / restore", "Capitalise it the same way everywhere: New / Restore…");
    private static final Ban BAD_BANK = new Ban("Bad Bank", "One word: Badbank");
    private static final Ban START_MIX = new Ban("Start Mix\"", "The button says Start Mixing");

    private static final List<Ban> BANS = List.of(ELLIPSIS, PLEASE, FAILED_TO, ERROR_VERBING, NEW_RESTORE, BAD_BANK, START_MIX);

    @Test
    void noBannedCopyInTheGuiSources() throws IOException {
        List<String> offences = offences(BANS, false);
        assertTrue(offences.isEmpty(), offences.isEmpty() ? "" : "banned copy:\n  " + String.join("\n  ", offences));
    }

    /**
     * Sentinel reads its own names out of the pairing payload and shows them on its side, so the list in
     * {@link SentinelPairing} is interop, not this app's wording. That file is the one place "Bad Bank" stays, and
     * renaming it there would make the two apps disagree about the same key.
     */
    @Test
    void sentinelPairingKeepsSentinelsOwnNameForBadbank() throws IOException {
        List<String> offences = offences(List.of(BAD_BANK), true);
        assertTrue(offences.stream().allMatch(o -> o.startsWith("SentinelPairing.java")),
                "Badbank is one word:\n  " + String.join("\n  ", offences));
        assertTrue(offences.stream().anyMatch(o -> o.startsWith("SentinelPairing.java")),
                "SentinelPairing no longer names the key \"Bad Bank\"; Sentinel shows that name, so check the pairing still lines up");
    }

    /** Every banned literal found in the GUI sources, as "File.java: "the literal" — the rule". */
    private static List<String> offences(List<Ban> bans, boolean sentinelPairingOnly) throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : files(GUI_JAVA, ".java")) {
            boolean sentinel = file.getFileName().toString().equals("SentinelPairing.java");
            if (sentinelPairingOnly != sentinel) continue;
            scan(offences, file, JAVA_LITERAL, bans);
        }
        if (!sentinelPairingOnly) {
            for (Path file : files(GUI_FXML, ".fxml")) {
                scan(offences, file, FXML_TEXT, bans);
            }
        }
        return offences;
    }

    private static void scan(List<String> offences, Path file, Pattern pattern, List<Ban> bans) throws IOException {
        Matcher m = pattern.matcher(withoutComments(Files.readString(file, StandardCharsets.UTF_8)));
        while (m.find()) {
            String literal = m.group(1);
            for (Ban ban : bans) {
                if (literal.contains(ban.literal())) {
                    offences.add(file.getFileName() + ": \"" + literal + "\" — " + ban.rule());
                }
            }
        }
    }

    /**
     * Drops comment lines, so a rule quoted in Javadoc is not read as copy. Only lines that are wholly a comment are
     * removed; a trailing comment after code is left alone, because the literal it follows is what matters there.
     */
    private static String withoutComments(String source) {
        StringBuilder kept = new StringBuilder();
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) continue;
            kept.append(line).append('\n');
        }
        return kept.toString();
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        assertTrue(Files.isDirectory(root), "missing source directory " + root.toAbsolutePath());
        Set<Path> found = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(suffix)).sorted().forEach(found::add);
        }
        assertFalse(found.isEmpty(), "no " + suffix + " files under " + root);
        return new ArrayList<>(found);
    }
}
