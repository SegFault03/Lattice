package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.List;

/** Asks before a statement is run for real inside a transaction that is rolled back, unless the project turned the question off. */
final class RollbackConfirmation {
    static final String TITLE = "Run and Roll Back";
    static final String SUPPRESS = "Don't ask again for this project";
    static final List<String> CAVEATS = List.of(
            "Sequence and auto-increment values are consumed.",
            "Triggers fire, including any effects outside the database.",
            "Row locks are held until the rollback, and other sessions can wait on them.");

    private static volatile Boolean answerForTests;

    private RollbackConfirmation() {}

    /** True to go ahead. Ticking "Don't ask again" turns the confirmation off for the project, like the setting does. */
    static boolean confirm(@NotNull Project project, @NotNull ConnectionConfig config, @NotNull SqlStatementKind kind) {
        SqlRunState state = SqlRunState.getInstance(project);
        if (!state.confirmRollbackRuns()) return true;
        Boolean answer = answerForTests;
        if (answer != null) return answer;
        Dialog dialog = new Dialog(project, config, kind);
        boolean confirmed = dialog.showAndGet();
        if (confirmed && dialog.suppress.isSelected()) state.setConfirmRollbackRuns(false);
        return confirmed;
    }

    /** Takes the place of the dialog; null brings the real dialog back. */
    @TestOnly
    static void setAnswerForTests(@Nullable Boolean answer, @NotNull Disposable parent) {
        answerForTests = answer;
        Disposer.register(parent, () -> answerForTests = null);
    }

    private static final class Dialog extends DialogWrapper {
        private final ConnectionConfig config;
        private final SqlStatementKind kind;
        final JBCheckBox suppress = new JBCheckBox(SUPPRESS);

        Dialog(Project project, ConnectionConfig config, SqlStatementKind kind) {
            super(project);
            this.config = config;
            this.kind = kind;
            setTitle(TITLE);
            setOKButtonText(TITLE);
            init();
        }

        @Override protected @NotNull JComponent createCenterPanel() {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            String statement = kind == SqlStatementKind.DML ? "DML statement" : "statement";
            panel.add(new JBLabel("Run this " + statement + " on \"" + config.getName() + "\" and roll it back?"));
            panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
            for (String caveat : CAVEATS) panel.add(new JBLabel("• " + caveat));
            panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
            panel.add(suppress);
            return panel;
        }
    }
}
