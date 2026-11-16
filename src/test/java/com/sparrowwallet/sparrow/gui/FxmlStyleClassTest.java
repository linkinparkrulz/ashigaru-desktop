package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FXML separates the entries of a styleClass list with commas. A value with a space in it, like
 * styleClass="primary-btn mix-btn", adds ONE class named "primary-btn mix-btn" that no stylesheet matches, so the
 * element silently loses its styling. This reads every FXML file, with no JavaFX involved, and fails on any entry
 * with whitespace in it.
 */
class FxmlStyleClassTest {
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern STYLE_CLASS = Pattern.compile("styleClass\\s*=\\s*\"([^\"]*)\"");

    @Test
    void everyStyleClassListIsCommaSeparated() throws IOException {
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for(Path file : fxmlFiles()) {
            String text = COMMENT.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll("");
            Matcher matcher = STYLE_CLASS.matcher(text);
            while(matcher.find()) {
                checked++;
                for(String entry : matcher.group(1).split(",", -1)) {
                    if(entry.trim().matches(".*\\s.*")) {
                        bad.add(file.getFileName() + ": styleClass=\"" + matcher.group(1).replaceAll("\\s+", " ") + "\" (use commas between classes)");
                        break;
                    }
                }
            }
        }
        assertTrue(checked > 100, "found only " + checked + " styleClass attributes, so the files were not read");
        assertTrue(bad.isEmpty(), "Space-separated style classes add one class that nothing matches:\n" + String.join("\n", bad));
    }

    @Test
    void theCheckCatchesTheMistake() {
        //The pattern itself, on the two forms
        assertTrue("primary-btn mix-btn".trim().matches(".*\\s.*"));
        assertFalse("mix-btn".trim().matches(".*\\s.*"));
        assertFalse(" mix-btn".trim().matches(".*\\s.*"));
    }

    private static List<Path> fxmlFiles() throws IOException {
        Path root = Path.of("src/main/resources");
        assertTrue(Files.isDirectory(root), "run the tests from the project directory");
        try(Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".fxml")).toList();
        }
    }
}
