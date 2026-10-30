package com.segfault03.ideadb.sql.inject;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides from its text alone whether a string is a SQL statement. Deliberately strict: a string must have the
 * structure of a statement and, for SELECT and DELETE, a second sign that it is not prose ("Select an item from the
 * list" is a valid SELECT to a parser). Missing a statement costs nothing, since the user can mark it with
 * {@code // language=SQL}; wrongly injecting prose colors and "completes" ordinary text.
 */
public final class SqlStringHeuristic {
    private static final String ID = "(?:[\\w$]+|\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|\\?)";
    private static final String NAME = ID + "(?:\\s*\\.\\s*" + ID + ")*";
    private static final Pattern VERB = Pattern.compile(
            "^(select|insert\\s+into|update|delete\\s+from|with|create|alter\\s+table|drop|truncate|merge\\s+into|explain)\\b");

    private static final Pattern INSERT = Pattern.compile("^insert\\s+into\\s+" + NAME + "\\s*(\\(|values\\b|select\\b|default\\s+values\\b|\\?)");
    private static final Pattern UPDATE = Pattern.compile("^update\\s+" + NAME + "(?:\\s+(?:as\\s+)?" + ID + ")?\\s+set\\s+" + NAME + "\\s*=");
    private static final Pattern DELETE = Pattern.compile(
            "^delete\\s+from\\s+" + NAME + "(?:\\s+(?:as\\s+)?" + ID + ")?(?:\\s+(?:where|using)\\b.*|\\s*;?)$", Pattern.DOTALL);
    private static final Pattern WITH = Pattern.compile(
            "^with\\s+(?:recursive\\s+)?" + ID + "\\s*(?:\\([^)]*\\))?\\s+as\\s*(?:not\\s+materialized\\s*|materialized\\s*)?\\(.*\\bselect\\b.*", Pattern.DOTALL);
    private static final Pattern DDL = Pattern.compile("^(?:create\\s+(?:or\\s+replace\\s+)?(?:(?:global\\s+|local\\s+)?(?:temporary\\s+|temp\\s+)|unique\\s+)?"
            + "(?:table\\s+(?:if\\s+not\\s+exists\\s+)?" + NAME + "\\s*(?:\\(|as\\s+select\\b)"
            + "|view\\s+(?:if\\s+not\\s+exists\\s+)?" + NAME + "\\s+as\\s+select\\b"
            + "|(?:index\\s+(?:if\\s+not\\s+exists\\s+)?" + ID + "\\s+on\\s+" + NAME + "\\s*\\()"
            + "|sequence\\s+" + NAME + "\\b)"
            + "|alter\\s+table\\s+" + NAME + "\\s+(?:add|drop|alter|rename|modify|change|set|owner)\\b"
            + "|drop\\s+(?:table|view|index|sequence)\\s+(?:if\\s+exists\\s+)?" + NAME + "\\s*(?:cascade|restrict)?\\s*;?"
            + "|truncate\\s+(?:table\\s+)?" + NAME + "\\s*;?)", Pattern.DOTALL);
    private static final Pattern EXPLAIN = Pattern.compile("^explain\\s+(?:analyze\\s+)?select\\b.*", Pattern.DOTALL);

    private static final Pattern STRONG = Pattern.compile(
            "\\bwhere\\b|\\bjoin\\b|\\bgroup\\s+by\\b|\\border\\s+by\\b|\\bhaving\\b|\\bunion\\b|\\blimit\\s+[\\d?]|\\bfetch\\s+first\\b"
                    + "|:[a-z_]\\w*|\\$\\d+|@[a-z_]\\w*|\\?");

    private SqlStringHeuristic() {}

    /** Whether text begins with a statement verb, which is all a known SQL-taking call needs to rule out other strings. */
    public static boolean startsLikeStatement(@NotNull String text) {
        return VERB.matcher(strip(text).toLowerCase(Locale.ROOT)).find();
    }

    public static boolean looksLikeSql(@NotNull String text) {
        String body = strip(text);
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.length() < 10 || sentenceEnd(lower)) return false;
        var verb = VERB.matcher(lower);
        if (!verb.find()) return false;
        return switch (verb.group(1).replaceAll("\\s+", " ")) {
            case "select" -> selectShape(lower) && (STRONG.matcher(lower).find() || listIsStrong(lower) || shoutsSql(body));
            case "insert into" -> INSERT.matcher(lower).find();
            case "update" -> UPDATE.matcher(lower).find();
            case "delete from" -> DELETE.matcher(lower).matches() && (lower.contains("where") || lower.contains("using") || lower.endsWith(";") || shoutsSql(body));
            case "with" -> WITH.matcher(lower).matches();
            case "explain" -> EXPLAIN.matcher(lower).matches();
            case "merge into" -> lower.contains(" using ") && lower.contains(" when ");
            default -> DDL.matcher(lower).find();
        };
    }

    // ---- select ------------------------------------------------------------------------------------------------------

    /** select [distinct] LIST from SOURCE, with a list that reads as expressions rather than words. */
    private static boolean selectShape(String lower) {
        int i = "select".length();
        while (i < lower.length() && Character.isWhitespace(lower.charAt(i))) i++;
        for (String modifier : new String[] {"distinct ", "all "}) {
            if (lower.startsWith(modifier, i)) { i += modifier.length(); while (i < lower.length() && lower.charAt(i) == ' ') i++; }
        }
        int depth = 0, listStart = i, from = -1;
        for (; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '\'' || c == '"' || c == '`') { i = skipQuoted(lower, i); if (i < 0) return false; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && lower.startsWith("from", i) && (i == 0 || !isWord(lower.charAt(i - 1)))
                    && i + 4 < lower.length() && !isWord(lower.charAt(i + 4))) { from = i; break; }
        }
        if (from < 0 || depth != 0) return false;
        String list = lower.substring(listStart, from).strip();
        if (list.isEmpty() || !itemsAreExpressions(list)) return false;
        String source = lower.substring(from + 4).strip();
        return !source.isEmpty() && (source.startsWith("(") || source.matches("(?s)" + NAME + ".*"));
    }

    /** Every top-level comma-separated item is an expression with at most an alias: no run of three bare words. */
    private static boolean itemsAreExpressions(String list) {
        int depth = 0, words = 0;
        boolean inWord = false;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '\'' || c == '"' || c == '`') { i = skipQuoted(list, i); if (i < 0) return false; if (depth == 0) { words++; inWord = false; } continue; }
            if (c == '(') { depth++; inWord = false; continue; }
            if (c == ')') { depth--; continue; }
            if (depth > 0) continue;
            if (c == ',') { words = 0; inWord = false; continue; }
            if (isWord(c) || c == '.' || c == '*' || c == '?') {
                if (!inWord) { words++; inWord = true; }
                if (words > 3) return false;
            } else inWord = false;
        }
        return true;
    }

    /** A select list that could only be written as SQL: *, several items, a call, or a qualified name. */
    private static boolean listIsStrong(String lower) {
        int from = lower.indexOf(" from ");
        String list = from < 0 ? "" : lower.substring("select".length(), from);
        return list.contains("*") || list.contains(",") || list.contains("(") || list.contains(".");
    }

    private static boolean shoutsSql(String original) {
        return original.contains("SELECT") && original.contains("FROM") || original.contains("DELETE FROM") || original.contains("INSERT INTO");
    }

    // ---- text handling -----------------------------------------------------------------------------------------------

    private static boolean sentenceEnd(String lower) {
        char last = lower.charAt(lower.length() - 1);
        return last == '.' || last == '!' || last == ':' || last == '…';
    }

    private static boolean isWord(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    private static int skipQuoted(String text, int start) {
        char quote = text.charAt(start);
        for (int i = start + 1; i < text.length(); i++) {
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) { i++; continue; }
                return i;
            }
        }
        return -1;
    }

    /** Drops leading whitespace and comments. */
    private static String strip(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) i++;
            else if (text.startsWith("--", i)) { int end = text.indexOf('\n', i); i = end < 0 ? text.length() : end + 1; }
            else if (text.startsWith("/*", i)) { int end = text.indexOf("*/", i + 2); i = end < 0 ? text.length() : end + 2; }
            else break;
        }
        return text.substring(i).stripTrailing();
    }
}
