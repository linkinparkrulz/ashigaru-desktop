package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.AppServices;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code -ag-*} palette is written down four times, because JavaFX CSS has no {@code @import} and the three
 * stylesheets reach different screens: {@code ashigaru.css} is on every Ashigaru screen, {@code darktheme.css} is
 * appended at scene root after {@code general.css}, and {@code #tourPopover} re-declares the tokens because a popup
 * resolves lookups through its anchor's subtree. Each file carries a "keep in sync" comment; this makes that
 * comment enforceable, so a colour changed in one place cannot quietly drift in the other three.
 */
class TokenConsistencyTest {
    private static final String ASHIGARU = "gui/ashigaru.css";
    private static final String GENERAL = "general.css";
    private static final String DARKTHEME = "darktheme.css";

    @Test
    void theThreeRootPalettesAreIdentical() {
        Map<String, String> ashigaru = tokens(ASHIGARU, "*");
        Map<String, String> general = tokens(GENERAL, ".root");
        Map<String, String> darktheme = tokens(DARKTHEME, ".root");

        assertFalse(ashigaru.isEmpty(), "no -ag-* tokens parsed out of " + ASHIGARU);
        assertEquals(ashigaru, general, "general.css has drifted from the canonical palette in " + ASHIGARU);
        assertEquals(ashigaru, darktheme, "darktheme.css has drifted from the canonical palette in " + ASHIGARU);
    }

    @Test
    void theTourPopoverRedeclaresTheSameValues() {
        Map<String, String> canonical = tokens(ASHIGARU, "*");
        Map<String, String> popover = tokens(ASHIGARU, "#tourPopover");

        assertFalse(popover.isEmpty(), "the #tourPopover block no longer re-declares any -ag-* token");
        for (Map.Entry<String, String> entry : popover.entrySet()) {
            assertEquals(canonical.get(entry.getKey()), entry.getValue(),
                    "#tourPopover declares " + entry.getKey() + " differently from the canonical palette");
        }
    }

    /** The {@code -ag-*} declarations in the first block opened by {@code selector}, normalised. */
    private static Map<String, String> tokens(String resource, String selector) {
        String css = read(resource).replaceAll("(?s)/\\*.*?\\*/", "");
        int start = css.indexOf("\n" + selector + " {");
        if (start < 0) {
            start = css.indexOf(selector + " {");
        }
        assertTrue(start >= 0, "no '" + selector + " {' block in " + resource);
        int open = css.indexOf('{', start);
        int close = css.indexOf('}', open);
        assertTrue(close > open, "unterminated '" + selector + "' block in " + resource);

        Map<String, String> tokens = new LinkedHashMap<>();
        for (String line : css.substring(open + 1, close).split("\\R")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            if (!name.startsWith("-ag-")) continue;
            tokens.put(name, line.substring(colon + 1).trim().replaceAll("\\s+", " ").toLowerCase());
        }
        return tokens;
    }

    private static String read(String resource) {
        try (InputStream in = AppServices.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing stylesheet resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }
}
