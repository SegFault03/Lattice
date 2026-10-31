package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One row per SQL parameter: its key, a type, a value and a NULL switch. */
public final class SqlParameterPanel extends JPanel {
    private static final SqlParameterValue.Type[] TYPES = {
            SqlParameterValue.Type.TEXT, SqlParameterValue.Type.NUMBER, SqlParameterValue.Type.BOOLEAN,
            SqlParameterValue.Type.DATE, SqlParameterValue.Type.TIMESTAMP};

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private final class Row {
        final ComboBox<SqlParameterValue.Type> type = new ComboBox<>(TYPES);
        final JBTextField value = new JBTextField(24);
        final JBCheckBox isNull = new JBCheckBox("NULL");
        final JBLabel error = new JBLabel();

        Row(SqlParameterValue initial) {
            boolean nullValue = initial.type() == SqlParameterValue.Type.NULL;
            if (!nullValue) type.setSelectedItem(initial.type());
            value.setText(initial.text());
            isNull.setSelected(nullValue);
            error.setForeground(JBColor.RED);
            Runnable changed = () -> { refresh(); listeners.forEach(Runnable::run); };
            type.addActionListener(e -> changed.run());
            isNull.addActionListener(e -> changed.run());
            value.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override protected void textChanged(@NotNull DocumentEvent event) { changed.run(); }
            });
            refresh();
        }

        SqlParameterValue current() {
            var selected = isNull.isSelected() ? SqlParameterValue.Type.NULL : (SqlParameterValue.Type) type.getSelectedItem();
            return new SqlParameterValue(selected, value.getText());
        }

        void refresh() {
            type.setEnabled(!isNull.isSelected());
            value.setEnabled(!isNull.isSelected());
            String message = current().validationError();
            error.setText(message == null ? " " : message);
        }
    }

    /** @param initial values to start from; a key without one starts as empty text */
    public SqlParameterPanel(@NotNull List<String> keys, @NotNull Map<String, SqlParameterValue> initial) {
        super(new GridBagLayout());
        var constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.insets = JBUI.insets(2, 4);
        constraints.anchor = GridBagConstraints.WEST;
        for (String key : keys) {
            Row row = new Row(initial.getOrDefault(key, new SqlParameterValue(SqlParameterValue.Type.TEXT, "")));
            rows.put(key, row);
            constraints.gridx = 0; constraints.weightx = 0; constraints.fill = GridBagConstraints.NONE;
            add(new JBLabel(key), constraints);
            constraints.gridx = 1;
            add(row.type, constraints);
            constraints.gridx = 2; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL;
            add(row.value, constraints);
            constraints.gridx = 3; constraints.weightx = 0; constraints.fill = GridBagConstraints.NONE;
            add(row.isNull, constraints);
            constraints.gridy++;
            constraints.gridx = 2; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL;
            add(row.error, constraints);
            constraints.gridy++;
        }
    }

    /** The values as entered, by key, in the order of the keys. */
    public @NotNull Map<String, SqlParameterValue> values() {
        Map<String, SqlParameterValue> values = new LinkedHashMap<>();
        rows.forEach((key, row) -> values.put(key, row.current()));
        return values;
    }

    /** Named so it does not override {@link java.awt.Component#isValid()}, which Swing layout relies on. */
    public boolean valuesValid() {
        return rows.values().stream().allMatch(row -> row.current().validationError() == null);
    }

    /** Called after any row changes. */
    public void addChangeListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }
}
