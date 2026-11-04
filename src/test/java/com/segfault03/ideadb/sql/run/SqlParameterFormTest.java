package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlParameterFormTest {
    @BeforeAll static void enableSvgIcons() {
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        com.intellij.openapi.util.IconLoader.activate();
    }

    private static final String SQL = "select * from orders where customer_id = :customer and placed_at >= :since";

    private static SqlParameterForm form(String sql, Map<String, SqlParameterValue> initial) {
        return new SqlParameterForm(sql, SqlParameters.of(sql, SqlDialects.MYSQL, false), DatabaseType.MYSQL, null, initial, "Run");
    }

    private static SqlParameterValue text(String value) { return new SqlParameterValue(SqlParameterValue.Type.TEXT, value); }

    @Test void theSqlKeepsItsTextWithAFieldInPlaceOfEachParameter() {
        var form = form(SQL, Map.of());
        assertEquals(2, form.fieldsForTests().size());
        // Each embedded field stands in the text as one character.
        assertEquals("select * from orders where customer_id =   and placed_at >=  ", form.paneTextForTests());
    }

    @Test void sharedIndentationAndSurroundingBlankLinesAreLeftOut() {
        String sql = "\n        UPDATE ORDERS\n           SET STATUS = 'x'\n\n         WHERE ID = ?\n        ";
        var form = form(sql, Map.of());
        // The field stands in the text as one character.
        assertEquals("UPDATE ORDERS\n   SET STATUS = 'x'\n\n WHERE ID =  ", form.paneTextForTests());
        assertEquals("\n        UPDATE ORDERS\n           SET STATUS = 'x'\n\n         WHERE ID = '5'\n        ",
                form(sql, Map.of("1", text("5"))).renderedSql());
    }

    @Test void sqlWithoutIndentationIsShownAsItIs() {
        assertTrue(SqlParameterForm.hiddenRanges("select 1\nfrom t").isEmpty());
    }

    @Test void renderedSqlFollowsTheFields() {
        var form = form(SQL, Map.of(":customer", text("42"), ":since", text("2026-01-01")));
        assertEquals("select * from orders where customer_id = '42' and placed_at >= '2026-01-01'", form.renderedSql());
        form.fieldsForTests().get(0).setText("7");
        assertEquals("select * from orders where customer_id = '7' and placed_at >= '2026-01-01'", form.renderedSql());
    }

    @Test void aRepeatedNamedParameterSharesOneValue() {
        var form = form("select * from t where a = :x or b = :x", Map.of());
        var fields = form.fieldsForTests();
        assertEquals(2, fields.size());
        fields.get(0).setText("abc");
        assertEquals("abc", fields.get(1).getText());
        assertEquals(1, form.values().size());
    }

    @Test void everyValueIsText() {
        var form = form(SQL, Map.of(":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "42")));
        assertEquals(SqlParameterValue.Type.TEXT, form.values().get(":customer").type());
        assertEquals("42", form.values().get(":customer").text());
    }

    @Test void anEmptyFieldStillSubmits() {
        var submitted = new AtomicInteger();
        var form = form("select * from t where n = ?", Map.of());
        form.onSubmit(submitted::incrementAndGet);
        form.submitForTests();
        assertEquals(1, submitted.get());
    }

    @Test void aFieldHasTheSameWidthEmptyOrFilledUntilTheTextOutgrowsIt() {
        var form = form(SQL, Map.of(":since", text("JP")));
        var fields = form.fieldsForTests();
        assertEquals(fields.get(0).getParent().getPreferredSize().width, fields.get(1).getParent().getPreferredSize().width);
        fields.get(1).setText("x".repeat(60));
        assertTrue(fields.get(1).getParent().getPreferredSize().width > fields.get(0).getParent().getPreferredSize().width);
    }

    @Test void mixedStylesBlockSubmitting() {
        var form = form("select ?, :a", Map.of());
        assertFalse(form.valuesValid());
    }

    @Test void changesAreAnnounced() {
        var form = form(SQL, Map.of());
        var changes = new AtomicInteger();
        form.addChangeListener(changes::incrementAndGet);
        form.fieldsForTests().get(1).setText("x");
        assertTrue(changes.get() > 0);
    }

    @Test void layoutValidityIsTheAwtDefault() {
        // valuesValid() is named so it does not override Component.isValid(), which layout relies on.
        assertFalse(form("select ?", Map.of()).isValid());
    }

    @Test void theFormSitsWithItsSqlOnTheCardsAndStaysInView() {
        var visible = new Rectangle(0, 100, 800, 400);
        var origin = new Point(8, 30);
        assertEquals(new Point(197, 170), SqlParameterPopup.location(new Rectangle(200, 200, 300, 40), origin, 120, visible));
        // At the top of the view the form lifts downwards instead of out of sight.
        assertEquals(new Point(197, 100), SqlParameterPopup.location(new Rectangle(200, 110, 300, 40), origin, 120, visible));
        // At the bottom it moves up to fit.
        assertEquals(new Point(197, 380), SqlParameterPopup.location(new Rectangle(200, 480, 300, 20), origin, 120, visible));
    }
}
