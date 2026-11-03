package com.segfault03.ideadb.sql.settings;

import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily;
import org.junit.jupiter.api.Test;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import java.awt.Color;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** The example each family shows on the settings screen. */
class SqlColorFamilyPreviewTest {
    private static final String TEXT = SqlColorFamilyPreview.sampleText();

    private static Color colorAt(JTextPane pane, String word) {
        return StyleConstants.getForeground(pane.getStyledDocument().getCharacterElement(TEXT.indexOf(word)).getAttributes());
    }

    private static boolean boldAt(JTextPane pane, String word) {
        return StyleConstants.isBold(pane.getStyledDocument().getCharacterElement(TEXT.indexOf(word)).getAttributes());
    }

    @Test void everyFamilyShowsTheSameSqlWithItsOwnColorsForTablesViewsAndAliases() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var tableColors = new HashSet<Color>();
            for (ColorFamily family : ColorFamily.values()) {
                JTextPane pane = SqlColorFamilyPreview.create(family);
                assertEquals(TEXT, pane.getText());
                assertFalse(pane.isEditable());
                assertEquals(Role.TABLE.defaultColor(false, family), colorAt(pane, "orders"), family + " table");
                assertEquals(Role.VIEW.defaultColor(false, family), colorAt(pane, "open_invoices"), family + " view");
                assertEquals(Role.ALIAS.defaultColor(false, family), colorAt(pane, "o.id"), family + " alias");
                assertTrue(boldAt(pane, "orders") && boldAt(pane, "open_invoices"), family + " tables and views are bold");
                assertFalse(boldAt(pane, "o.id"), family + " aliases are plain");
                assertTrue(tableColors.add(colorAt(pane, "orders")), family + " looks like another family");
            }
        });
    }

    @Test void columnsLookTheSameInEveryFamily() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextPane coral = SqlColorFamilyPreview.create(ColorFamily.CORAL);
            for (ColorFamily family : ColorFamily.values()) {
                JTextPane pane = SqlColorFamilyPreview.create(family);
                assertEquals(colorAt(coral, "id,"), colorAt(pane, "id,"), "the primary key");
                assertEquals(colorAt(coral, "customer_id"), colorAt(pane, "customer_id"), "the foreign key");
                assertEquals(colorAt(coral, "name"), colorAt(pane, "name"), "a text column");
            }
            assertEquals(Role.PRIMARY_KEY.defaultColor(false, ColorFamily.CORAL), colorAt(coral, "id,"));
        });
    }

    @Test void keywordsKeepTheSyntaxColorAndNamesAreNotTheTextColor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextPane pane = SqlColorFamilyPreview.create(ColorFamily.CORAL);
            assertNotEquals(pane.getForeground(), colorAt(pane, "SELECT"));
            assertNotEquals(pane.getForeground(), colorAt(pane, "orders"));
        });
    }
}
