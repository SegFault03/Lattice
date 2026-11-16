package com.segfault03.ideadb.dialog;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;

/**
 * Execute SQL Script: choose a .sql file, see it checked offline and previewed, then run it statement by statement
 * against a connection and database, with each statement's outcome in the run log.
 */
public final class SqlScriptDialog extends DialogWrapper {
    private final SqlScriptPanel panel;
    private final Action copyLog = new DialogWrapperAction("Copy Log") {
        @Override protected void doAction(ActionEvent event) { CopyPasteManager.getInstance().setContents(new StringSelection(panel.logText())); }
    };
    private final Action recheck = new DialogWrapperAction("Re-check") {
        @Override protected void doAction(ActionEvent event) { panel.check(); }
    };

    public SqlScriptDialog(@Nullable Project project, @NotNull ConnectionConfig config, @Nullable String namespace) {
        super(project, true);
        panel = new SqlScriptPanel(project, config, namespace, this::updateActions);
        Disposer.register(getDisposable(), panel);
        setTitle("Execute SQL Script");
        copyLog.putValue(Action.SMALL_ICON, AllIcons.Actions.Copy);
        recheck.putValue(Action.SMALL_ICON, AllIcons.Actions.Refresh);
        init();
        updateActions();
    }

    /** Opens the dialog for a connection, with unqualified names resolving in {@code namespace}. */
    public static void show(@Nullable Project project, @NotNull ConnectionConfig config, @Nullable String namespace) {
        new SqlScriptDialog(project, config, namespace).show();
    }

    @Override protected @Nullable JComponent createCenterPanel() {
        panel.setPreferredSize(JBUI.size(860, 640));
        return panel;
    }

    @Override public @Nullable JComponent getPreferredFocusedComponent() { return panel.path.getTextField(); }
    @Override protected @Nullable String getDimensionServiceKey() { return "Lattice.SqlScriptDialog"; }
    @Override protected Action @NotNull [] createLeftSideActions() { return new Action[]{copyLog}; }
    @Override protected Action @NotNull [] createActions() { return new Action[]{recheck, getOKAction(), getCancelAction()}; }

    @Override protected void doOKAction() {
        if (panel.phase() == SqlScriptPanel.Phase.RUNNING) { panel.stop(); return; }
        int errors = panel.errorCount();
        if (errors > 0 && !MessageDialogBuilder.yesNo("Run Script with Errors",
                        "The check found " + errors + (errors == 1 ? " error" : " errors") + " in this script. The database may reject those statements, "
                                + "and the check can be wrong about tables the script itself creates.\n\nRun the script anyway?")
                .yesText("Run Anyway").noText("Cancel").icon(Messages.getWarningIcon()).ask(getRootPane())) return;
        panel.execute();
    }

    @Override public void doCancelAction() {
        if (panel.phase() == SqlScriptPanel.Phase.RUNNING) return; // stop the run first; closing must not leave it running unseen
        super.doCancelAction();
    }

    private void updateActions() {
        boolean running = panel.phase() == SqlScriptPanel.Phase.RUNNING;
        var script = panel.script();
        boolean runnable = script != null && !script.statements().isEmpty();
        getOKAction().putValue(Action.NAME, running ? "Stop" : "Execute");
        getOKAction().putValue(Action.SMALL_ICON, running ? Icons.STOP : AllIcons.Actions.Execute);
        getOKAction().setEnabled(running || runnable);
        getCancelAction().putValue(Action.NAME, "Close");
        getCancelAction().setEnabled(!running);
        recheck.setEnabled(!running && script != null);
        copyLog.setEnabled(!running && panel.hasLog());
    }
}
