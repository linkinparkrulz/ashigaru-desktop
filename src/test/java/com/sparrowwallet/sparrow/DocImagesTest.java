package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The README and the features page show pictures: every one must exist, say what it shows, and be used. */
class DocImagesTest {
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile("<img\\s+src=\"([^\"]+)\"\\s+alt=\"([^\"]*)\"");

    private static String text(String file) throws IOException {
        Path path = Path.of(file);
        assertTrue(Files.isRegularFile(path), "run the tests from the project directory: " + file);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void check(String page, Path base, java.util.Set<String> used) throws IOException {
        String text = text(page);
        int count = 0;
        Matcher markdown = MARKDOWN_IMAGE.matcher(text);
        while(markdown.find()) {
            count++;
            check(page, base, markdown.group(2), markdown.group(1), used);
        }
        Matcher html = HTML_IMAGE.matcher(text);
        while(html.find()) {
            count++;
            check(page, base, html.group(1), html.group(2), used);
        }
        assertTrue(count > 0, page + " has no pictures");
    }

    private static void check(String page, Path base, String link, String alt, java.util.Set<String> used) {
        assertFalse(alt.isBlank(), page + ": " + link + " has no alt text");
        if(link.startsWith("http")) {
            return;
        }
        Path file = base.resolve(link).normalize();
        assertTrue(Files.isRegularFile(file), page + ": " + link + " does not exist");
        used.add(file.getFileName().toString());
    }

    @Test
    void everyPictureExistsHasAltTextAndIsUsed() throws IOException {
        java.util.Set<String> used = new java.util.HashSet<>();
        check("README.md", Path.of("."), used);
        check("docs/FEATURES.md", Path.of("docs"), used);
        try(var files = Files.list(Path.of("docs/images"))) {
            files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".png")).forEach(name ->
                    assertTrue(used.contains(name), "docs/images/" + name + " is not used by the README or FEATURES.md"));
        }
    }
}
