package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.DryRunResult;
import com.segfault03.ideadb.service.DryRunService;
import com.segfault03.ideadb.service.QueryExecution;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** What Explain Statement and Run and Roll Back share: checks on the EDT, then the JDBC work in a cancellable background task. */
final class DryRunRunner {
    static final String TITLE = "Lattice SQL";
    static final String ALREADY_RUNNING = "A dry run is already running for this connection.";

    enum Mode {
        EXPLAIN("Lattice: Explain"), ROLL_BACK("Lattice: Run and Roll Back");
        final String taskTitle;
        Mode(String taskTitle) { this.taskTitle = taskTitle; }
    }

    private DryRunRunner() {}

    /** The whole action, from the caret to the tab; call on the EDT. */
    static void start(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file, @NotNull Mode mode) {
        SqlRunTarget target = SqlRunActionSupport.targetOrShowProblem(project, editor, file);
        if (target == null || target.connection() == null) return;
        ConnectionConfig config = target.connection();
        SqlStatementKind kind = SqlStatementKind.of(target.sql(), target.profile());
        String refusal = mode == Mode.EXPLAIN ? SqlStatementKind.explainRefusal(kind) : SqlStatementKind.rollbackRefusal(kind);
        if (refusal != null) {
            Messages.showErrorDialog(project, refusal, TITLE);
            return;
        }
        if (isRunning(project, config)) {
            Messages.showErrorDialog(project, ALREADY_RUNNING, TITLE);
            return;
        }
        Map<String, SqlParameterValue> values = SqlRunActionSupport.askValues(project, target);
        if (values == null) return;
        if (mode == Mode.ROLL_BACK && !RollbackConfirmation.confirm(project, config, kind)) return;
        launch(project, target, kind, mode, values);
    }

    private static boolean isRunning(Project project, ConnectionConfig config) {
        DryRunPanel panel = DryRunVirtualFile.existingPanel(project, config);
        return panel != null && panel.isRunning();
    }

    /** The same statement with the same values, as Re-run does it; the confirmation still applies. */
    private static void rerun(Project project, SqlRunTarget target, SqlStatementKind kind, Mode mode, Map<String, SqlParameterValue> values) {
        ConnectionConfig config = target.connection();
        if (config == null || isRunning(project, config)) return;
        if (mode == Mode.ROLL_BACK && !RollbackConfirmation.confirm(project, config, kind)) return;
        launch(project, target, kind, mode, values);
    }

    private static void launch(Project project, SqlRunTarget target, SqlStatementKind kind, Mode mode, Map<String, SqlParameterValue> values) {
        ConnectionConfig config = target.connection();
        SqlLiteralRenderer.JdbcStatement statement = SqlLiteralRenderer.toJdbc(target.sql(), target.parameters(), values);
        String literalSql = SqlLiteralRenderer.render(target.sql(), target.parameters(), values, config.getType());
        List<SqlStatementKind.QualifiedName> tables = mode == Mode.ROLL_BACK ? SqlStatementKind.targetTableNames(target.sql(), target.profile()) : List.of();
        List<String> functions = mode == Mode.ROLL_BACK ? SqlStatementKind.userFunctionCalls(target.sql(), target.profile()) : List.of();

        DryRunPanel panel = DryRunVirtualFile.open(project, config);
        panel.showRunning(target, kind == SqlStatementKind.DML ? "DML" : "Query", values);
        panel.setRerunHandler(() -> rerun(project, target, kind, mode, values));
        new Task.Backgroundable(project, mode.taskTitle, true) {
            private volatile DryRunResult result = new DryRunResult.Failed("The dry run did not finish.");

            @Override public void run(@NotNull ProgressIndicator indicator) {
                QueryExecution execution = new QueryExecution();
                try {
                    result = execute(indicator, execution, () -> mode == Mode.EXPLAIN
                            ? DryRunService.getInstance().explain(config, statement, literalSql, execution)
                            : DryRunService.getInstance().runAndRollBack(config, kind, statement, tables, functions, execution));
                } finally {
                    ApplicationManager.getApplication().invokeLater(() -> panel.show(result), ModalityState.any(), project.getDisposed());
                }
            }
        }.queue();
    }

    /** Runs the call on another thread so that cancelling the progress can reach the JDBC statement while it is in flight. */
    private static DryRunResult execute(ProgressIndicator indicator, QueryExecution execution, java.util.function.Supplier<DryRunResult> call) {
        CompletableFuture<DryRunResult> future = CompletableFuture.supplyAsync(call, AppExecutorUtil.getAppExecutorService());
        boolean cancelSent = false;
        while (true) {
            try {
                return future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillRunning) {
                if (!cancelSent && indicator.isCanceled()) {
                    cancelSent = true;
                    execution.cancel();
                }
            } catch (InterruptedException interrupted) {
                execution.cancel();
                Thread.currentThread().interrupt();
                return new DryRunResult.Cancelled(false);
            } catch (java.util.concurrent.ExecutionException failed) {
                Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
                return new DryRunResult.Failed(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
            }
        }
    }
}
