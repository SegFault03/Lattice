package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.Map;

/** Asks for the value of every parameter of a statement. OK stays disabled while a value does not fit its type. */
public final class SqlParameterDialog extends DialogWrapper {
    private final SqlParameterPanel panel;

    public SqlParameterDialog(@NotNull Project project, @NotNull SqlRunTarget target, @NotNull Map<String, SqlParameterValue> initial) {
        super(project);
        panel = new SqlParameterPanel(target.parameters().keys(), initial);
        panel.addChangeListener(() -> setOKActionEnabled(panel.isValid()));
        setTitle("SQL Parameters");
        init();
        setOKActionEnabled(panel.isValid());
    }

    @Override protected @NotNull JComponent createCenterPanel() { return panel; }

    /** The entered values, or null when the dialog was cancelled. */
    public @Nullable Map<String, SqlParameterValue> showAndGetValues() {
        return showAndGet() ? panel.values() : null;
    }
}
