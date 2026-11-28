package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Every menu looks like the wallet's 3-dot menu: icons, grouping by lines, and no white frame where popups cannot be transparent */
class MenuLookTest {
    @BeforeAll
    static void startToolkit() throws Exception {
        JavaFXTestSupport.ensureStarted();
        Platform.setImplicitExit(false);
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
            throw new AssertionError(failure.get());
        }
    }

    private static void assertMenuLook(ContextMenu menu) {
        long separators = menu.getItems().stream().filter(item -> item instanceof SeparatorMenuItem).count();
        assertTrue(separators >= 1, "grouped by lines");
        for(MenuItem item : menu.getItems()) {
            if(!(item instanceof SeparatorMenuItem)) {
                assertNotNull(item.getGraphic(), item.getText() + " has an icon");
            }
        }
    }

    @Test
    void aTextFieldsMenuHasIconsGroupsAndOffersWhatTheFieldAllows() throws Exception {
        onFx(() -> {
            TextField field = new TextField("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq");
            ContextMenu menu = TextFieldMenu.create(field);
            assertTrue(TextFieldMenu.isOurs(menu));
            assertMenuLook(menu);
            assertEquals(java.util.Arrays.asList("Undo", "Redo", null, "Cut", "Copy", "Paste", "Delete", null, "Select All"),
                    menu.getItems().stream().map(MenuItem::getText).toList());

            MenuItem[] items = menu.getItems().toArray(new MenuItem[0]);
            TextFieldMenu.update(field, items[0], items[1], (SeparatorMenuItem)items[2], items[3], items[4], items[5], items[6], items[8], false);
            assertTrue(items[3].isDisable() && items[4].isDisable() && items[6].isDisable(), "nothing selected: no cut, copy or delete");
            assertTrue(items[5].isDisable(), "nothing to paste");
            assertFalse(items[8].isDisable());

            field.selectRange(0, 4);
            TextFieldMenu.update(field, items[0], items[1], (SeparatorMenuItem)items[2], items[3], items[4], items[5], items[6], items[8], true);
            assertFalse(items[3].isDisable() || items[4].isDisable() || items[5].isDisable() || items[6].isDisable());

            //A field that cannot be edited only copies and selects
            field.setEditable(false);
            TextFieldMenu.update(field, items[0], items[1], (SeparatorMenuItem)items[2], items[3], items[4], items[5], items[6], items[8], true);
            for(int i : new int[] {0, 1, 2, 3, 5, 6}) {
                assertFalse(items[i].isVisible(), items[i].getText());
            }
            assertTrue(items[4].isVisible() && items[8].isVisible());

            //A password is never copied or cut
            PasswordField password = new PasswordField();
            password.setText("secret");
            password.selectAll();
            TextFieldMenu.update(password, items[0], items[1], (SeparatorMenuItem)items[2], items[3], items[4], items[5], items[6], items[8], true);
            assertTrue(items[3].isDisable() && items[4].isDisable());

            //Nor is a text field marked secret: seed words, a passphrase, dice (AD-42)
            TextField words = new TextField("abandon");
            TextFieldMenu.markSecret(words);
            words.selectAll();
            TextFieldMenu.update(words, items[0], items[1], (SeparatorMenuItem)items[2], items[3], items[4], items[5], items[6], items[8], true);
            assertTrue(items[3].isDisable() && items[4].isDisable());
            assertFalse(items[5].isDisable(), "pasting into it is still allowed");
        });
    }

    @Test
    void theCopyAndCutKeysDoNothingInASecretField() throws Exception {
        assertTrue(TextFieldMenu.isCopyOrCut(javafx.scene.input.KeyCode.C, true, true, false));
        assertTrue(TextFieldMenu.isCopyOrCut(javafx.scene.input.KeyCode.X, true, false, false));
        assertTrue(TextFieldMenu.isCopyOrCut(javafx.scene.input.KeyCode.INSERT, false, true, false));
        assertTrue(TextFieldMenu.isCopyOrCut(javafx.scene.input.KeyCode.DELETE, false, false, true));
        assertTrue(TextFieldMenu.isCopyOrCut(javafx.scene.input.KeyCode.COPY, false, false, false));
        assertFalse(TextFieldMenu.isCopyOrCut(javafx.scene.input.KeyCode.V, true, true, false), "paste is allowed");
        assertFalse(TextFieldMenu.isCopyOrCut(javafx.scene.input.KeyCode.C, false, false, false), "typing c is allowed");

        onFx(() -> {
            TextField secret = new TextField("abandon");
            TextFieldMenu.markSecret(secret);
            TextField plain = new TextField("abandon");
            java.util.List<TextField> reached = new java.util.ArrayList<>();
            for(TextField field : new TextField[] {secret, plain}) {
                field.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> reached.add(field));
                javafx.event.Event.fireEvent(field, JavaFXTestSupport.shortcut(javafx.scene.input.KeyCode.C));
            }
            assertEquals(java.util.List.of(plain), reached, "Ctrl+C is stopped before it reaches the secret field");
            assertTrue(TextFieldMenu.isSecret(secret));
            assertFalse(TextFieldMenu.isSecret(plain));
        });
    }

    @Test
    void everyTextFieldInAWindowGetsItOnRightClickButAFieldWithItsOwnMenuKeepsIt() throws Exception {
        onFx(() -> {
            TextField plain = new TextField("text");
            TextField own = new TextField("text");
            ContextMenu ownMenu = new ContextMenu(new MenuItem("Own"));
            own.setContextMenu(ownMenu);
            TextArea area = new TextArea("text");
            Scene scene = new Scene(new VBox(plain, own, area));
            TextFieldMenu.install(scene);
            for(Control control : new Control[] {plain, own, area}) {
                Event.fireEvent(control, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 1, 1, false, null));
            }
            assertTrue(TextFieldMenu.isOurs(plain.getContextMenu()));
            assertTrue(TextFieldMenu.isOurs(area.getContextMenu()));
            assertSame(ownMenu, own.getContextMenu());
        });
    }

    @Test
    void anActionMenuItemAlwaysHasItsIcon() throws Exception {
        onFx(() -> assertNotNull(Icons.menuItem("Lock wallet", com.sparrowwallet.sparrow.glyphfont.FontAwesome5.Glyph.LOCK, null).getGraphic()));
    }

    @Test
    void aShadowIsTakenOffOnceAndKeepsTheRestOfTheStyle() {
        assertEquals("-fx-effect: null;", AppServices.withoutShadow(null));
        assertEquals("-fx-padding: 2;-fx-effect: null;", AppServices.withoutShadow("-fx-padding: 2"));
        assertEquals("-fx-effect: null;", AppServices.withoutShadow("-fx-effect: null;"));
    }

    @Test
    void whereWindowsCannotBeTransparentAMenuUnderAPanesOwnStylesheetsHasNoFrame() throws Exception {
        AtomicReference<Boolean> transparent = new AtomicReference<>();
        onFx(() -> transparent.set(Platform.isSupported(ConditionalFeature.TRANSPARENT_WINDOW)));
        org.junit.jupiter.api.Assumptions.assumeFalse(transparent.get(), "only where popups cannot be transparent (Xvfb, X11 without a compositor)");

        AtomicReference<Stage> stage = new AtomicReference<>();
        ContextMenu menu = new ContextMenu();
        ComboBox<String> combo = new ComboBox<>();
        onFx(() -> {
            //As Tools and Settings do: ashigaru.css on the page's own root pane as well as on the scene
            VBox root = new VBox(new Label("owner"), combo);
            root.getStylesheets().addAll(AppServices.class.getResource("general.css").toExternalForm(),
                    AppServices.class.getResource("gui/ashigaru.css").toExternalForm(),
                    AppServices.class.getResource("darktheme.css").toExternalForm());
            combo.getItems().addAll("one", "two");
            Scene scene = new Scene(root, 300, 200);
            AppServices.addAshigaruStylesheets(scene.getStylesheets());
            Stage window = new Stage();
            window.setScene(scene);
            window.show();
            stage.set(window);
            menu.getItems().addAll(Icons.menuItem("View seed words…", com.sparrowwallet.sparrow.glyphfont.FontAwesome5.Glyph.EYE, null));
            menu.show(root.getChildren().get(0), Side.BOTTOM, 0, 4);
        });
        onFx(() -> {
            //The window is the menu: no margin left for a shadow, which would be painted white
            assertEquals(menu.getSkin().getNode().getLayoutBounds().getWidth(), menu.getWidth(), 0.5);
            menu.hide();
            combo.show();
        });
        onFx(() -> {
            javafx.stage.Window popup = javafx.stage.Window.getWindows().stream().filter(w -> w instanceof javafx.stage.PopupWindow).findFirst().orElseThrow();
            javafx.scene.Node content = popup.getScene().getRoot().lookup(".list-view");
            assertNotNull(content);
            assertNull(content.getEffect(), "the drop-down has no shadow either");
            combo.hide();
            stage.get().close();
        });
    }
}
