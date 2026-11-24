package com.sparrowwallet.sparrow.gui;

import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;

/**
 * The right-click menu of every text field and text area, in the look of every other menu in the app (the wallet's 3-dot menu): an icon
 * on each item, and the items grouped by lines. JavaFX's own menu for text has neither. It is installed once per scene
 * ({@link #install(Scene)}) and given to a field the first time that field is right-clicked, so new screens and dialogs get it without
 * any code of their own. A field that already has a menu of its own keeps it.
 */
public final class TextFieldMenu {
    /** Marks a scene that has the filter, and a field whose menu is this one */
    private static final String MARK = TextFieldMenu.class.getName();

    private TextFieldMenu() {}

    /** Gives every text field and area in the scene this menu, when it is first right-clicked */
    public static void install(Scene scene) {
        if(scene == null || scene.getProperties().containsKey(MARK)) {
            return;
        }
        scene.getProperties().put(MARK, Boolean.TRUE);
        scene.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            TextInputControl control = textControl(event.getTarget() instanceof Node node ? node : null);
            if(control != null && control.getContextMenu() == null) {
                control.setContextMenu(create(control));
            }
        });
    }

    /** The text field or area a right-click landed in: the target itself, or the field whose inner text it is */
    static TextInputControl textControl(Node target) {
        for(Node node = target; node != null; node = node.getParent()) {
            if(node instanceof TextInputControl control) {
                return control;
            }
        }
        return null;
    }

    /** The menu for one field. What can be done is worked out each time it opens, from the field as it is then. */
    public static ContextMenu create(TextInputControl control) {
        MenuItem undo = Icons.menuItem("Undo", FontAwesome5.Glyph.UNDO, event -> control.undo());
        MenuItem redo = Icons.menuItem("Redo", FontAwesome5.Glyph.REDO, event -> control.redo());
        MenuItem cut = Icons.menuItem("Cut", FontAwesome5.Glyph.CUT, event -> control.cut());
        MenuItem copy = Icons.menuItem("Copy", FontAwesome5.Glyph.COPY, event -> control.copy());
        MenuItem paste = Icons.menuItem("Paste", FontAwesome5.Glyph.PASTE, event -> control.paste());
        MenuItem delete = Icons.menuItem("Delete", FontAwesome5.Glyph.TRASH_ALT, event -> control.deleteText(control.getSelection()));
        MenuItem selectAll = Icons.menuItem("Select All", FontAwesome5.Glyph.OBJECT_GROUP, event -> control.selectAll());
        SeparatorMenuItem afterHistory = new SeparatorMenuItem();

        ContextMenu menu = new ContextMenu(undo, redo, afterHistory, cut, copy, paste, delete, new SeparatorMenuItem(), selectAll);
        menu.getProperties().put(MARK, Boolean.TRUE);
        menu.setOnShowing(event -> update(control, undo, redo, afterHistory, cut, copy, paste, delete, selectAll,
                Clipboard.getSystemClipboard().hasString()));
        return menu;
    }

    /**
     * What the menu offers for the field as it is now, as JavaFX's own text menu does: a field that cannot be edited only copies and
     * selects, and a password field never copies or cuts its text.
     */
    static void update(TextInputControl control, MenuItem undo, MenuItem redo, SeparatorMenuItem afterHistory, MenuItem cut, MenuItem copy,
                       MenuItem paste, MenuItem delete, MenuItem selectAll, boolean clipboardHasText) {
        boolean editable = control.isEditable() && !control.isDisabled();
        boolean selection = control.getSelection().getLength() > 0;
        boolean secret = control instanceof PasswordField;
        for(MenuItem item : new MenuItem[] {undo, redo, afterHistory, cut, paste, delete}) {
            item.setVisible(editable);
        }
        undo.setDisable(!control.isUndoable());
        redo.setDisable(!control.isRedoable());
        cut.setDisable(!selection || secret);
        copy.setDisable(!selection || secret);
        paste.setDisable(!clipboardHasText);
        delete.setDisable(!selection);
        selectAll.setDisable(control.getLength() == 0 || control.getSelection().getLength() == control.getLength());
    }

    /** Whether a field's menu is this one */
    public static boolean isOurs(ContextMenu menu) {
        return menu != null && menu.getProperties().containsKey(MARK);
    }
}
