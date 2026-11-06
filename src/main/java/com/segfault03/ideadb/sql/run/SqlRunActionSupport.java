package com.segfault03.ideadb.sql.run;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Disposer;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.card.SqlCard;
import com.segfault03.ideadb.sql.card.SqlCardAnchor;
import com.segfault03.ideadb.model.DatabaseType;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.segfault03.ideadb.sql.card.SqlCardDataKeys;
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
import java.util.function.Consumer;

/** What the "Lattice SQL" editor actions share: availability, the target statement and the parameter values. */
final class SqlRunActionSupport {
    static final String BIND_FIRST = "Bind this file to a connection first";
    static final String RUN_REFUSAL = "Run is for queries such as SELECT. Use Run and Roll Back to try an INSERT, UPDATE, DELETE or MERGE.";

    interface ConsoleOpener { void open(@NotNull ConnectionConfig config, @Nullable String db, @NotNull String sql); }

    private static volatile BiFunction<SqlRunTarget, Map<String, SqlParameterValue>, Map<String, SqlParameterValue>> valuesProvider;
    private static volatile ConsoleOpener consoleOpener;
    private static volatile ConsoleOpener consoleRunner;

    private SqlRunActionSupport() {}

    /** What a card toolbar action is for, which decides the cards it refuses. */
    enum CardUse { CONSOLE, EXPLAIN, ROLLBACK, RUN }

