package com.segfault03.ideadb.sql.card;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.run.SqlStatementKind;
import com.segfault03.ideadb.sql.run.SqlStatementRange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the cards of a file: each statement of a Lattice SQL file, or each Lattice SQL injection elsewhere. Text over
 * several lines is cut per line and trimmed of surrounding whitespace, so a card hugs the SQL instead of the indentation.
 */
public final class SqlCardLocator {
    private SqlCardLocator() {}

    /** Must run in a read action. Cards come back in document order. */
    public static @NotNull List<SqlCard> cards(@NotNull Project project, @NotNull PsiFile hostFile) {
        VirtualFile virtualFile = hostFile.getVirtualFile();
        SqlDialectProfile profile = SqlDialectResolver.resolve(project, virtualFile == null ? null : SqlBindingsState.hostOf(virtualFile));
        CharSequence text = hostFile.getViewProvider().getContents();
        return hostFile.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)
                ? statementCards(text, profile)
                : injectionCards(project, hostFile, text, profile);
    }

    private static List<SqlCard> statementCards(CharSequence text, SqlDialectProfile profile) {
        List<SqlCard> cards = new ArrayList<>();
        for (SqlStatementRange range : SqlStatementRange.inSelection(text, 0, text.length(), profile)) {
            List<TextRange> segments = lines(text, new TextRange(range.start(), range.end()));
            if (segments.isEmpty()) continue;
            String sql = text.subSequence(range.start(), range.end()).toString();
            cards.add(new SqlCard(segments, sql, false, SqlStatementKind.of(sql, profile), 1));
        }
        return cards;
    }

    private static List<SqlCard> injectionCards(Project project, PsiFile hostFile, CharSequence text, SqlDialectProfile profile) {
        InjectedLanguageManager injections = InjectedLanguageManager.getInstance(project);
        Map<PsiFile, List<PsiLanguageInjectionHost.Shred>> shreds = new IdentityHashMap<>();
        PsiTreeUtil.processElements(hostFile, element -> {
            if (element instanceof PsiLanguageInjectionHost host) {
                injections.enumerate(host, (injected, places) -> {
                    if (!injected.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE) || shreds.containsKey(injected)) return;
                    shreds.put(injected, List.copyOf(places));
                });
            }
            return true;
        });
        List<SqlCard> cards = new ArrayList<>();
        shreds.forEach((injected, places) -> {
            List<TextRange> segments = segments(text, places);
            if (segments.isEmpty()) return;
            String sql = injected.getText();
            int count = SqlStatementRange.inSelection(sql, 0, sql.length(), profile).size();
            cards.add(new SqlCard(segments, sql, true, count == 1 ? SqlStatementKind.of(sql, profile) : null, count));
        });
        cards.sort(Comparator.comparingInt(card -> card.range().getStartOffset()));
        return cards;
    }

    /** The non-blank part of each line of {@code range}. */
    static List<TextRange> lines(CharSequence text, TextRange range) {
        List<TextRange> lines = new ArrayList<>();
        int start = range.getStartOffset();
        while (start < range.getEndOffset()) {
            int end = start;
            while (end < range.getEndOffset() && text.charAt(end) != '\n') end++;
            int from = start, to = end;
            while (from < to && Character.isWhitespace(text.charAt(from))) from++;
            while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
            if (from < to) lines.add(new TextRange(from, to));
            start = end + 1;
        }
        return lines;
    }

    /**
     * A one-line literal is covered whole, quotes and template entries included, since its SQL can stop short of the
     * closing quote ({@code "... id = " + id}). A literal over several lines is covered line by line.
     */
    private static List<TextRange> segments(CharSequence text, List<PsiLanguageInjectionHost.Shred> places) {
        List<TextRange> segments = new ArrayList<>();
        Set<PsiLanguageInjectionHost> covered = new HashSet<>();
        for (PsiLanguageInjectionHost.Shred place : places) {
            PsiLanguageInjectionHost host = place.getHost();
            if (host == null) continue;
            TextRange hostRange = host.getTextRange();
            if (!StringUtil.contains(text.subSequence(hostRange.getStartOffset(), hostRange.getEndOffset()), "\n")) {
                if (covered.add(host)) segments.add(hostRange);
            } else {
                segments.addAll(lines(text, place.getRangeInsideHost().shiftRight(hostRange.getStartOffset())));
            }
        }
        segments.sort(Comparator.comparingInt(TextRange::getStartOffset));
        return segments;
    }
}
