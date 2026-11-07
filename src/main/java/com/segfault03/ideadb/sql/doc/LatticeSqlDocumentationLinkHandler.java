package com.segfault03.ideadb.sql.doc;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.platform.backend.documentation.DocumentationLinkHandler;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.LinkResolveResult;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.util.HashSet;
import java.util.Set;

/** Follows the links in Lattice SQL cards: to another card, or to the table in a Lattice editor. */
public final class LatticeSqlDocumentationLinkHandler implements DocumentationLinkHandler, DumbAware {
    @Override
    public @Nullable LinkResolveResult resolveLink(@NotNull DocumentationTarget target, @NotNull String url) {
        if (!(target instanceof SqlDocumentationTarget source)) return null;
        SqlDocLinks.Parsed link = SqlDocLinks.parse(url);
        if (link == null) return null;
        var next = new SqlDocumentationTarget(source.project(), link.connectionId(), link.target());
        if (link.kind().equals("open")) {
            open(source, link);
            return LinkResolveResult.resolvedTarget(source);
        }
        return next.html() == null ? null : LinkResolveResult.resolvedTarget(next);
    }

    private static void open(SqlDocumentationTarget source, SqlDocLinks.Parsed link) {
        ConnectionConfig config = DatabaseSettingsState.getInstance().getConnection(link.connectionId());
        if (config == null || !(link.target() instanceof SqlTarget.Table table)) return;
        var schema = SqlSchemaService.getInstance().schema(config);
        var found = schema == null ? null : schema.table(table.namespace(), table.table());
        if (found == null) return;
        Project project = source.project();
        ApplicationManager.getApplication().invokeLater(() -> {
            // The card stays up over the new tab otherwise: it is a popup of the SQL editor, which keeps no focus to lose.
            closeEditorPopups(project);
            DatabaseEditorManager.getInstance(project).openTableData(config, table.namespace(), found.metadata());
        }, project.getDisposed());
    }

    /**
     * Closes the hover and quick documentation popups shown over the project's editors. A popup placed at a point in the
     * editor is owned by the window's root pane, not by the editor, so the popups are looked up from there.
     */
    private static void closeEditorPopups(Project project) {
        Set<JRootPane> roots = new HashSet<>();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            JRootPane root = editor.getProject() == project ? SwingUtilities.getRootPane(editor.getComponent()) : null;
            if (root == null || !roots.add(root)) continue;
            for (JBPopup popup : JBPopupFactory.getInstance().getChildPopups(root)) popup.cancel();
        }
    }
}
