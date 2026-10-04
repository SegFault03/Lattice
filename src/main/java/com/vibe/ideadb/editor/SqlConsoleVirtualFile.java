package com.vibe.ideadb.editor;

import com.intellij.openapi.project.Project;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.ui.SqlQueryConsolePanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

public class SqlConsoleVirtualFile extends DatabaseVirtualFile {
    private final ConnectionConfig config;
    private final String initialDb;
    private final List<String> allDatabases;
    private SqlQueryConsolePanel panel;

    public SqlConsoleVirtualFile(ConnectionConfig config, String initialDb, List<String> allDatabases) {
        super("Console: " + config.getName() + (initialDb != null && !initialDb.isEmpty() ? " [" + initialDb + "]" : ""),
              "console:" + config.getId() + ":" + (initialDb != null ? initialDb : ""),
              DatabaseFileTypes.CONSOLE);
        this.config = config;
        this.initialDb = initialDb;
        this.allDatabases = allDatabases;
    }

    public ConnectionConfig getConfig() {
        return config;
    }

    public String getInitialDb() {
        return initialDb;
    }

    @Override
    public JComponent createComponent(@NotNull Project project) {
        panel = new SqlQueryConsolePanel(project, config, initialDb, allDatabases);
        return panel;
    }

    public SqlQueryConsolePanel getPanel() {
        return panel;
    }
}