    /**
     * Hidden outside Lattice SQL and where the IDE's own database plugin is active; disabled until the file has a
     * connection. On a card the action is always shown, and disabled with the reason when the card does not suit it.
     */
    static void update(@NotNull AnActionEvent event, @NotNull CardUse use) {
        var presentation = event.getPresentation();
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (UltimateDatabasePlugin.isActive() || project == null || editor == null || file == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        SqlCard card = event.getData(SqlCardDataKeys.SQL_CARD);
        if (card != null && !shownOnCard(card, use)) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        if (card != null) {
            String refusal = cardRefusal(card, use, isBound(project, file));
            presentation.setVisible(true);
            presentation.setEnabled(refusal == null);
            presentation.setIcon(switch (use) {
                case ROLLBACK, RUN -> AllIcons.Actions.Execute;
                case CONSOLE -> AllIcons.Debugger.Console;
                case EXPLAIN -> AllIcons.Actions.Preview;
            });
            if (refusal != null) presentation.setDescription(refusal);
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

    /** A query card offers Run in place of Run and Roll Back; every other card the other way round. */
    private static boolean shownOnCard(SqlCard card, CardUse use) {
        boolean query = card.kind() == SqlStatementKind.QUERY;
        return switch (use) {
            case RUN -> query;
            case ROLLBACK -> !query;
            case CONSOLE, EXPLAIN -> true;
        };
    }

    /** Why a card does not suit {@code use}, or null when it does. */
    static @Nullable String cardRefusal(@NotNull SqlCard card, @NotNull CardUse use, boolean bound) {
        if (!bound) return BIND_FIRST;
        if (use == CardUse.CONSOLE) return null;
        if (card.statementCount() != 1 || card.kind() == null) return SqlRunTarget.SELECT_SINGLE_STATEMENT;
        return switch (use) {
            case EXPLAIN -> SqlStatementKind.explainRefusal(card.kind());
            case ROLLBACK -> SqlStatementKind.rollbackRefusal(card.kind());
            case RUN -> card.kind() == SqlStatementKind.QUERY ? null : RUN_REFUSAL;
            case CONSOLE -> null;
        };
    }

    private static boolean isBound(Project project, PsiFile file) {
        VirtualFile virtualFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(file).getVirtualFile();
        return virtualFile != null && SqlDialectResolver.connection(project, SqlBindingsState.hostOf(virtualFile)) != null;
    }

    /** The statement to work on, or null after telling the user why there is none. */
    static @Nullable SqlRunTarget targetOrShowProblem(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        return project == null || editor == null || file == null ? null : targetOrShowProblem(project, editor, file);
    }

    static @Nullable SqlRunTarget targetOrShowProblem(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        return targetOrShowProblem(project, editor, file, null, CardUse.CONSOLE);
    }

    /** With a card, its SQL after checking the card suits {@code use}; without one, the caret's statement. */
    static @Nullable SqlRunTarget targetOrShowProblem(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file,
                                                     @Nullable SqlCard card, @NotNull CardUse use) {
        if (card != null) {
            String refusal = ReadAction.compute(() -> cardRefusal(card, use, isBound(project, file)));
            if (refusal != null) {
                HintManager.getInstance().showErrorHint(editor, refusal);
                return null;
            }
        }
        SqlRunTarget.Resolution resolution = ReadAction.compute(() -> card != null
                ? SqlRunTarget.resolve(project, card, InjectedLanguageManager.getInstance(project).getTopLevelFile(file).getVirtualFile())
                : SqlRunTarget.resolve(project, editor, file));
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

    /**
     * Asks for the value of each parameter in the statement's card, lifted over the editor (see {@link SqlParameterPopup}),
     * and hands them to {@code then}; at once and with no values when there are none, and never when the user cancels.
     * Values start from those last entered for the file.
     */
    static void askValues(@NotNull Project project, @NotNull Editor editor, @NotNull SqlRunTarget target, @Nullable SqlCard card,
                          @NotNull String actionText, @NotNull Consumer<Map<String, SqlParameterValue>> then) {
        if (target.parameters().keys().isEmpty()) {
            then.accept(Map.of());
            return;
        }
        SqlRunState remembered = SqlRunState.getInstance(project);
        String fileUrl = target.file().getUrl();
        Map<String, SqlParameterValue> initial = new LinkedHashMap<>(remembered.values(fileUrl));
        initial.keySet().retainAll(target.parameters().keys());
        Consumer<Map<String, SqlParameterValue>> remembering = values -> {
            remembered.remember(fileUrl, values);
            then.accept(values);
        };
        var provider = valuesProvider;
        if (provider != null) {
            Map<String, SqlParameterValue> values = provider.apply(target, initial);
            if (values != null) remembering.accept(values);
            return;
        }
        ConnectionConfig connection = target.connection();
        var form = new SqlParameterForm(target.sql(), target.parameters(),
                connection != null ? connection.getType() : DatabaseType.MYSQL, connection, initial, actionText);
        Editor host = InjectedLanguageUtil.getTopLevelEditor(editor);
        // A card's toolbar is only shown for a card in view; without a card the form goes on the caret's line.
        if (card == null) host.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
        JBPopup popup = SqlParameterPopup.show(host, SqlCardAnchor.of(host, card), form, remembering);
        // The card belongs to its editor tab: switching tabs puts it away at once.
        project.getMessageBus().connect(popup).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(@NotNull FileEditorManagerEvent event) { popup.cancel(); }
        });
    }

    static void openConsole(@NotNull Project project, @NotNull ConnectionConfig config, @NotNull String sql) {
        var opener = consoleOpener;
        if (opener != null) opener.open(config, config.getDatabaseName(), sql);
        else DatabaseEditorManager.getInstance(project).openConsole(config, config.getDatabaseName(), sql);
    }

    /** Opens the console with {@code sql} and runs it there at once. */
    static void runInConsole(@NotNull Project project, @NotNull ConnectionConfig config, @NotNull String sql) {
        var runner = consoleRunner;
        if (runner != null) runner.open(config, config.getDatabaseName(), sql);
        else DatabaseEditorManager.getInstance(project).openConsole(config, config.getDatabaseName(), sql, true);
    }

    /** Takes the place of opening and running in the console tab, which needs a real editor window. */
    @TestOnly
    static void setConsoleRunnerForTests(@NotNull ConsoleOpener runner, @NotNull Disposable parent) {
        consoleRunner = runner;
        Disposer.register(parent, () -> consoleRunner = null);
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
