package com.segfault03.ideadb.sql.status;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlEmbeddedBinding;
import com.segfault03.ideadb.sql.binding.SqlConnectionChooser;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.card.SqlCardAnchor;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.InputEvent;

/**
 * Lattice's status for the file, in the editor's inspection widget beside the error and warning counts: a spinner while
 * the schema is read or the SQL is found and checked, and otherwise what the checks can rely on. Shown only for files
 * with Lattice SQL. A click opens the details, with Reload Schema and the connection chooser.
 */
final class LatticeStatusAction extends DumbAwareAction {
    private final Editor editor;

    LatticeStatusAction(@NotNull Editor editor) {
        super("Lattice SQL Status");
        this.editor = editor;
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        LatticeFileStatus status = status();
        var presentation = event.getPresentation();
        presentation.setEnabledAndVisible(status != null);
        if (status == null) return;
        presentation.setIcon(status.icon());
        presentation.setText(status.summary());
        presentation.setDescription(status.summary());
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        LatticeFileStatus status = status();
        Project project = editor.getProject();
        VirtualFile file = file();
        if (status == null || project == null || file == null) return;
        JBPopup[] popup = new JBPopup[1];
        JComponent content = details(project, file, status, () -> { if (popup[0] != null) popup[0].cancel(); });
        popup[0] = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
                .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true).setShowShadow(true).createPopup();
        InputEvent input = event.getInputEvent();
        Component source = input == null ? null : input.getComponent();
        if (source != null && source.isShowing()) popup[0].show(RelativePoint.getSouthWestOf((JComponent) source));
        else popup[0].showInBestPositionFor(editor);
    }

    /** The file's status, or null when the icon does not apply: no Lattice SQL in the file, or Lattice SQL turned off. */
    private @Nullable LatticeFileStatus status() {
        Project project = editor.getProject();
        VirtualFile file = file();
        if (project == null || project.isDisposed() || file == null || editor.isDisposed() || !SqlEditorSettings.getInstance().isEnabled()) return null;
        // The SQL console's input (and the other embedded inputs) is not a file Lattice checks, so it has no status to show.
        if (SqlEmbeddedBinding.of(file) != null) return null;
        int cards = SqlCardAnchor.count(editor);
        if (cards == 0 && !LatticeActivity.hasSql(file) && !isLatticeSql(project)) return null;
        ConnectionConfig connection = SqlDialectResolver.connection(project, SqlBindingsState.hostOf(file));
        return LatticeFileStatus.of(project, file, connection, cards);
    }

    private boolean isLatticeSql(Project project) {
        return ReadAction.compute(() -> {
            PsiFile psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            return psi != null && psi.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE);
        });
    }

    private @Nullable VirtualFile file() { return FileDocumentManager.getInstance().getFile(editor.getDocument()); }

    private JComponent details(Project project, VirtualFile file, LatticeFileStatus status, Runnable close) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(10, 12));
        var constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.insets = JBUI.insetsBottom(8);
        JBLabel title = new JBLabel(status.summary(), status.icon(), JBLabel.LEFT);
        title.setFont(JBUI.Fonts.label().asBold());
        panel.add(title, constraints);
        constraints.gridwidth = 1;
        ConnectionConfig connection = status.connection();
        row(panel, constraints, "File", file.getName());
        row(panel, constraints, "Connection", connection == null ? "None" : connection.toString());
        row(panel, constraints, "Schema", status.schemaText());
        if (status.statements() > 0) row(panel, constraints, "SQL", status.statements() == 1 ? "1 card" : status.statements() + " cards");

        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        links.setOpaque(false);
        if (connection != null) {
            links.add(new ActionLink(status.state() == LatticeFileStatus.State.SCHEMA_MISSING ? "Read Schema" : "Reload Schema", event -> {
                close.run();
                SqlSchemaService.getInstance().reload(connection);
            }));
            links.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(16)));
        }
        links.add(new ActionLink(connection == null ? "Choose Connection…" : "Change Connection…", event -> {
            close.run();
            SqlConnectionChooser.chooseForFile(project, SqlBindingsState.hostOf(file), editor);
        }));
        constraints.gridy++;
        constraints.gridx = 0;
        constraints.gridwidth = 2;
        constraints.insets = JBUI.insetsTop(10);
        panel.add(links, constraints);
        return panel;
    }

    private static void row(JPanel panel, GridBagConstraints constraints, String label, String value) {
        constraints.gridy++;
        constraints.insets = JBUI.insets(1, 0, 1, 12);
        constraints.gridx = 0;
        JBLabel name = new JBLabel(label);
        name.setForeground(UIUtil.getContextHelpForeground());
        panel.add(name, constraints);
        constraints.gridx = 1;
        constraints.insets = JBUI.insets(1, 0);
        panel.add(new JBLabel(value), constraints);
    }
}
