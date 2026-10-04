package com.vibe.ideadb.editor;

import com.intellij.openapi.project.Project;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.TableMetadata;
import com.vibe.ideadb.ui.TableDataEditorPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class TableDataVirtualFile extends DatabaseVirtualFile {
    private final ConnectionConfig config;
    private final String databaseName;
    private final TableMetadata tableMetadata;
    private TableDataEditorPanel panel;

    public TableDataVirtualFile(ConnectionConfig config, String databaseName, TableMetadata tableMetadata) {
        super(tableMetadata.getName() + " [" + config.getName() + "]",
              "table:" + config.getId() + ":" + databaseName + ":" + tableMetadata.getName(),
              DatabaseFileTypes.TABLE);
        this.config = config;
        this.databaseName = databaseName;
        this.tableMetadata = tableMetadata;
    }

    public ConnectionConfig getConfig() {
        return config;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public TableMetadata getTableMetadata() {
        return tableMetadata;
    }

    @Override
    public JComponent createComponent(@NotNull Project project) {
        if (panel == null) {
            panel = new TableDataEditorPanel(project, config, databaseName, tableMetadata);
        }
        return panel;
    }

    public TableDataEditorPanel getPanel() {
        return panel;
    }
}
