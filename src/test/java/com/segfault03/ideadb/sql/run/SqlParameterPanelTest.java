package com.segfault03.ideadb.sql.run;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlParameterPanelTest {
    @BeforeAll static void enableSvgIcons() {
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        com.intellij.openapi.util.IconLoader.activate();
    }

    private static List<JBTextField> fields(Container container) {
        List<JBTextField> fields = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JBTextField field) fields.add(field);
            else if (child instanceof Container nested) fields.addAll(fields(nested));
        }
        return fields;
    }

    private static final String SQL = "select * from orders where customer_id = :customer and placed_at >= :since";

    private static SqlParameterPanel panel(String sql, Map<String, SqlParameterValue> initial) {
        return new SqlParameterPanel(sql, SqlParameters.of(sql, SqlDialects.MYSQL, false), DatabaseType.MYSQL, initial);
    }

    private static <T extends Component> List<T> all(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container nested) found.addAll(all(nested, type));
        }
        return found;
    }

    @Test void previewShowsRenderedSql() {
        var panel = panel(SQL, Map.of(
                ":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "42"),
                ":since", new SqlParameterValue(SqlParameterValue.Type.DATE, "2026-01-01")));
        assertEquals("select * from orders where customer_id = 42 and placed_at >= DATE '2026-01-01'", panel.previewText());
        fields(panel).get(0).setText("7");
        assertEquals("select * from orders where customer_id = 7 and placed_at >= DATE '2026-01-01'", panel.previewText());
    }

    @Test void previewShowsFirstValidationError() {
        var panel = panel(SQL, Map.of(
                ":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "abc"),
                ":since", new SqlParameterValue(SqlParameterValue.Type.DATE, "nope")));
        assertEquals(":customer: Enter a number such as 12 or 3.5", panel.previewText());
        fields(panel).get(0).setText("1");
        assertEquals(":since: Enter a date as yyyy-MM-dd", panel.previewText());
    }

    @Test void nullCheckboxDisablesValueField() {
        var panel = panel(SQL, Map.of(":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "abc")));
        JBCheckBox isNull = all(panel, JBCheckBox.class).get(0);
        isNull.doClick();
        assertFalse(fields(panel).get(0).isEnabled());
        assertEquals(SqlParameterValue.Type.NULL, panel.values().get(":customer").type());
        assertTrue(panel.previewText().contains("customer_id = NULL"), panel.previewText());
        isNull.doClick();
        assertTrue(fields(panel).get(0).isEnabled());
    }

    @Test void previewShowsParametersErrorWithoutThrowing() {
        var panel = panel("select ?, :a", Map.of());
        assertEquals(SqlParameters.MIXED_STYLES, panel.previewText());
        assertFalse(panel.valuesValid());
        fields(panel).get(0).setText("x");
        assertEquals(SqlParameters.MIXED_STYLES, panel.previewText());
    }

    @Test void okDisabledWhileInvalid() {
        var panel = panel(SQL, Map.of(":customer", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "abc")));
        var changes = new AtomicInteger();
        panel.addChangeListener(changes::incrementAndGet);
        assertFalse(panel.valuesValid());
        fields(panel).get(0).setText("5");
        assertTrue(changes.get() > 0);
        assertTrue(panel.valuesValid());
        int before = changes.get();
        all(panel, JBCheckBox.class).get(0).doClick();
        assertTrue(changes.get() > before);
    }

    @Test void valuesValidFollowsTheNumberField() {
        var panel = panel("select ?, ?", Map.of(
                "1", new SqlParameterValue(SqlParameterValue.Type.TEXT, "x"),
                "2", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "abc")));
        assertFalse(panel.valuesValid());
        fields(panel).get(1).setText("12");
        assertTrue(panel.valuesValid());
    }

    @Test void layoutValidityIsTheAwtDefault() {
        var panel = panel("select ?", Map.of());
        assertFalse(panel.isValid());
        panel.addNotify();
        panel.validate();
        assertTrue(panel.isValid());
    }
}
