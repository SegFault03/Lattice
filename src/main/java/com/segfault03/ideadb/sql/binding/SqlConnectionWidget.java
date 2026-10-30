package com.segfault03.ideadb.sql.binding;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManager;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows which dialect and connection the current file uses, and opens the chooser above itself on click. It shows for
 * .sql files and for source files that currently have SQL injected in them; it is blank for other files.
 */
final class SqlConnectionWidget implements StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    private final Project project;
    /** The text last shown, so that the daemon finishing only refreshes the bar when the entry changes. */
    private volatile String shown = "";

    SqlConnectionWidget(@NotNull Project project) { this.project = project; }

    /** Refreshes the widget in this project's status bar, if it has one. */
    static void update(@NotNull Project project) {
        StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
        if (statusBar != null) statusBar.updateWidget(SqlConnectionWidgetFactory.ID);
    }

    @Override public @NotNull String ID() { return SqlConnectionWidgetFactory.ID; }
    @Override public @Nullable WidgetPresentation getPresentation() { return this; }

    @Override public void install(@NotNull StatusBar statusBar) {
        var connection = project.getMessageBus().connect(this);
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(@NotNull FileEditorManagerEvent event) { statusBar.updateWidget(ID()); }
        });
        // SQL strings are injected once the daemon has run over the file, so that is when a source file starts to qualify.
        connection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, new DaemonCodeAnalyzer.DaemonListener() {
            @Override public void daemonFinished() {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed() && !getSelectedValue().equals(shown)) statusBar.updateWidget(ID());
                }, project.getDisposed());
            }
        });
    }

    @Override public void dispose() {}

    /** The selected file when it is SQL or hosts SQL; null otherwise. */
    private @Nullable VirtualFile sqlFile() {
        if (project.isDisposed()) return null;
        VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
        if (selected.length == 0) return null;
        VirtualFile file = selected[0];
        return file.getFileType() == LatticeSqlFileType.INSTANCE || SqlInjectionHosts.hostsSql(project, file) ? file : null;
    }

    @Override public @NotNull String getSelectedValue() {
        VirtualFile file = sqlFile();
        if (file == null) return shown = "";
        SqlDialectProfile dialect = SqlDialectResolver.resolve(project, file);
        ConnectionConfig connection = SqlDialectResolver.connection(project, file);
        return shown = "SQL: " + dialect.displayName() + (connection == null ? "" : " · " + connection.getName());
    }

    @Override public @Nullable String getTooltipText() {
        VirtualFile file = sqlFile();
        if (file == null) return null;
        String origin = SqlDialectResolver.isFromProjectFiles(project, file) ? " Chosen because the project's configuration files point at it." : "";
        return "The SQL dialect and Lattice connection used for this file." + origin + " Click to choose.";
    }

    @Override public @Nullable ListPopup getPopupStep() {
        VirtualFile file = sqlFile();
        if (file == null) return null;
        return SqlConnectionChooser.filePopup(project, file, SqlConnectionChooser.editorOf(project, file));
    }

    @Override public @Nullable ListPopup getPopup() { return getPopupStep(); }
}
