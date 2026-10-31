package com.segfault03.ideadb.sql.run;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** What the "Lattice SQL" editor actions share: availability, the target statement and the parameter values. */
final class SqlRunActionSupport {
    static final String BIND_FIRST = "Bind this file to a connection first";

    interface ConsoleOpener { void open(@NotNull ConnectionConfig config, @Nullable String db, @NotNull String sql); }

    private static volatile BiFunction<SqlRunTarget, Map<String, SqlParameterValue>, Map<String, SqlParameterValue>> valuesProvider;
    private static volatile ConsoleOpener consoleOpener;

    private SqlRunActionSupport() {}

    /** Hidden outside Lattice SQL and where the IDE's own database plugin is active; disabled until the file has a connection. */
    static void update(@NotNull AnActionEvent event) {
        var presentation = event.getPresentation();
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (UltimateDatabasePlugin.isActive() || project == null || editor == null || file == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        SqlRunTarget.Resolution resolution = SqlRunTarget.resolve(project, editor, file);
        if (resolution == null) {
            presentation.setEnabledAndVisible(false);
        } else if (resolution instanceof SqlRunTarget.Found found && found.target().connection() == null) {
            presentation.setVisible(true);
            presentation.setEnabled(false);
            presentation.setDescription(BIND_FIRST);
        } else {
            presentation.setEnabledAndVisible(true);
        }
    }

    /** The statement to work on, or null after telling the user why there is none. */
    static @Nullable SqlRunTarget targetOrShowProblem(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        return project == null || editor == null || file == null ? null : targetOrShowProblem(project, editor, file);
    }

    static @Nullable SqlRunTarget targetOrShowProblem(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        SqlRunTarget.Resolution resolution = ReadAction.compute(() -> SqlRunTarget.resolve(project, editor, file));
        if (resolution instanceof SqlRunTarget.Problem problem) {
            HintManager.getInstance().showErrorHint(editor, problem.message());
            return null;
        }
        if (!(resolution instanceof SqlRunTarget.Found found)) return null;
        SqlRunTarget target = found.target();
        if (target.parameters().error() != null) {
            HintManager.getInstance().showErrorHint(editor, target.parameters().error());
            return null;
        }
        return target;
    }

    /** The value of each parameter; empty without asking when there are none, null when the user cancels. */
    static @Nullable Map<String, SqlParameterValue> askValues(@NotNull Project project, @NotNull SqlRunTarget target) {
        if (target.parameters().keys().isEmpty()) return Map.of();
        SqlRunState remembered = SqlRunState.getInstance(project);
        String fileUrl = target.file().getUrl();
        Map<String, SqlParameterValue> initial = new LinkedHashMap<>(remembered.values(fileUrl));
        initial.keySet().retainAll(target.parameters().keys());
        var provider = valuesProvider;
        Map<String, SqlParameterValue> values = provider != null
                ? provider.apply(target, initial)
                : new SqlParameterDialog(project, target, initial).showAndGetValues();
        if (values != null) remembered.remember(fileUrl, values);
        return values;
    }

    static void openConsole(@NotNull Project project, @NotNull ConnectionConfig config, @NotNull String sql) {
        var opener = consoleOpener;
        if (opener != null) opener.open(config, config.getDatabaseName(), sql);
        else DatabaseEditorManager.getInstance(project).openConsole(config, config.getDatabaseName(), sql);
    }

    @TestOnly
    static void setValuesProviderForTests(@NotNull BiFunction<SqlRunTarget, Map<String, SqlParameterValue>, Map<String, SqlParameterValue>> provider, @NotNull Disposable parent) {
        valuesProvider = provider;
        Disposer.register(parent, () -> valuesProvider = null);
    }

    /** Takes the place of opening the console tab, which needs a real editor window. */
    @TestOnly
    static void setConsoleOpenerForTests(@NotNull ConsoleOpener opener, @NotNull Disposable parent) {
        consoleOpener = opener;
        Disposer.register(parent, () -> consoleOpener = null);
    }
}
