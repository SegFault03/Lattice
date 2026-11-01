package com.segfault03.ideadb.sql.view;

import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

class SqlSwingHighlighterTest {
    private static Color colorAt(JTextPane pane, String text, String word) {
        int at = text.indexOf(word);
        return StyleConstants.getForeground(pane.getStyledDocument().getCharacterElement(at).getAttributes());
    }

    private static JTextPane pane(String text) {
        JTextPane pane = new JTextPane();
        pane.setText(text);
        return pane;
    }

    @Test void tokensGetTheirKindsColorAndPlainNamesKeepTheTextColor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String sql = "select name, 'x', 12, count(*) from t -- note";
            JTextPane pane = pane(sql);
            SqlSwingHighlighter.apply(pane, SqlDialects.MYSQL);
            Color plain = colorAt(pane, sql, "name");
            assertEquals(pane.getForeground(), plain, "a column name stays the text color");
            assertNotEquals(plain, colorAt(pane, sql, "select"), "keyword");
            assertNotEquals(plain, colorAt(pane, sql, "'x'"), "string");
            assertNotEquals(plain, colorAt(pane, sql, "12"), "number");
            assertNotEquals(plain, colorAt(pane, sql, "count"), "function");
            assertNotEquals(plain, colorAt(pane, sql, "-- note"), "comment");
            assertNotEquals(colorAt(pane, sql, "select"), colorAt(pane, sql, "'x'"), "each kind has its own color");
        });
    }

    @Test void theDialectDecidesWhatIsAKeyword() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String sql = "select 1 returning id";
            JTextPane postgres = pane(sql);
            SqlSwingHighlighter.apply(postgres, SqlDialects.POSTGRESQL);
            JTextPane mysql = pane(sql);
            SqlSwingHighlighter.apply(mysql, SqlDialects.MYSQL);
            assertNotEquals(colorAt(postgres, sql, "id"), colorAt(postgres, sql, "returning"));
            assertEquals(colorAt(mysql, sql, "id"), colorAt(mysql, sql, "returning"), "RETURNING is an ordinary name for MySQL");
        });
    }

    @Test void applyingAgainAfterNewTextRecolorsItAndClearRemovesTheColors() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextPane pane = pane("select 1");
            SqlSwingHighlighter.apply(pane, SqlDialects.H2);
            pane.setText("update t set a = 2");
            SqlSwingHighlighter.apply(pane, SqlDialects.H2);
            assertNotEquals(pane.getForeground(), colorAt(pane, "update t set a = 2", "update"));
            SqlSwingHighlighter.clear(pane);
            assertEquals(pane.getForeground(), colorAt(pane, "update t set a = 2", "update"));
        });
    }
}
