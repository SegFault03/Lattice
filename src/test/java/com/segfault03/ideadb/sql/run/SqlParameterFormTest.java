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
        return form(sql, Map.of(), initial);
    }

    private static SqlParameterForm form(String sql, Map<String, SqlParameterHints.Hint> hints, Map<String, SqlParameterValue> initial) {
        return new SqlParameterForm(sql, SqlParameters.of(sql, SqlDialects.MYSQL, false), DatabaseType.MYSQL, null, hints, initial, "Run");
    }

    @Test void theSqlKeepsItsTextWithAFieldInPlaceOfEachParameter() {
        var form = form(SQL, Map.of());
        assertEquals(2, form.fieldsForTests().size());
        // Each embedded field stands in the text as one character.
        assertEquals("select * from orders where customer_id =   and placed_at >=  ", form.paneTextForTests());
    }

    @Test void renderedSqlFollowsTheFields() {
        var form = form(SQL, Map.of(
                ":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "42"),
                ":since", new SqlParameterValue(SqlParameterValue.Type.DATE, "2026-01-01")));
        assertEquals("select * from orders where customer_id = 42 and placed_at >= DATE '2026-01-01'", form.renderedSql());
        form.fieldsForTests().get(0).setText("7");
        assertEquals("select * from orders where customer_id = 7 and placed_at >= DATE '2026-01-01'", form.renderedSql());
    }

    @Test void aRepeatedNamedParameterSharesOneValue() {
        var form = form("select * from t where a = :x or b = :x", Map.of());
        var fields = form.fieldsForTests();
        assertEquals(2, fields.size());
        fields.get(0).setText("abc");
        assertEquals("abc", fields.get(1).getText());
        assertEquals(1, form.values().size());
    }

    @Test void theHintSetsTheStartingTypeAndTheRememberedValueWins() {
        var hint = new SqlParameterHints.Hint("orders.customer_id", "INT", SqlParameterValue.Type.NUMBER, null, false);
        var fresh = form(SQL, Map.of(":customer", hint), Map.of());
        assertEquals(SqlParameterValue.Type.NUMBER, fresh.values().get(":customer").type());
        assertTrue(fresh.chipForTests(":customer").startsWith("number"));
        var remembered = form(SQL, Map.of(":customer", hint), Map.of(":customer", new SqlParameterValue(SqlParameterValue.Type.TEXT, "x")));
        assertEquals(SqlParameterValue.Type.TEXT, remembered.values().get(":customer").type());
    }

    @Test void theStatusLineDescribesTheFirstValueThatDoesNotFit() {
        var form = form(SQL, Map.of(
                ":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "abc"),
                ":since", new SqlParameterValue(SqlParameterValue.Type.DATE, "nope")));
        assertEquals(":customer: Enter a number such as 12 or 3.5", form.statusText());
        assertNull(form.renderedSql());
        form.fieldsForTests().get(0).setText("1");
        assertEquals(":since: Enter a date as yyyy-MM-dd", form.statusText());
    }

    @Test void anEmptyTypedValueOnlyCountsOnceSubmitIsTried() {
        var submitted = new AtomicInteger();
        var form = form("select * from t where n = ?", Map.of("1", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "")));
        form.onSubmit(submitted::incrementAndGet);
        assertFalse(form.valuesValid());
        assertFalse(form.statusText().contains("Enter a value"));
        form.submitForTests();
        assertEquals(0, submitted.get());
        assertEquals("?1: Enter a value", form.statusText());
        form.fieldsForTests().get(0).setText("3");
        form.submitForTests();
        assertEquals(1, submitted.get());
    }

    @Test void nullDisablesTheFieldAndWritesNull() {
        var form = form(SQL, Map.of(":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "abc"),
                ":since", new SqlParameterValue(SqlParameterValue.Type.TEXT, "x")));
        form.typeForTests(":customer", SqlParameterValue.Type.NULL);
        assertFalse(form.fieldsForTests().get(0).isEnabled());
        assertTrue(form.valuesValid());
        assertTrue(String.valueOf(form.renderedSql()).contains("customer_id = NULL"), form.renderedSql());
        form.typeForTests(":customer", SqlParameterValue.Type.NUMBER);
        assertTrue(form.fieldsForTests().get(0).isEnabled());
    }

    @Test void mixedStylesBlockSubmitting() {
        var form = form("select ?, :a", Map.of());
        assertFalse(form.valuesValid());
        assertEquals(SqlParameters.MIXED_STYLES, form.statusText());
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
