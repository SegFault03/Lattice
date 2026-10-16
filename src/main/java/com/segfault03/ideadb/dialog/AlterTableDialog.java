package com.segfault03.ideadb.dialog;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.segfault03.ideadb.editor.TableRenameListener;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import com.segfault03.ideadb.ui.DatabaseUi;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.sql.Connection;
import java.util.List;

/** Reviews a complete batch and applies it on an editor-owned background connection. */
public class AlterTableDialog extends DialogWrapper {
    private final Project project;
    private final ConnectionConfig config;
    private final String databaseName;
    private final TableMetadata tableMetadata;
    private AlterTablePanel editor;
    private Timer previewDelay;
    private DatabaseTaskScope previewScope, mutationScope;
    private long revision;
    private boolean applying, loading;

    public AlterTableDialog(@Nullable Project project, ConnectionConfig config, String databaseName, TableMetadata tableMetadata) {
        super(project, true);
        this.project = project; this.config = config; this.databaseName = databaseName; this.tableMetadata = tableMetadata;
        setTitle("Edit table: " + tableMetadata.getName()); setResizable(true); setCancelButtonText("Close");
        init();
        previewDelay = new Timer(300, event -> preparePreview()); previewDelay.setRepeats(false);
        editor.onChange(this::queuePreview);
        editor.applyButton().addActionListener(event -> apply());
        editor.closeButton().addActionListener(event -> doCancelAction());
        editor.refreshButton().addActionListener(event -> {
            if (!editor.dirty() || confirmDiscard()) loadColumns();
        });
        if (getRootPane() != null) getRootPane().setDefaultButton(editor.applyButton());
        if (tableMetadata.getColumns().isEmpty()) loadColumns();
    }
    @Override protected javax.swing.border.Border createContentPaneBorder() {
        return com.intellij.util.ui.JBUI.Borders.empty(6, 12, 10, 12);
    }
    @Override protected Action[] createActions() { return new Action[0]; }
    @Override protected @Nullable JComponent createSouthPanel() { return null; }
    @Override protected @Nullable String getDimensionServiceKey() { return "Lattice.AlterTableDialog.v3"; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return editor.columnList(); }
    @Override protected JComponent createCenterPanel() {
        editor = new AlterTablePanel(config, databaseName, tableMetadata.getName(), tableMetadata.getColumns());
        return editor;
    }
    private boolean confirmDiscard() {
        return Messages.showYesNoDialog(project, "Discard all pending table changes?", "Discard changes",
                "Discard", "Keep editing", Messages.getQuestionIcon()) == Messages.YES;
    }
    @Override public void doCancelAction() {
        if (applying || loading || editor.dirty() && !confirmDiscard()) return;
        super.doCancelAction();
    }
    private void cancelPreview() {
        revision++;
        if (previewDelay != null) previewDelay.stop();
        DatabaseTaskScope old = previewScope; previewScope = null;
        if (old != null) {
            old.cancelPending();
            ApplicationManager.getApplication().executeOnPooledThread(old::close);
        }
    }
    private void queuePreview() {
        cancelPreview();
        if (!applying && !loading && !isDisposed() && editor.needsPreview()) previewDelay.restart();
    }
    private void preparePreview() {
        if (isDisposed() || !editor.needsPreview()) return;
        TableAlterationBatch request = editor.batch(); long token = revision;
        DatabaseTaskScope scope = DatabaseTaskService.getInstance().newScope(); previewScope = scope;
        scope.submit(() -> {
            try (var read = scope.openRead(config)) {
                List<String> statements = DdlService.getInstance().previewAlterBatch(read.connection(), config, databaseName, request);
                SwingUtilities.invokeLater(() -> {
                    if (!isDisposed() && token == revision) editor.showPreview(request, statements);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (!isDisposed() && token == revision) editor.previewFailed(request, "Preview failed · " + message(error));
                });
            }
        });
    }
    private void loadColumns() {
        if (applying || loading || isDisposed()) return;
        cancelPreview(); loading = true; editor.setBusy(true);
        editor.status("Loading columns…", DatabaseUi.Tone.BUSY);
        DatabaseTaskScope scope = DatabaseTaskService.getInstance().newScope(); previewScope = scope;
        scope.submit(() -> {
            try (var read = scope.openRead(config)) {
                List<ColumnMetadata> columns = MetadataService.getInstance().getColumns(read.connection(), config, databaseName, tableMetadata.getName());
                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    tableMetadata.setColumns(columns); loading = false; editor.setBusy(false); editor.replaceColumns(columns, null);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    loading = false; editor.setBusy(false);
                    editor.status("Could not load columns · " + message(error), DatabaseUi.Tone.ERROR);
                });
            }
        });
    }
    private void apply() {
        AlterTablePanel.Prepared prepared = editor.prepared();
        if (applying || loading || prepared == null || isDisposed()) return;
        TableAlterationBatch request = prepared.batch();
        List<String> drops = request.changes().stream().filter(c -> c.kind() == TableAlteration.Kind.DROP)
                .map(c -> c.original().getName()).toList();
        if (!drops.isEmpty() && !DatabaseUi.confirmDestructive(project, drops.size() == 1 ? "Drop column" : "Drop columns",
                "Apply " + request.changes().size() + " pending change(s) to '" + tableMetadata.getName() + "'?\n\n"
                        + "Drop: " + String.join(", ", drops) + "\nThese columns and all their values will be permanently deleted.",
                request.changes().size() == 1 ? "Drop column" : "Apply all changes")) return;
        cancelPreview(); applying = true; editor.setBusy(true); editor.status("Updating table…", DatabaseUi.Tone.BUSY);
        mutationScope = DatabaseTaskService.getInstance().newScope();
        mutationScope.submitMutation(() -> {
            try (Connection conn = DatabaseConnectionManager.getInstance().openConnection(config)) {
                DdlService.getInstance().applyAlterBatch(conn, config, databaseName, request, prepared.statements());
                String name = request.targetTableName();
                List<ColumnMetadata> columns;
                try { columns = MetadataService.getInstance().getColumns(conn, config, databaseName, name); }
                catch (Exception refresh) { columns = List.of(); }
                finish(name, columns);
            } catch (Exception error) {
                // H2/HSQLDB/MySQL can commit DDL incrementally. Never replay completed steps blindly.
                List<TableAlteration> completed = error instanceof DdlService.BatchFailure failure ? failure.completed() : List.of();
                List<ColumnMetadata> columns = null;
                try (Connection conn = DatabaseConnectionManager.getInstance().openConnection(config)) {
                    if (!request.targetTableName().equals(request.tableName())) {
                        List<TableMetadata> tables = MetadataService.getInstance().getTables(conn, config, databaseName);
                        boolean oldExists = tables.stream().anyMatch(t -> t.getName().equals(request.tableName()));
                        boolean renamedExists = tables.stream().anyMatch(t -> t.getName().equals(request.targetTableName()));
                        if (!oldExists && renamedExists) {
                            finish(request.targetTableName(), MetadataService.getInstance().getColumns(conn, config, databaseName, request.targetTableName()));
                            return;
                        }
                    }
                    columns = MetadataService.getInstance().getColumns(conn, config, databaseName, tableMetadata.getName());
                } catch (Exception refresh) { error.addSuppressed(refresh); }
                List<ColumnMetadata> loaded = columns;
                DatabaseChangeListener.publish(DatabaseChange.table(config.getId(), databaseName, request.tableName()));
                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    applying = false; editor.setBusy(false);
                    if (loaded != null) {
                        tableMetadata.setColumns(loaded);
                        editor.recoverBatch(loaded, completed, request.tableName());
                    } else editor.requireReload();
                    editor.status("Batch stopped · Review remaining changes", DatabaseUi.Tone.ERROR);
                    Messages.showErrorDialog(project, message(error) + "\n\n"
                            + (loaded == null ? "Metadata could not be refreshed. Reload columns before retrying."
                            : "The current schema has been refreshed. Completed changes are no longer pending. Review the remaining batch before retrying."), "Database Operation Failed");
                });
            }
        });
    }
    private void finish(String name, List<ColumnMetadata> loaded) {
        SwingUtilities.invokeLater(() -> {
            // A completed write must invalidate other views even if this dialog has closed.
            DatabaseChangeListener.publish(DatabaseChange.table(config.getId(), databaseName, null));
            if (isDisposed()) return;
            tableMetadata.setColumns(loaded);
            if (!name.equals(tableMetadata.getName()) && project != null && !project.isDisposed()) {
                TableMetadata renamed = new TableMetadata(tableMetadata.getCatalog(), tableMetadata.getSchema(), name, tableMetadata.getType());
                renamed.setColumns(loaded);
                project.getMessageBus().syncPublisher(TableRenameListener.TOPIC).tableRenamed(config, databaseName, tableMetadata.getName(), renamed);
            }
            close(OK_EXIT_CODE);
        });
    }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    @Override protected void dispose() {
        cancelPreview();
        if (mutationScope != null) {
            DatabaseTaskScope old = mutationScope; old.cancelPending();
            ApplicationManager.getApplication().executeOnPooledThread(old::close);
        }
        super.dispose();
    }
}
