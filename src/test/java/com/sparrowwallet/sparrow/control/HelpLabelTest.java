package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The "?" beside a field used to answer only to hovering; clicking it now shows the same help */
class HelpLabelTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        JavaFXTestSupport.ensureStarted();
        //Closing a test's window must not end the toolkit for the tests that follow
        javafx.application.Platform.setImplicitExit(false);
        onFx(() -> org.controlsfx.glyphfont.GlyphFontRegistry.register(new com.sparrowwallet.sparrow.glyphfont.FontAwesome5()));
    }

    private static void onFx(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        JavaFXTestSupport.runAndWait(() -> {
            try {
                action.run();
            } catch(Throwable t) {
                failure.set(t);
            }
        });
        if(failure.get() != null) {
            throw failure.get() instanceof AssertionError error ? error : new AssertionError(failure.get());
        }
    }

    private static MouseEvent click(MouseButton button) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 100, 100, button, 1, false, false, false, false,
                button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY, true, false, false, null);
    }

    /** Shows the label in a window, as a tooltip can only be shown beside something on screen, then runs the check */
    private static void inWindow(HelpLabel label, Runnable check) throws Exception {
        AtomicReference<Stage> stage = new AtomicReference<>();
        onFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(label), 200, 100));
            s.show();
            stage.set(s);
        });
        //The tooltip is attached on the next pulse
        onFx(() -> {});
        try {
            onFx(check);
        } finally {
            onFx(() -> stage.get().close());
        }
    }

    @Test
    void clickingShowsTheHelpAndClickingAgainHidesIt() throws Exception {
        AtomicReference<HelpLabel> holder = new AtomicReference<>();
        onFx(() -> {
            HelpLabel label = new HelpLabel();
            label.setHelpText("All passphrases create valid wallets.");
            holder.set(label);
        });
        HelpLabel label = holder.get();
        inWindow(label, () -> {
            assertNotNull(label.getTooltip(), "the help is attached");
            assertFalse(label.getTooltip().isShowing());
            label.fireEvent(click(MouseButton.PRIMARY));
            assertTrue(label.getTooltip().isShowing(), "a click shows the help");
            label.fireEvent(click(MouseButton.PRIMARY));
            assertFalse(label.getTooltip().isShowing(), "a second click hides it");
        });
    }

    @Test
    void aRightClickDoesNothing() throws Exception {
        AtomicReference<HelpLabel> holder = new AtomicReference<>();
        onFx(() -> {
            HelpLabel label = new HelpLabel();
            label.setHelpText("Some help");
            holder.set(label);
        });
        HelpLabel label = holder.get();
        inWindow(label, () -> {
            label.fireEvent(click(MouseButton.SECONDARY));
            assertFalse(label.getTooltip().isShowing());
        });
    }

    @Test
    void aLabelWithNoHelpShowsNothing() throws Exception {
        AtomicReference<HelpLabel> holder = new AtomicReference<>();
        onFx(() -> holder.set(new HelpLabel()));
        HelpLabel label = holder.get();
        inWindow(label, () -> {
            label.fireEvent(click(MouseButton.PRIMARY));
            assertFalse(label.getTooltip().isShowing());
        });
    }

    @Test
    void theQuestionMarkLooksClickable() throws Exception {
        AtomicReference<HelpLabel> holder = new AtomicReference<>();
        onFx(() -> holder.set(new HelpLabel()));
        assertEquals(javafx.scene.Cursor.HAND, holder.get().getCursor());
    }
}
