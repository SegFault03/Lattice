package com.segfault03.ideadb.dialog;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.*;
import com.segfault03.ideadb.ui.DatabaseUi;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.sql.Connection;
import java.util.List;

/** Keeps the complete definition editable until background creation succeeds. */
public class CreateTableDialog extends DialogWrapper {
    private final Project project;
    private final ConnectionConfig config;
    private final String databaseName;
    private CreateTablePanel editor;
    private CreateTablePanel.Prepared created;
    private DatabaseTaskScope mutationScope;
    private boolean creating;

    public CreateTableDialog(@Nullable Project project, ConnectionConfig config, String databaseName) {
        super(project, true); this.project=project;this.config=config;this.databaseName=databaseName;
        setTitle("Create table in " + databaseName);setOKButtonText("Create table");setResizable(true);init();
        editor.createButton().addActionListener(e -> doOKAction());editor.cancelButton().addActionListener(e -> doCancelAction());
        if(getRootPane()!=null)getRootPane().setDefaultButton(editor.createButton());
    }
    public String getTableName() { return created == null ? editor.tableNameField().getText().trim() : created.definition().name(); }
    public List<ColumnDefinition> getColumns() {
        var prepared = created == null ? editor.prepared() : created;
        return prepared == null ? List.of() : prepared.definition().definitions();
    }
    @Override protected JComponent createCenterPanel() { editor=new CreateTablePanel(config,databaseName);return editor; }
    @Override protected javax.swing.border.Border createContentPaneBorder() {
        return com.intellij.util.ui.JBUI.Borders.empty(6, 12, 10, 12);
    }
    @Override protected Action[] createActions() { return new Action[0]; }
    @Override protected @Nullable JComponent createSouthPanel() { return null; }
    @Override protected @Nullable String getDimensionServiceKey() { return "Lattice.CreateTableDialog.v3"; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return editor.tableNameField(); }
    @Override public void doCancelAction() {
        if(creating)return;
        if(editor.dirty() && Messages.showYesNoDialog(project,"Discard the new table definition?","Discard changes",
                "Discard","Keep editing",Messages.getQuestionIcon())!=Messages.YES)return;
        super.doCancelAction();
    }
    @Override protected void doOKAction() {
        CreateTablePanel.Prepared request=editor.prepared();
        if(creating || request==null || isDisposed())return;
        creating=true;editor.setBusy(true);editor.status("Creating table…",DatabaseUi.Tone.BUSY);
        mutationScope=DatabaseTaskService.getInstance().newScope();
        mutationScope.submitMutation(() -> {
            try(Connection conn=DatabaseConnectionManager.getInstance().openConnection(config)) {
                var definition=request.definition();
                String sql=DdlService.getInstance().buildCreateTableSql(config,databaseName,definition.name(),definition.definitions());
                if(!sql.equals(request.sql()))throw new IllegalStateException("The SQL preview changed. Review the definition before creating the table.");
                DdlService.getInstance().createTable(conn,config,databaseName,definition.name(),definition.definitions());
                SwingUtilities.invokeLater(() -> { if(!isDisposed()){created=request;close(OK_EXIT_CODE);} });
            } catch(Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if(isDisposed())return;
                    creating=false;editor.setBusy(false);editor.status("Could not create table · Review the definition and retry",DatabaseUi.Tone.ERROR);
                    Messages.showErrorDialog(project,(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage())
                            + "\n\nYour table definition has been kept for review.","Create table failed");
                });
            }
        });
    }
    @Override protected void dispose() {
        if(mutationScope!=null) {
            var old=mutationScope;old.cancelPending();ApplicationManager.getApplication().executeOnPooledThread(old::close);
        }
        super.dispose();
    }
}
