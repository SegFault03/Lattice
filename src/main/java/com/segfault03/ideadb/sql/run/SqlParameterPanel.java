package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.ui.DatabaseInputs;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One row per SQL parameter: its key, a type, a value and a NULL switch, above a live preview of the SQL with the values written in. */
public final class SqlParameterPanel extends JPanel {
    private static final SqlParameterValue.Type[] TYPES = {
            SqlParameterValue.Type.TEXT, SqlParameterValue.Type.NUMBER, SqlParameterValue.Type.BOOLEAN,
            SqlParameterValue.Type.DATE, SqlParameterValue.Type.TIMESTAMP};

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final CharSequence sql;
    private final SqlParameters.Result parameters;
    private final DatabaseType type;
    private final JBTextArea preview = new JBTextArea(4, 40);

    private final class Row {
        final JComboBox<SqlParameterValue.Type> type = DatabaseInputs.comboBox(TYPES);
        final JBTextField value = DatabaseInputs.textField(24);
        final JBCheckBox isNull = new JBCheckBox("NULL");

        Row(SqlParameterValue initial) {
            boolean nullValue = initial.type() == SqlParameterValue.Type.NULL;
            if (!nullValue) type.setSelectedItem(initial.type());
            value.setText(initial.text());
            isNull.setSelected(nullValue);
            Runnable changed = () -> { refresh(); updatePreview(); listeners.forEach(Runnable::run); };
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
        }
    }

    /** @param initial values to start from; a key without one starts as empty text */
    public SqlParameterPanel(@NotNull CharSequence sql, @NotNull SqlParameters.Result parameters, @NotNull DatabaseType type,
                             @NotNull Map<String, SqlParameterValue> initial) {
        super(new GridBagLayout());
        this.sql = sql;
        this.parameters = parameters;
        this.type = type;
        List<String> keys = parameters.keys();
        var constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.insets = JBUI.insets(2, 4);
        constraints.anchor = GridBagConstraints.WEST;
        for (String key : keys) {
            Row row = new Row(initial.getOrDefault(key, new SqlParameterValue(SqlParameterValue.Type.TEXT, "")));
            rows.put(key, row);
            constraints.gridx = 0; constraints.weightx = 0; constraints.fill = GridBagConstraints.NONE;
            constraints.insets = JBUI.insets(2, 8, 2, 4);
            add(new JBLabel(key), constraints);
            constraints.insets = JBUI.insets(2, 4);
            constraints.gridx = 1;
            add(row.type, constraints);
            constraints.gridx = 2; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL;
            add(row.value, constraints);
            constraints.gridx = 3; constraints.weightx = 0; constraints.fill = GridBagConstraints.NONE;
            add(row.isNull, constraints);
            constraints.gridy++;
        }
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        // Tests and previews can run without an application, hence the monospaced fallback.
        preview.setFont(ApplicationManager.getApplication() == null ? new Font(Font.MONOSPACED, Font.PLAIN, preview.getFont().getSize())
                : EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        preview.getAccessibleContext().setAccessibleName("SQL preview");
        constraints.gridx = 0; constraints.gridwidth = 4; constraints.weightx = 1; constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        add(new JBScrollPane(preview), constraints);
        updatePreview();
    }

    /** The SQL with the values written in, or the first validation error while a value does not fit its type. */
    String previewText() { return preview.getText(); }

    private void updatePreview() {
        preview.setText(previewContent());
        preview.setCaretPosition(0);
    }

    private String previewContent() {
        if (parameters.error() != null) return parameters.error();
        for (var entry : rows.entrySet()) {
            String message = entry.getValue().current().validationError();
            if (message != null) return entry.getKey() + ": " + message;
        }
        try {
            return SqlLiteralRenderer.render(sql, parameters, values(), type);
        } catch (IllegalArgumentException e) {
            return String.valueOf(e.getMessage());
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
        return parameters.error() == null && rows.values().stream().allMatch(row -> row.current().validationError() == null);
    }

    /** Called after any row changes. */
    public void addChangeListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }
}
