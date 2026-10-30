package com.segfault03.ideadb.sql.discovery;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.dialog.ConnectionDialog;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlEditorRefresh;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Turns a database found in a project file into a saved connection: the connection dialog opens with the type, server,
 * port and database filled in, and the user name too when the file names one. The password is always empty: the person
 * reviews the connection and enters it; nothing is saved until they confirm.
 */
public final class ProjectConnectionOffer {
    private ProjectConnectionOffer() {}

    /** The databases found in project files that no saved connection points at and the user has not turned away. */
    public static @NotNull List<DiscoveredConnection> open(@NotNull Project project) {
        var bindings = SqlBindingsState.getInstance(project);
        return JdbcDiscovery.getInstance(project).unmatched().stream()
                .filter(discovered -> !bindings.isDiscoveryDismissed(discovered.describe())).toList();
    }

    /** Opens the dialog for one found database; on OK saves the connection and, if the project has no default, makes it the default. */
    public static void add(@NotNull Project project, @NotNull DiscoveredConnection discovered, @Nullable VirtualFile file) {
        ConnectionConfig config = discovered.toConnection();
        ConnectionDialog dialog = new ConnectionDialog(project, config, true);
        if (!dialog.showAndGet()) return;
        ConnectionConfig saved = dialog.getResultConfig();
        DatabaseSettingsState.getInstance().addConnection(saved);
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        if (bindings.defaultConnectionId().isEmpty()) bindings.setDefaultConnectionId(saved.getId());
        if (file != null) SqlEditorRefresh.refresh(project, file); else SqlEditorRefresh.refreshAll(project);
    }

    /** Offers every database that could be added: straight to the dialog when there is one, else a list. */
    public static void choose(@NotNull Project project, @Nullable VirtualFile file) {
        List<DiscoveredConnection> candidates = open(project);
        if (candidates.size() == 1) { add(project, candidates.get(0), file); return; }
        JBPopupFactory.getInstance().createPopupChooserBuilder(candidates).setTitle("Add a connection found in the project")
                .setRenderer(new com.intellij.ui.SimpleListCellRenderer<DiscoveredConnection>() {
                    @Override public void customize(@NotNull javax.swing.JList<? extends DiscoveredConnection> list, DiscoveredConnection value, int index, boolean selected, boolean focused) {
                        setText(value.describe());
                    }
                })
                .setItemChosenCallback(chosen -> add(project, chosen, file)).createPopup().showCenteredInCurrentWindow(project);
    }
}
