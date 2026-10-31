package com.segfault03.ideadb.sql.run;

import com.intellij.ui.components.JBTextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
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

    @Test void valuesValidFollowsTheNumberField() {
        var panel = new SqlParameterPanel(List.of("1", "2"), Map.of(
                "1", new SqlParameterValue(SqlParameterValue.Type.TEXT, "x"),
                "2", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "abc")));
        assertFalse(panel.valuesValid());
        fields(panel).get(1).setText("12");
        assertTrue(panel.valuesValid());
    }

    @Test void layoutValidityIsTheAwtDefault() {
        var panel = new SqlParameterPanel(List.of("1"), Map.of());
        assertFalse(panel.isValid());
        panel.addNotify();
        panel.validate();
        assertTrue(panel.isValid());
    }
}
