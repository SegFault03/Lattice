package com.segfault03.ideadb.sql.validate;

import com.intellij.openapi.util.TextRange;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.completion.SqlTokenizer;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code -- noinspection <ShortName>[,<ShortName>]} comments of a script and what they cover, the platform's
 * comment form for silencing one inspection. A comment on its own line directly above a statement (no blank line
 * between) covers that statement. A comment before the first statement and not directly above it, that is with a blank
 * line before the statement, covers the whole file. {@code ALL} stands for every inspection.
 */
public final class SqlSuppressions {
    private static final Pattern DIRECTIVE = Pattern.compile("^(?:--+|#|//|/\\*+)[ \\t]*noinspection[ \\t]+([\\w.-]+(?:[ \\t]*,[ \\t]*[\\w.-]+)*)");

    /** A suppression comment: the whole comment, the names it lists and where that list sits in the text. */
    public record Directive(int start, int end, int listStart, int listEnd, @NotNull List<String> ids, boolean block) {
        boolean covers(String toolId) {
            for (String id : ids) if (id.equals(toolId) || id.equalsIgnoreCase("ALL")) return true;
            return false;
        }
    }

    /** One statement: where it runs, where its first word starts, and the directives directly above it. */
    public record Statement(int from, int to, int firstWord, @NotNull List<Directive> above) {}

    private final CharSequence text;
    private final List<Statement> statements = new ArrayList<>();
    private final List<Directive> file = new ArrayList<>();

    private SqlSuppressions(CharSequence text) { this.text = text; }

    public static @NotNull SqlSuppressions of(@NotNull CharSequence text, @NotNull SqlDialectProfile profile) {
        SqlSuppressions result = new SqlSuppressions(text);
        List<SqlToken> tokens = SqlTokenizer.tokenize(text, profile);
        int from = 0;
        List<SqlToken> comments = new ArrayList<>();
        SqlToken first = null;
        boolean firstStatement = true;
        for (SqlToken token : tokens) {
            boolean comment = LatticeSqlTokens.COMMENTS.contains(token.type());
            boolean end = token.type() == LatticeSqlTokens.PUNCTUATION && text.charAt(token.start()) == ';';
            if (comment) { if (first == null) comments.add(token); continue; }
            if (!end) { if (first == null) first = token; continue; }
            if (first != null) { result.statement(from, token.end(), first, comments, firstStatement); firstStatement = false; }
            from = token.end();
            comments = new ArrayList<>();
            first = null;
        }
        if (first != null) result.statement(from, text.length(), first, comments, firstStatement);
        else if (firstStatement) result.file.addAll(result.directives(comments));
        return result;
    }

    private void statement(int from, int to, SqlToken first, List<SqlToken> comments, boolean firstStatement) {
        // the comments directly above the statement: each on its own line, with no blank line down to the statement
        int attached = comments.size();
        int boundary = first.start();
        while (attached > 0) {
            SqlToken comment = comments.get(attached - 1);
            if (!ownLine(comment.start()) || newlines(comment.end(), boundary) > 1) break;
            boundary = comment.start();
            attached--;
        }
        List<Directive> above = directives(comments.subList(attached, comments.size()));
        if (firstStatement) file.addAll(directives(comments.subList(0, attached)));
        statements.add(new Statement(from, to, first.start(), above));
    }

    private List<Directive> directives(List<SqlToken> comments) {
        List<Directive> found = new ArrayList<>();
        for (SqlToken comment : comments) {
            if (!ownLine(comment.start())) continue;
            String body = text.subSequence(comment.start(), comment.end()).toString();
            Matcher matcher = DIRECTIVE.matcher(body);
            if (!matcher.find()) continue;
            List<String> ids = new ArrayList<>();
            for (String id : matcher.group(1).split("\\s*,\\s*")) ids.add(id.trim());
            found.add(new Directive(comment.start(), comment.end(), comment.start() + matcher.start(1), comment.start() + matcher.end(1), ids,
                    body.startsWith("/*")));
        }
        return found;
    }

    private boolean ownLine(int offset) {
        for (int i = offset - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') return true;
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    private int newlines(int from, int to) {
        int count = 0;
        for (int i = from; i < to && i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    // ---- questions -------------------------------------------------------------------------------------------------

    public boolean suppressedInFile(@NotNull String toolId) {
        for (Directive directive : file) if (directive.covers(toolId)) return true;
        return false;
    }

    /** Whether the inspection is silenced at an offset: by the file's comment or by the one above the statement holding it. */
    public boolean isSuppressed(int offset, @NotNull String toolId) {
        if (suppressedInFile(toolId)) return true;
        Statement statement = statementAt(offset);
        if (statement == null) return false;
        for (Directive directive : statement.above()) if (directive.covers(toolId)) return true;
        return false;
    }

    public @Nullable Statement statementAt(int offset) {
        for (Statement statement : statements) if (offset >= statement.from() && offset <= statement.to()) return statement;
        return statements.isEmpty() ? null : offset > statements.get(statements.size() - 1).to() ? statements.get(statements.size() - 1) : null;
    }

    // ---- the edits the suppress fixes make -------------------------------------------------------------------------

    /** The change that silences an inspection for the statement at an offset, or null when there is none. */
    public @Nullable Edit forStatement(int offset, @NotNull String toolId) {
        Statement statement = statementAt(offset);
        if (statement == null) return null;
        for (Directive directive : statement.above()) {
            if (!directive.block()) return append(directive, toolId);
        }
        int word = statement.firstWord();
        int lineStart = word;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') lineStart--;
        String indent = text.subSequence(lineStart, word).toString();
        if (indent.isBlank()) return new Edit(new TextRange(lineStart, lineStart), indent + comment(toolId) + "\n");
        return new Edit(new TextRange(word, word), "\n" + comment(toolId) + "\n"); // the statement shares its line with another
    }

    /** The change that silences an inspection for the whole file. */
    public @NotNull Edit forFile(@NotNull String toolId) {
        if (!file.isEmpty()) {
            for (Directive directive : file) if (!directive.block()) return append(directive, toolId);
        }
        return new Edit(new TextRange(0, 0), comment(toolId) + "\n\n");
    }

    private static Edit append(Directive directive, String toolId) {
        if (directive.covers(toolId)) return new Edit(new TextRange(directive.end(), directive.end()), "");
        return new Edit(new TextRange(directive.listEnd(), directive.listEnd()), "," + toolId);
    }

    private static String comment(String toolId) { return "-- noinspection " + toolId; }

    /** Text to put in place of a range. */
    public record Edit(@NotNull TextRange range, @NotNull String replacement) {}
}
