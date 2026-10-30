package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.Consumer;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/** Shows which dialect and connection the current SQL file uses, and opens the chooser on click. Blank for other files. */
final class SqlConnectionWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    private final Project project;

    SqlConnectionWidget(@NotNull Project project) { this.project = project; }

    /** Refreshes the widget in this project's status bar, if it has one. */
    static void update(@NotNull Project project) {
        StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
        if (statusBar != null) statusBar.updateWidget(SqlConnectionWidgetFactory.ID);
    }

    @Override public @NotNull String ID() { return SqlConnectionWidgetFactory.ID; }
    @Override public @Nullable WidgetPresentation getPresentation() { return this; }

    @Override public void install(@NotNull StatusBar statusBar) {
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(@NotNull FileEditorManagerEvent event) { statusBar.updateWidget(ID()); }
        });
    }

    @Override public void dispose() {}

    private @Nullable VirtualFile sqlFile() {
        if (project.isDisposed()) return null;
        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        return selected.length > 0 && selected[0].getFileType() == LatticeSqlFileType.INSTANCE ? selected[0] : null;
    }

    @Override public @NotNull String getText() {
        VirtualFile file = sqlFile();
        if (file == null) return "";
        SqlDialectProfile dialect = SqlDialectResolver.resolve(project, file);
        ConnectionConfig connection = SqlDialectResolver.connection(project, file);
        return "SQL: " + dialect.displayName() + (connection == null ? "" : " · " + connection.getName());
    }

    @Override public float getAlignment() { return Component.CENTER_ALIGNMENT; }

    @Override public @Nullable String getTooltipText() {
        VirtualFile file = sqlFile();
        if (file == null) return null;
        String origin = SqlDialectResolver.isFromProjectFiles(project, file) ? " Chosen because the project's configuration files point at it." : "";
        return "The SQL dialect and Lattice connection used for this file." + origin + " Click to choose.";
    }

    @Override public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return event -> {
            VirtualFile file = sqlFile();
            if (file != null) SqlConnectionChooser.chooseForFile(project, file);
        };
    }
}
