package com.vibe.ideadb.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.TableMetadata;
import com.vibe.ideadb.service.DatabaseConnectionManager;
import com.vibe.ideadb.service.MetadataService;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseEditorManager implements com.intellij.openapi.Disposable {
    private volatile boolean disposed;
    private final com.vibe.ideadb.service.DatabaseTaskScope tasks=com.vibe.ideadb.service.DatabaseTaskService.getInstance().newScope();
    private final Project project;
    private final Map<String, DatabaseVirtualFile> openFiles = new ConcurrentHashMap<>();

    public DatabaseEditorManager(Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override
            public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                if (file instanceof DatabaseVirtualFile dbFile) {
                    openFiles.remove(dbFile.getFileKey());
                }
            }
        });
    }

    public static DatabaseEditorManager getInstance(@NotNull Project project) {
        return project.getService(DatabaseEditorManager.class);
    }

    public void openTableData(ConnectionConfig config, String databaseName, TableMetadata tableMetadata) {
        String key = "table:" + config.getId() + ":" + databaseName + ":" + tableMetadata.getName();
        ApplicationManager.getApplication().invokeLater(() -> {
            if(disposed || project.isDisposed()) return;
            DatabaseVirtualFile vf = openFiles.get(key);
            if (vf == null || !FileEditorManager.getInstance(project).isFileOpen(vf)) {
                vf = new TableDataVirtualFile(config, databaseName, tableMetadata);
                openFiles.put(key, vf);
            }
            FileEditorManager.getInstance(project).openFile(vf, true);
        });
    }

    public void openConsole(ConnectionConfig config, String initialDb, String initialSql) {
        String key = "console:" + config.getId() + ":" + (initialDb != null ? initialDb : "");
        tasks.submit(() -> {
            if(disposed || project.isDisposed()) return;
            List<String> dbs = new ArrayList<>();
            try(var read=tasks.openRead(config)) {
                Connection conn=read.connection();
                dbs = MetadataService.getInstance().getDatabases(conn, config);
            } catch (Exception ignored) {
            }

            final List<String> allDbs = dbs;
            ApplicationManager.getApplication().invokeLater(() -> {
                if(disposed || project.isDisposed()) return;
                DatabaseVirtualFile vf = openFiles.get(key);
                if (vf instanceof SqlConsoleVirtualFile consoleVf && FileEditorManager.getInstance(project).isFileOpen(consoleVf)) {
                    FileEditorManager.getInstance(project).openFile(consoleVf, true);
                    if (initialSql != null && consoleVf.getPanel() != null) {
                        consoleVf.getPanel().setSqlText(initialSql);
                    }
                    return;
                }

                SqlConsoleVirtualFile newVf = new SqlConsoleVirtualFile(config, initialDb, allDbs);
                openFiles.put(key, newVf);
                FileEditorManager.getInstance(project).openFile(newVf, true);

                if (initialSql != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                if(disposed || project.isDisposed()) return;
                        if (newVf.getPanel() != null) {
                            newVf.getPanel().setSqlText(initialSql);
                        }
                    });
                }
            });
        });
    }

    @Override public void dispose() {
        disposed=true; openFiles.clear(); tasks.cancelPending();
        ApplicationManager.getApplication().executeOnPooledThread(tasks::close);
    }

    public void openWelcome() {
        String key = "welcome:root";
        ApplicationManager.getApplication().invokeLater(() -> {
            if(disposed || project.isDisposed()) return;
            DatabaseVirtualFile vf = openFiles.get(key);
            if (vf == null || !FileEditorManager.getInstance(project).isFileOpen(vf)) {
                vf = new WelcomeVirtualFile();
                openFiles.put(key, vf);
            }
            FileEditorManager.getInstance(project).openFile(vf, true);
        });
    }
}
