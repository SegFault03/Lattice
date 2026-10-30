package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.discovery.DiscoveredConnection;
import com.segfault03.ideadb.sql.discovery.ProjectConnectionOffer;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Popups for picking the connection that supplies the dialect, for one file or for the whole project. */
public final class SqlConnectionChooser {
    private SqlConnectionChooser() {}

    private record Choice(@NotNull String label, @Nullable String connectionId, @Nullable String dialectId, @Nullable DiscoveredConnection found) {
        Choice(@NotNull String label, @Nullable String connectionId, @Nullable String dialectId) { this(label, connectionId, dialectId, null); }
        @Override public String toString() { return label; }
    }

    public static void chooseForFile(@NotNull Project project, @NotNull VirtualFile file) {
        List<Choice> choices = connectionChoices();
        choices.add(new Choice("Generic SQL (no connection)", null, SqlDialects.ANSI.id()));
        choices.add(new Choice("Use the project default", null, null));
        for (DiscoveredConnection found : ProjectConnectionOffer.open(project)) choices.add(new Choice("Add " + found.describe() + "...", null, null, found));
        show(project, "SQL connection for " + file.getName(), choices, choice -> {
            if (choice.found() != null) { ProjectConnectionOffer.add(project, choice.found(), file); return; }
            SqlBindingsState bindings = SqlBindingsState.getInstance(project);
            if (choice.connectionId() != null) bindings.bindFileToConnection(file, choice.connectionId());
            else if (choice.dialectId() != null) bindings.bindFileToDialect(file, choice.dialectId());
            else bindings.clearFile(file);
            SqlEditorRefresh.refresh(project, file);
        });
    }

    public static void chooseForProject(@NotNull Project project) {
        List<Choice> choices = connectionChoices();
        choices.add(new Choice("None (generic SQL)", null, null));
        show(project, "Default SQL connection for this project", choices, choice -> {
            SqlBindingsState.getInstance(project).setDefaultConnectionId(choice.connectionId() == null ? "" : choice.connectionId());
            SqlEditorRefresh.refreshAll(project);
        });
    }

    private static List<Choice> connectionChoices() {
        List<Choice> choices = new ArrayList<>();
        for (ConnectionConfig config : DatabaseSettingsState.getInstance().getConnections()) {
            choices.add(new Choice(config.getType().getDisplayName() + " · " + config.getName(), config.getId(), null));
        }
        return choices;
    }

    private static void show(Project project, String title, List<Choice> choices, com.intellij.util.Consumer<Choice> onChosen) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices).setTitle(title)
                .setItemChosenCallback(onChosen).createPopup().showCenteredInCurrentWindow(project);
    }
}
