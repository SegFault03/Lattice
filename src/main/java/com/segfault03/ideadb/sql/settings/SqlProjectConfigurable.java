package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorNotifications;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlEditorRefresh;
import com.segfault03.ideadb.sql.discovery.JdbcDiscovery;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/** Settings | Tools | Lattice SQL Editor | Project: what applies to the SQL of this project only. */
public final class SqlProjectConfigurable implements SearchableConfigurable {
    /** The first entry of the connection list: no default, so the project's files and the only connection decide. */
    private static final Object AUTOMATIC = new Object() { @Override public String toString() { return "Automatic (project files, or the only connection)"; } };

    private final Project project;
    private final JComboBox<Object> defaultConnection = new JComboBox<>();
    private final JBCheckBox useProjectFiles = new JBCheckBox("Find the database in the project's configuration files (application.yml, persistence.xml...)");
    private final JButton showOffers = new JButton("Offer the databases I turned down again");
    private JPanel panel;

    public SqlProjectConfigurable(@NotNull Project project) { this.project = project; }

    @Override public @NotNull String getId() { return "com.segfault03.lattice.sql.editor.project"; }
    @Override public @Nls String getDisplayName() { return "Project"; }

    @Override public JComponent createComponent() {
        showOffers.addActionListener(event -> {
            SqlBindingsState.getInstance(project).clearDismissedDiscoveries();
            EditorNotifications.getInstance(project).updateAllNotifications();
        });
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Default connection for SQL in this project:", defaultConnection)
                .addComponent(useProjectFiles, JBUI.scale(8))
                .addComponent(showOffers, JBUI.scale(4))
                .addComponentFillVertically(new JPanel(), 0).getPanel();
        reset();
        return panel;
    }

    private String selectedId() {
        return defaultConnection.getSelectedItem() instanceof ConnectionConfig connection ? connection.getId() : "";
    }

    @Override public boolean isModified() {
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        return !selectedId().equals(bindings.defaultConnectionId()) || useProjectFiles.isSelected() != bindings.useProjectFiles();
    }

    @Override public void apply() {
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        boolean filesChanged = useProjectFiles.isSelected() != bindings.useProjectFiles();
        bindings.setDefaultConnectionId(selectedId());
        bindings.useProjectFiles(useProjectFiles.isSelected());
        if (filesChanged) JdbcDiscovery.getInstance(project).refresh();
        SqlEditorRefresh.refreshAll(project);
        EditorNotifications.getInstance(project).updateAllNotifications();
    }

    @Override public void reset() {
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        List<Object> choices = new ArrayList<>();
        choices.add(AUTOMATIC);
        choices.addAll(DatabaseSettingsState.getInstance().getConnections());
        defaultConnection.setModel(new DefaultComboBoxModel<>(choices.toArray()));
        Object selected = AUTOMATIC;
        for (Object choice : choices) if (choice instanceof ConnectionConfig connection && connection.getId().equals(bindings.defaultConnectionId())) selected = choice;
        defaultConnection.setSelectedItem(selected);
        useProjectFiles.setSelected(bindings.useProjectFiles());
    }

    @Override public void disposeUIResources() { panel = null; }
}
