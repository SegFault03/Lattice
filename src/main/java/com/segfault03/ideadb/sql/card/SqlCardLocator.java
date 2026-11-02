package com.segfault03.ideadb.sql.card;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the cards of a file: each statement of a Lattice SQL file, or each Lattice SQL injection elsewhere. Segments
 * are cut per line and trimmed of surrounding whitespace, so a card hugs the SQL instead of the indentation around it.
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
        Map<PsiFile, List<TextRange>> shreds = new IdentityHashMap<>();
        PsiTreeUtil.processElements(hostFile, element -> {
            if (element instanceof PsiLanguageInjectionHost host) {
                injections.enumerate(host, (injected, places) -> {
                    if (!injected.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE) || shreds.containsKey(injected)) return;
                    List<TextRange> ranges = new ArrayList<>();
                    for (PsiLanguageInjectionHost.Shred place : places) {
                        PsiLanguageInjectionHost shredHost = place.getHost();
                        if (shredHost == null) continue;
                        ranges.add(place.getRangeInsideHost().shiftRight(shredHost.getTextRange().getStartOffset()));
                    }
                    shreds.put(injected, ranges);
                });
            }
            return true;
        });
        List<SqlCard> cards = new ArrayList<>();
        shreds.forEach((injected, ranges) -> {
            List<TextRange> segments = new ArrayList<>();
            for (TextRange range : ranges) segments.addAll(lines(text, range));
            if (segments.isEmpty()) return;
            segments.sort(Comparator.comparingInt(TextRange::getStartOffset));
            if (ranges.size() == 1 && segments.size() == 1) segments.set(0, withQuotes(text, segments.get(0)));
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

    /** A one-line literal's card takes in its quotes when they sit right around it. */
    private static TextRange withQuotes(CharSequence text, TextRange range) {
        int start = range.getStartOffset(), end = range.getEndOffset();
        boolean quoted = start > 0 && end < text.length() && text.charAt(start - 1) == '"' && text.charAt(end) == '"';
        return quoted ? new TextRange(start - 1, end + 1) : range;
    }
}
