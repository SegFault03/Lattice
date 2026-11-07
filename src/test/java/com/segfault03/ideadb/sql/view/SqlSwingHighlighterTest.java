package com.segfault03.ideadb.sql.view;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

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

    private static ExplorerMetadataSnapshot.Table table(String name, ColumnMetadata... columns) {
        var metadata = new TableMetadata(null, "PUBLIC", name, "TABLE", null);
        metadata.setColumns(List.of(columns));
        return new ExplorerMetadataSnapshot.Table(metadata, List.of(columns), true);
    }

    private static final SqlSchema SCHEMA = SqlSchema.of(new ConnectionConfig(DatabaseType.MYSQL, "panes"), new ExplorerMetadataSnapshot(List.of(
            new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(
                    table("customers", new ColumnMetadata("id", "INTEGER", Types.INTEGER, 10, 0, false, true, false, null),
                            new ColumnMetadata("name", "VARCHAR", Types.VARCHAR, 40, 0, true, false, false, null)),
                    table("orders", new ColumnMetadata("id", "INTEGER", Types.INTEGER, 10, 0, false, true, false, null),
                            new ColumnMetadata("total", "DECIMAL", Types.DECIMAL, 10, 2, false, false, false, null),
                            new ColumnMetadata("customer_id", "INTEGER", Types.INTEGER, 10, 0, false, false, false, null)
                                    .withReference(new ColumnReference("PUBLIC", "customers", "id")))), true)), List.of(), 1), "PUBLIC", 1);

    private static final String QUERY = "select o.id, o.total, o.customer_id, c.name from orders o join customers c on c.id = o.customer_id";

    /** Whether the color is one the role has for a light or a dark scheme: which of the two is the IDE theme's business. */
    private static boolean isRoleColor(Color color, Role role) {
        return color.getRGB() == role.defaultColor(false).getRGB() || color.getRGB() == role.defaultColor(true).getRGB();
    }

    private static boolean boldAt(JTextPane pane, String text, String word) {
        return StyleConstants.isBold(pane.getStyledDocument().getCharacterElement(text.indexOf(word)).getAttributes());
    }

    @Test void namesTheSchemaKnowsGetTheirRoleColorsOnTopOfTheSyntaxColors() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextPane pane = pane(QUERY);
            SqlSwingHighlighter.apply(pane, SqlDialects.MYSQL, SCHEMA);
            assertTrue(isRoleColor(colorAt(pane, QUERY, "orders"), Role.TABLE));
            assertTrue(isRoleColor(colorAt(pane, QUERY, "customers"), Role.TABLE));
            assertTrue(isRoleColor(colorAt(pane, QUERY, "id"), Role.PRIMARY_KEY));
            assertTrue(isRoleColor(colorAt(pane, QUERY, "total"), Role.NUMERIC));
            assertTrue(isRoleColor(colorAt(pane, QUERY, "customer_id"), Role.FOREIGN_KEY));
            assertTrue(isRoleColor(colorAt(pane, QUERY, "name"), Role.TEXT));
            assertTrue(isRoleColor(colorAt(pane, QUERY, "o.id"), Role.ALIAS), "the alias in o.id");
            for (Role role : Role.values()) assertFalse(isRoleColor(colorAt(pane, QUERY, "select"), role), "keywords keep their syntax color");
        });
    }

    @Test void theRolesStyleFollowsTheRoleNotTheTokenBeforeIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextPane pane = pane(QUERY);
            SqlSwingHighlighter.apply(pane, SqlDialects.MYSQL, SCHEMA);
            assertTrue(boldAt(pane, QUERY, "orders"), "tables are bold");
            assertTrue(boldAt(pane, QUERY, "id"), "primary keys are bold");
            assertFalse(boldAt(pane, QUERY, "o.id"), "aliases are plain");
            assertFalse(boldAt(pane, QUERY, "total"), "other columns are plain");
        });
    }

    @Test void withoutASchemaTheNamesKeepTheTextColor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextPane pane = pane(QUERY);
            SqlSwingHighlighter.apply(pane, SqlDialects.MYSQL, null);
            assertEquals(pane.getForeground(), colorAt(pane, QUERY, "orders"));
            assertEquals(pane.getForeground(), colorAt(pane, QUERY, "total"));
        });
    }
}
