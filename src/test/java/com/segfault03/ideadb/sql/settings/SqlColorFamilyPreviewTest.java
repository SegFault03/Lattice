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

/** The example each family shows on the settings screen, on a light and on a dark editor. */
class SqlColorFamilyPreviewTest {
    private static final String TEXT = SqlColorFamilyPreview.sampleText();

    private static Color colorAt(JTextPane pane, String word) {
        return StyleConstants.getForeground(pane.getStyledDocument().getCharacterElement(TEXT.indexOf(word)).getAttributes());
    }

    private static boolean boldAt(JTextPane pane, String word) {
        return StyleConstants.isBold(pane.getStyledDocument().getCharacterElement(TEXT.indexOf(word)).getAttributes());
    }

    @Test void everyFamilyShowsTheSameSqlWithItsOwnColorsOnBothKindsOfEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean dark : new boolean[] {false, true}) {
                var tableColors = new HashSet<Color>();
                for (ColorFamily family : ColorFamily.values()) {
                    JTextPane pane = SqlColorFamilyPreview.create(family, dark);
                    String where = family + (dark ? " on dark" : " on light");
                    assertEquals(TEXT, pane.getText().replace("\r\n", "\n"), where);
                    assertFalse(pane.isEditable(), where);
                    assertEquals(SqlColorFamilyPreview.background(dark), pane.getBackground(), where);
                    assertEquals(SqlColorFamilyPreview.colorOf(Role.TABLE, family, dark), colorAt(pane, "orders"), where + " table");
                    assertEquals(SqlColorFamilyPreview.colorOf(Role.VIEW, family, dark), colorAt(pane, "open_invoices"), where + " view");
                    assertEquals(SqlColorFamilyPreview.colorOf(Role.ALIAS, family, dark), colorAt(pane, "o.id"), where + " alias");
                    assertTrue(boldAt(pane, "orders") && boldAt(pane, "open_invoices"), where + " tables and views are bold");
                    assertFalse(boldAt(pane, "o.id"), where + " aliases are plain");
                    assertTrue(tableColors.add(colorAt(pane, "orders")), where + " looks like another family");
                }
            }
        });
    }

    @Test void columnsLookTheSameInEveryFamily() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean dark : new boolean[] {false, true}) {
                JTextPane coral = SqlColorFamilyPreview.create(ColorFamily.CORAL, dark);
                for (ColorFamily family : ColorFamily.values()) {
                    JTextPane pane = SqlColorFamilyPreview.create(family, dark);
                    assertEquals(colorAt(coral, "id,"), colorAt(pane, "id,"), "the primary key");
                    assertEquals(colorAt(coral, "customer_id"), colorAt(pane, "customer_id"), "the foreign key");
                    assertEquals(colorAt(coral, "name"), colorAt(pane, "name"), "a text column");
                }
                assertEquals(Role.PRIMARY_KEY.defaultColor(dark, ColorFamily.CORAL), colorAt(coral, "id,"));
            }
        });
    }

    @Test void theNeutralFamilyTakesTheEditorsTextColorAndFadesAliases() {
        for (boolean dark : new boolean[] {false, true}) {
            Color table = SqlColorFamilyPreview.colorOf(Role.TABLE, ColorFamily.NEUTRAL, dark);
            Color alias = SqlColorFamilyPreview.colorOf(Role.ALIAS, ColorFamily.NEUTRAL, dark);
            assertEquals(SqlColorFamilyPreview.colorOf(Role.VIEW, ColorFamily.NEUTRAL, dark), table);
            assertNotEquals(table, alias, "aliases are fainter than names");
            // Faded toward the background: nearer to it than the text is.
            Color background = SqlColorFamilyPreview.background(dark);
            assertTrue(distance(alias, background) < distance(table, background), dark ? "dark" : "light");
        }
    }

    @Test void keywordsKeepTheSyntaxColorAndNamesAreNotTheTextColor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean dark : new boolean[] {false, true}) {
                JTextPane pane = SqlColorFamilyPreview.create(ColorFamily.CORAL, dark);
                assertNotEquals(pane.getForeground(), colorAt(pane, "SELECT"));
                assertNotEquals(pane.getForeground(), colorAt(pane, "orders"));
            }
        });
    }

    private static int distance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen()) + Math.abs(a.getBlue() - b.getBlue());
    }
}
