package com.segfault03.ideadb.sql.run;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The SQL an editor action works on, with the dialect, connection and parameters that go with it. In a {@code .sql} file
 * that is the statement at the caret or the one selected statement; in SQL injected into a Java or Kotlin string it is
 * the whole injected text (dynamic parts already {@code ?}) unless the selection is one statement inside one fragment.
 * {@link #file} is always the file the user is editing, which is also the file that carries the connection binding.
 */
public record SqlRunTarget(@NotNull String sql, @NotNull SqlDialectProfile profile, @Nullable ConnectionConfig connection,
                           @NotNull VirtualFile file, @NotNull SqlParameters.Result parameters) {
    public static final String SELECT_SINGLE_STATEMENT = "Select a single statement";

    public sealed interface Resolution permits Found, Problem {}

    public record Found(@NotNull SqlRunTarget target) implements Resolution {}

    public record Problem(@NotNull String message) implements Resolution {}

    /** Must run in a read action. Null when the caret is not in Lattice SQL. */
    public static @Nullable Resolution resolve(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile psiFile) {
        InjectedLanguageManager injections = InjectedLanguageManager.getInstance(project);
        PsiFile hostFile = injections.getTopLevelFile(psiFile);
        Editor hostEditor = InjectedLanguageUtil.getTopLevelEditor(editor);
        VirtualFile virtualFile = hostFile.getVirtualFile();
        if (virtualFile == null) return null;
        VirtualFile file = SqlBindingsState.hostOf(virtualFile);
        SqlDialectProfile profile = SqlDialectResolver.resolve(project, file);
        ConnectionConfig connection = SqlDialectResolver.connection(project, file);

        SelectionModel selection = hostEditor.getSelectionModel();
        boolean hasSelection = selection.hasSelection();
        int caret = hostEditor.getCaretModel().getOffset();
        int selectionStart = hasSelection ? selection.getSelectionStart() : caret;
        int selectionEnd = hasSelection ? selection.getSelectionEnd() : caret;

        if (hostFile.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)) {
            CharSequence text = hostEditor.getDocument().getCharsSequence();
            return resolution(text, statement(text, hasSelection, caret, selectionStart, selectionEnd, profile), profile, connection, file, false);
        }

        PsiFile injected = injectedFileAt(injections, hostFile, selectionStart);
        if (injected == null && hasSelection) injected = injectedFileAt(injections, hostFile, caret);
        if (injected == null) return null;
        CharSequence text = injected.getText();
        SqlStatementRange range = null;
        if (hasSelection) {
            int[] mapped = selectionWithinOneFragment(injected, selectionStart, selectionEnd);
            if (mapped != null) range = singleStatement(text, mapped[0], mapped[1], profile);
            if (mapped != null && range == null) return new Problem(SELECT_SINGLE_STATEMENT);
        }
        return resolution(text, range != null ? range : new SqlStatementRange(0, text.length()), profile, connection, file, true);
    }

    private static @Nullable SqlStatementRange statement(CharSequence text, boolean hasSelection, int caret, int start, int end, SqlDialectProfile profile) {
        return hasSelection ? singleStatement(text, start, end, profile) : SqlStatementRange.atCaret(text, caret, profile);
    }

    private static @Nullable SqlStatementRange singleStatement(CharSequence text, int start, int end, SqlDialectProfile profile) {
        List<SqlStatementRange> ranges = SqlStatementRange.inSelection(text, start, end, profile);
        return ranges.size() == 1 ? ranges.get(0) : null;
    }

    private static @NotNull Resolution resolution(CharSequence text, @Nullable SqlStatementRange range, SqlDialectProfile profile,
                                                  @Nullable ConnectionConfig connection, VirtualFile file, boolean jdbcHost) {
        if (range == null) return new Problem(SELECT_SINGLE_STATEMENT);
        String sql = text.subSequence(range.start(), range.end()).toString();
        return new Found(new SqlRunTarget(sql, profile, connection, file, SqlParameters.of(sql, profile, jdbcHost)));
    }

    /** The injected Lattice SQL file at a host offset; a caret just past the last character of a string still counts. */
    private static @Nullable PsiFile injectedFileAt(InjectedLanguageManager injections, PsiFile hostFile, int offset) {
        for (int candidate : new int[] {offset, offset - 1}) {
            if (candidate < 0) continue;
            PsiElement element = injections.findInjectedElementAt(hostFile, candidate);
            PsiFile file = element == null ? null : element.getContainingFile();
            if (file != null && file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)) return file;
        }
        return null;
    }

    /** The selection as offsets into the injected text, or null when it spans more than one fragment of the injection. */
    private static int @Nullable [] selectionWithinOneFragment(PsiFile injected, int hostStart, int hostEnd) {
        var document = PsiDocumentManager.getInstance(injected.getProject()).getDocument(injected);
        List<PsiLanguageInjectionHost.Shred> shreds = InjectedLanguageUtil.getShreds(injected);
        if (!(document instanceof DocumentWindow window) || shreds == null) return null;
        for (PsiLanguageInjectionHost.Shred shred : shreds) {
            PsiLanguageInjectionHost host = shred.getHost();
            if (host == null) continue;
            int from = host.getTextRange().getStartOffset() + shred.getRangeInsideHost().getStartOffset();
            TextRange fragment = TextRange.from(from, shred.getRangeInsideHost().getLength());
            if (fragment.getStartOffset() <= hostStart && hostEnd <= fragment.getEndOffset()) {
                return new int[] {window.hostToInjected(hostStart), window.hostToInjected(hostEnd)};
            }
        }
        return null;
    }
}
