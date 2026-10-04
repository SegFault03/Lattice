package com.segfault03.ideadb.editor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.MetadataService;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DatabaseEditorManager implements com.intellij.openapi.Disposable {
    private volatile boolean disposed;
    private final com.segfault03.ideadb.service.DatabaseTaskScope tasks=com.segfault03.ideadb.service.DatabaseTaskService.getInstance().newScope();
    private final Project project;
    private final Map<String, DatabaseVirtualFile> openFiles = new ConcurrentHashMap<>();

    public DatabaseEditorManager(Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(TableRenameListener.TOPIC,this::tableRenamed);
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

    private void tableRenamed(ConnectionConfig config,String database,String oldName,TableMetadata renamed) {
        String oldKey=TableDataVirtualFile.key(config.getId(),database,oldName), newKey=TableDataVirtualFile.key(config.getId(),database,renamed.getName());
        DatabaseVirtualFile oldFile=openFiles.remove(oldKey);
        boolean reopen=oldFile!=null && FileEditorManager.getInstance(project).isFileOpen(oldFile);
        if(reopen) FileEditorManager.getInstance(project).closeFile(oldFile);
        boolean moved=com.segfault03.ideadb.state.TableDraftState.getInstance(project).move(oldKey,newKey);
        if(!moved) com.intellij.openapi.ui.Messages.showWarningDialog(project,"Pending edits for both table names were preserved. Resolve the existing destination draft before restoring the renamed table's draft.","Draft Conflict");
        if(reopen && moved) openTableData(config,database,renamed);
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
