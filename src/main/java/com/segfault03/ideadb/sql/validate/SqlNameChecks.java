package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.completion.SqlContext.TableRef;
import com.segfault03.ideadb.sql.completion.SqlStatementAnalyzer;
import com.segfault03.ideadb.sql.completion.SqlToken;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlTableIndex;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Supplier;

/** The checks of one query's tables, aliases and columns against the cached schema (and against the script's own names). */
final class SqlNameChecks {
    private static final Set<String> TRAILING_QUALIFIERS = Set.of("EXCLUDED", "NEW", "OLD", "INSERTED", "DELETED", "VALUES");
    private static final Set<String> LITERAL_WORDS = Set.of("NULL", "TRUE", "FALSE");

    private final SqlScriptFacts script;
    private final SqlStatementContext c;
    private final CharSequence text;
    private final List<SqlToken> s;
    private final int n;
    private final String kind;
    private final SqlDialectProfile profile;
    private final SqlStatementAnalyzer.Structure structure;
    private final int[] segment;
    /** The tables visible from each group and segment of the statement. */
    private final Map<Long, Scope> scopes = new HashMap<>();
    private List<List<Ref>> refsByBlock;
    private int[] cteMarker;
    private boolean[] skip;
    private final Set<String> aliasNames = new HashSet<>();
    private final Set<String> usingColumns = new HashSet<>();
    private final Set<String> ctes = new HashSet<>();
    private final Set<String> knownQualifiers = new HashSet<>();
    private boolean natural;

    SqlNameChecks(SqlScriptFacts script, SqlStatementContext context) {
        this.script = script; this.c = context; this.text = context.text(); this.s = context.s; this.n = context.n; this.kind = context.kind;
        this.profile = context.profile; this.structure = context.structure; this.segment = context.segment;
    }

    void run() {
        collectNames();
        boolean multiTable = multiTableStatement();
        List<Ref> refs = resolveRefs();
        duplicateAliases(refs);
        if (multiTable) return;
        columnUses(refs);
        insertColumns(refs);
    }

    /** UPDATE t1, t2 and DELETE ... USING: tables the structure scan does not list, so names cannot be judged. */
    boolean multiTableStatement() {
        for (int i = 0; i < n; i++) if (c.word(i, "STRAIGHT_JOIN")) return true; // a join the structure scan does not know
        if (kind.equals("DELETE")) for (int i = 0; i < n; i++) if (c.keyword(i, "USING")) return true;
        if (kind.equals("UPDATE")) for (int i = 0; i < n; i++) { if (c.keyword(i, "SET")) break; if (c.punct(i, ',') && structure.blockOf()[i] == 0) return true; }
        return false;
    }

    void collectNames() {
        skip = new boolean[n];
        for (int i = 0; i < n; i++) {
            c.tick();
            // alias definitions
            if (c.ident(i) && (c.keyword(i - 1, "AS") || operandEnd(i - 1))) aliasNames.add(c.name(i).toLowerCase(Locale.ROOT));
            if (c.keyword(i, "NATURAL")) natural = true;
            // CTE names: WITH name [(columns)] AS (  and  , name AS (
            if (c.ident(i) && (c.keyword(i - 1, "WITH") || c.word(i - 1, "RECURSIVE") || c.punct(i - 1, ',') && cteContext(i))) {
                int at = i + 1;
                if (c.punct(at, '(')) at = c.closing(at) + 1;
                if (c.keyword(at, "AS") || c.word(at, "AS")) {
                    ctes.add(c.name(i).toLowerCase(Locale.ROOT));
                    if (c.punct(i + 1, '(')) skipRange(i + 1, c.closing(i + 1));
                }
            }
            // USING (a, b): merged columns
            if (c.keyword(i, "USING") && c.punct(i + 1, '(')) {
                int close = c.closing(i + 1);
                for (int k = i + 2; k < close; k++) if (c.isWord(k)) usingColumns.add(c.name(k).toLowerCase(Locale.ROOT));
                skipRange(i + 1, close);
            }
            // alias column lists  AS t(a, b)   and  index hints  USE INDEX (i)  and  COLUMNS (...)
            if (c.punct(i, '(') && i > 0) {
                boolean afterAlias = c.ident(i - 1) && c.keyword(i - 2, "AS");
                boolean afterHint = (c.keyword(i - 1, "INDEX") || c.keyword(i - 1, "KEY") || c.word(i - 1, "INDEX") || c.word(i - 1, "KEY"))
                        && (c.word(i - 2, "USE") || c.word(i - 2, "FORCE") || c.word(i - 2, "IGNORE") || c.word(i - 2, "JOIN") || c.word(i - 2, "ORDER")
                        || c.word(i - 2, "GROUP"));
                boolean afterColumns = c.word(i - 1, "COLUMNS");
                if (afterAlias || afterHint || afterColumns) skipRange(i, c.closing(i));
            }
            // field names and units
            if (c.punct(i, '(') && i > 0 && c.isWord(i - 1) && SqlStatementContext.FIELD_FUNCTIONS.contains(c.up(i - 1)) && c.in(i + 1)) skip[i + 1] = true;
            if (c.word(i, "COLLATE") && c.in(i + 1)) skip[i + 1] = true;
            if (c.keyword(i, "OF") && c.keyword(i - 1, "UPDATE") || c.word(i, "OF") && (c.word(i - 1, "UPDATE") || c.word(i - 1, "SHARE"))) {
                for (int k = i + 1; k < n && (c.isWord(k) || c.punct(k, ',') || c.punct(k, '.')); k++) skip[k] = true;
            }
            if (c.keyword(i, "OVER") && c.ident(i + 1)) skip[i + 1] = true;
            if (c.in(i + 1) && c.ident(i) && (c.operator(i + 1, "=>") || c.operator(i + 1, ":="))) skip[i] = true;
        }
        // regions that name things the schema cannot judge
        for (int i = 0; i < n; i++) {
            c.tick();
            boolean conflict = c.word(i, "ON") && (c.word(i + 1, "CONFLICT") || c.word(i + 1, "DUPLICATE"));
            if (conflict || c.word(i, "RETURNING") || c.word(i, "WINDOW")) { for (int k = i; k < n; k++) skip[k] = true; break; }
        }
        // table references are judged on their own, and an INSERT's column list is checked against its table
        for (TableRef ref : structure.refs()) {
            for (int k = ref.startIndex(); k < Math.min(ref.endIndex(), n); k++) skip[k] = true;
            if (ref.introducer().equals("INTO") && c.punct(ref.endIndex(), '(') && !c.keyword(ref.endIndex() + 1, "SELECT")) {
                skipRange(ref.endIndex(), c.closing(ref.endIndex()));
            }
        }
        // known qualifiers
        for (TableRef ref : structure.refs()) {
            if (ref.exposedName() != null) knownQualifiers.add(ref.exposedName().toLowerCase(Locale.ROOT));
            if (ref.name() != null) knownQualifiers.add(ref.name().toLowerCase(Locale.ROOT));
        }
        knownQualifiers.addAll(ctes);
        knownQualifiers.addAll(aliasNames);
    }

    boolean cteContext(int i) {
        if (cteMarker == null) {
            cteMarker = c.nearestBefore(k -> c.keyword(k, "WITH") || c.word(k, "RECURSIVE")
                    || c.keyword(k, "SELECT") || c.keyword(k, "INSERT") || c.keyword(k, "UPDATE") || c.keyword(k, "DELETE"));
        }
        int k = cteMarker[i];
        return k >= 0 && (c.keyword(k, "WITH") || c.word(k, "RECURSIVE"));
    }

    void skipRange(int from, int to) { for (int k = from; k <= to && k < n; k++) skip[k] = true; }

    boolean operandEnd(int i) {
        if (!c.in(i)) return false;
        var t = s.get(i).type();
        if (t == LatticeSqlTokens.IDENTIFIER || t == LatticeSqlTokens.QUOTED_IDENTIFIER || t == LatticeSqlTokens.NUMBER
                || t == LatticeSqlTokens.STRING || t == LatticeSqlTokens.PARAMETER) return true;
        return c.punct(i, ')') || t == LatticeSqlTokens.KEYWORD && LITERAL_WORDS.contains(c.up(i));
    }

    // -- table references -------------------------------------------------------------------------------------------------

    /** What a table reference resolved to: opaque (columns unknowable), or a real table whose columns may be known. */
    record Ref(TableRef ref, @Nullable ExplorerMetadataSnapshot.Table table, boolean opaque) {
        boolean columnsKnown() { return table != null && !opaque && table.columnsLoaded(); }
    }

    List<Ref> resolveRefs() {
        List<Ref> resolved = new ArrayList<>();
        for (TableRef ref : structure.refs()) {
            boolean opaque = ref.derived() || ref.name() == null;
            String lower = ref.name() == null ? "" : ref.name().toLowerCase(Locale.ROOT);
            if (!opaque && ref.namespace() == null && (ctes.contains(lower) || script.created.contains(lower))) opaque = true;
            // a function that returns rows
            if (!opaque && !ref.introducer().equals("INTO") && !ref.introducer().equals("UPDATE") && c.punct(ref.endIndex(), '(')) opaque = true;
            if (!opaque && script.created.contains(lower) || !opaque && script.altered.contains(lower)) opaque = true;
            ExplorerMetadataSnapshot.Table table = null;
            if (!opaque && script.schema != null) {
                table = lookup(ref);
                if (table == null) opaque = true;
            }
            resolved.add(new Ref(ref, table, opaque));
        }
        return resolved;
    }

    /** The table, reporting an unknown one when the schema is sure to list it. */
    @Nullable ExplorerMetadataSnapshot.Table lookup(TableRef ref) {
        SqlSchema schema = script.schema;
        String lower = ref.name().toLowerCase(Locale.ROOT);
        if (lower.equals("dual") || lower.startsWith("pg_") || lower.startsWith("sqlite_") || lower.startsWith("sys")) return null;
        ExplorerMetadataSnapshot.Namespace namespace = schema.namespace(ref.namespace());
        if (namespace == null || !namespace.tablesLoaded()) return null;
        if (ref.namespace() == null && script.changesDefaultSchema) return null;
        SqlTableIndex index = schema.index(namespace);
        var table = index.find(ref.name());
        if (table != null) return table;
        if (index.ambiguousCase(ref.name())) return null;
        String suggestion = suggestion(namespace, ref.name(), () -> index.nearest(ref.name(), editLimit(ref.name())));
        String where = ref.namespace() == null ? "" : " in " + ref.namespace();
        int startToken = nameToken(ref);
        if (startToken >= 0) {
            c.report(Kind.UNKNOWN_TABLE, startToken, startToken,
                    "Unknown table '" + ref.name() + "'" + where + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                    suggestion == null ? null : requote(suggestion, startToken));
        }
        return null;
    }

    /** The token of the table's own name (the last part of a dotted name). */
    int nameToken(TableRef ref) {
        int last = -1;
        for (int k = ref.startIndex(); k < ref.endIndex() && k < n; k++) {
            if (ref.alias() != null && k >= aliasStart(ref)) break;
            if (c.isWord(k)) last = k;
            else if (!c.punct(k, '.')) break;
        }
        return last;
    }

    int aliasStart(TableRef ref) {
        if (ref.alias() == null) return Integer.MAX_VALUE;
        int k = ref.endIndex() - 1;
        return c.keyword(k - 1, "AS") ? k - 1 : k;
    }

    String requote(String replacement, int token) {
        if (s.get(token).type() != LatticeSqlTokens.QUOTED_IDENTIFIER) return replacement;
        return text.charAt(s.get(token).start()) + replacement + text.charAt(s.get(token).start());
    }

    void duplicateAliases(List<Ref> refs) {
        Map<String, TableRef> seen = new HashMap<>();
        for (Ref r : refs) {
            TableRef ref = r.ref();
            if (ref.exposedName() == null || ref.introducer().equals("INTO")) continue;
            String key = structure.blockOf()[ref.startIndex()] + ":" + segment[ref.startIndex()] + ":" + ref.exposedName().toLowerCase(Locale.ROOT);
            if (seen.containsKey(key)) {
                int at = Math.min(ref.alias() != null ? ref.endIndex() - 1 : nameToken(ref), n - 1);
                if (at >= 0) c.report(Kind.ALIAS, at, at, "Duplicate table alias '" + ref.exposedName() + "'");
            } else seen.put(key, ref);
        }
    }

    // -- column uses ---------------------------------------------------------------------------------------------------

    /** Tables visible at a token, innermost query first. Tables a statement inserts into are not part of what its SELECT sees. */
    Scope scope(int token, List<Ref> refs) {
        if (refsByBlock == null) {
            refsByBlock = new ArrayList<>();
            for (int b = 0; b < structure.open().size(); b++) refsByBlock.add(new ArrayList<>());
            for (Ref r : refs) refsByBlock.get(r.ref().block()).add(r);
        }
        int own = structure.blockOf()[token];
        int seg = segment[token];
        return scopes.computeIfAbsent((long) own << 32 | seg, key -> {
            List<List<Ref>> levels = new ArrayList<>();
            for (int block = own; block >= 0; block = structure.parent().get(block)) {
                List<Ref> level = new ArrayList<>();
                for (Ref r : refsByBlock.get(block)) {
                    if (r.ref().introducer().equals("INTO")) continue;
                    if (block == own && segment[r.ref().startIndex()] != seg) continue;
                    level.add(r);
                }
                if (!level.isEmpty()) levels.add(level);
                if (block == 0) break;
            }
            return Scope.of(levels);
        });
    }

    /** The levels of tables a name can come from, innermost first, and whether every one of their columns is known. */
    record Scope(List<List<Ref>> levels, boolean columnsKnown) {
        static Scope of(List<List<Ref>> levels) {
            for (List<Ref> level : levels) for (Ref r : level) if (!r.columnsKnown()) return new Scope(levels, false);
            return new Scope(levels, true);
        }
    }

    void columnUses(List<Ref> refs) {
        Set<Integer> setTargets = setTargets();
        for (int i = 0; i < n; i++) {
            c.tick();
            if (skip[i] || !c.ident(i) && !(isQualifierStart(i))) continue;
            if (c.punct(i - 1, '.') || c.operator(i - 1, "::") || c.keyword(i - 1, "AS")) continue;
            if (operandEnd(i - 1)) continue; // an alias
            List<Integer> chain = chain(i);
            int last = chain.get(chain.size() - 1);
            if (c.punct(last + 1, '(')) continue;
            if (chain.size() == 1 && !c.ident(i)) continue;
            boolean star = c.operator(last, "*");
            List<String> parts = new ArrayList<>();
            for (int k : chain) if (!c.operator(k, "*")) parts.add(c.name(k));
            boolean asSetTarget = setTargets.contains(i);
            if (parts.size() == 1 && !star) unqualified(i, parts.get(0), refs, asSetTarget);
            else if (parts.size() == 1 && star) qualified(i, i, parts.get(0), null, refs, chain);
            else if (parts.size() == 2) qualified(i, last, parts.get(0), star ? null : parts.get(1), refs, chain);
            else if (parts.size() == 3 && !star) qualified(chain.get(1), last, parts.get(1), parts.get(2), refs, chain);
        }
    }

    boolean isQualifierStart(int i) { return c.isWord(i) && c.punct(i + 1, '.') && !c.punct(i - 1, '.'); }

    List<Integer> chain(int start) {
        List<Integer> parts = new ArrayList<>();
        int k = start;
        parts.add(k);
        while (c.punct(k + 1, '.') && c.in(k + 2) && (c.isWord(k + 2) || c.operator(k + 2, "*"))) { parts.add(k + 2); k += 2; }
        return parts;
    }

    /** The identifiers on the left of "=" directly after SET: they name the statement's target table's columns. */
    Set<Integer> setTargets() {
        Set<Integer> targets = new HashSet<>();
        if (!kind.equals("UPDATE")) return targets;
        boolean inSet = false;
        for (int i = 0; i < n; i++) {
            c.tick();
            if (structure.blockOf()[i] != 0) continue;
            if (c.keyword(i, "SET")) inSet = true;
            else if (c.keyword(i, "WHERE") || c.keyword(i, "FROM")) inSet = false;
            else if (inSet && c.ident(i) && (c.keyword(i - 1, "SET") || c.punct(i - 1, ',')) && c.operator(i + 1, "=")) targets.add(i);
        }
        return targets;
    }

    void qualified(int fromToken, int lastToken, String qualifier, @Nullable String column, List<Ref> refs, List<Integer> chain) {
        String lowerQualifier = qualifier.toLowerCase(Locale.ROOT);
        if (TRAILING_QUALIFIERS.contains(qualifier.toUpperCase(Locale.ROOT))) return;
        List<List<Ref>> levels = scope(fromToken, refs).levels();
        Ref found = null;
        for (List<Ref> level : levels) {
            for (Ref r : level) if (r.ref().exposedName() != null && r.ref().exposedName().equalsIgnoreCase(qualifier)) found = found == null ? r : found;
            if (found != null) break;
        }
        int qualifierToken = chain.size() == 3 ? chain.get(1) : chain.get(0);
        if (found == null) {
            if (knownQualifiers.contains(lowerQualifier) || ctes.contains(lowerQualifier)) {
                // a table that is aliased, named by its own name
                for (TableRef ref : structure.refs()) {
                    if (ref.alias() != null && ref.name() != null && ref.name().equalsIgnoreCase(qualifier) && !qualifier.equalsIgnoreCase(ref.alias())
                            && noOtherExposes(qualifier)) {
                        c.report(Kind.ALIAS, qualifierToken, qualifierToken, "'" + qualifier + "' is aliased as '" + ref.alias() + "' here; use the alias",
                                requote(ref.alias(), qualifierToken));
                        return;
                    }
                }
                return;
            }
            if (script.schema != null && script.schema.namespace(qualifier) != null) return;
            if (levels.isEmpty() && structure.refs().isEmpty()) return;
            c.report(Kind.ALIAS, qualifierToken, qualifierToken, "Unknown table or alias '" + qualifier + "'");
            return;
        }
        if (column == null || !found.columnsKnown()) return;
        boolean exists = found.table().columns().stream().anyMatch(col -> col.getName().equalsIgnoreCase(column));
        if (!exists) {
            int token = lastToken;
            if (SqlSafeWords.pseudoColumn(profile.databaseType(), column)) return;
            var columns = found.table().columns();
            String suggestion = suggestion(found.table(), column, () -> nearest(column, columns.stream().map(ColumnMetadata::getName).toList()));
            c.report(Kind.UNKNOWN_COLUMN, token, token,
                    "Unknown column '" + column + "' in " + found.table().metadata().getName() + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                    suggestion == null ? null : requote(suggestion, token));
        }
    }

    boolean noOtherExposes(String qualifier) {
        for (TableRef ref : structure.refs()) if (ref.exposedName() != null && ref.exposedName().equalsIgnoreCase(qualifier)) return false;
        return true;
    }

    void unqualified(int token, String column, List<Ref> refs, boolean setTarget) {
        if (script.schema == null) return;
        String lower = column.toLowerCase(Locale.ROOT);
        boolean quoted = s.get(token).type() == LatticeSqlTokens.QUOTED_IDENTIFIER;
        if (aliasNames.contains(lower) && !quoted || usingColumns.contains(lower) && !quoted) return;
        Scope scope = setTarget ? Scope.of(targetLevel(refs)) : scope(token, refs);
        List<List<Ref>> levels = scope.levels();
        if (levels.isEmpty() || !scope.columnsKnown()) return; // nothing to judge against, or something in scope hides its columns
        for (List<Ref> level : levels) {
            List<Ref> matches = new ArrayList<>();
            for (Ref r : level) if (r.table().columns().stream().anyMatch(col -> col.getName().equalsIgnoreCase(column))) matches.add(r);
            if (matches.isEmpty()) continue;
            if (matches.size() > 1 && !natural && !setTarget) {
                c.report(Kind.AMBIGUOUS_COLUMN, token, token, "Column '" + column + "' is in " + names(matches) + "; qualify it");
            }
            return;
        }
        if (SqlSafeWords.pseudoColumn(profile.databaseType(), column)) return;
        if (!quoted && (SqlSafeWords.contains(column) || profile.keywords().contains(column.toUpperCase(Locale.ROOT)))) return;
        List<String> candidates = new ArrayList<>();
        for (List<Ref> level : levels) for (Ref r : level) for (ColumnMetadata col : r.table().columns()) candidates.add(col.getName());
        // with a single table in scope the answer is the one a qualified name gets
        Object single = levels.size() == 1 && levels.get(0).size() == 1 ? levels.get(0).get(0).table() : null;
        String suggestion = single == null ? nearest(column, candidates) : suggestion(single, column, () -> nearest(column, candidates));
        c.report(Kind.UNKNOWN_COLUMN, token, token, "Unknown column '" + column + "'" + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                suggestion == null ? null : requote(suggestion, token));
    }

    String names(List<Ref> matches) {
        StringJoiner joined = new StringJoiner(" and ");
        for (Ref r : matches) joined.add(r.ref().exposedName() == null ? r.table().metadata().getName() : r.ref().exposedName());
        return joined.toString();
    }

    /** UPDATE's own table: the only one its SET list can name. */
    List<List<Ref>> targetLevel(List<Ref> refs) {
        for (Ref r : refs) if (r.ref().introducer().equals("UPDATE")) return List.of(List.of(r));
        return List.of();
    }

    /** INSERT INTO t (a, b): the names must be columns of t. */
    void insertColumns(List<Ref> refs) {
        if (!kind.equals("INSERT") && !kind.equals("REPLACE")) return;
        for (Ref r : refs) {
            TableRef ref = r.ref();
            if (!ref.introducer().equals("INTO") || !r.columnsKnown() || !c.punct(ref.endIndex(), '(') || c.keyword(ref.endIndex() + 1, "SELECT")) continue;
            int close = c.closing(ref.endIndex());
            for (int k = ref.endIndex() + 1; k < close; k++) {
                c.tick();
                if (!c.ident(k) || !(c.punct(k - 1, '(') || c.punct(k - 1, ','))) continue;
                String column = c.name(k);
                if (r.table().columns().stream().noneMatch(col -> col.getName().equalsIgnoreCase(column))) {
                    String suggestion = suggestion(r.table(), column, () -> nearest(column, r.table().columns().stream().map(ColumnMetadata::getName).toList()));
                    c.report(Kind.UNKNOWN_COLUMN, k, k,
                            "Unknown column '" + column + "' in " + r.table().metadata().getName() + (suggestion == null ? "" : ". Did you mean '" + suggestion + "'?"),
                            suggestion == null ? null : requote(suggestion, k));
                }
            }
        }
    }

    /** How many edits away a name can be and still be offered as what was meant. */
    static int editLimit(String wanted) { return wanted.length() <= 4 ? 1 : 2; }

    /** The "did you mean" answer for a name among the things of {@code scope}, worked out once per script. */
    @Nullable String suggestion(Object scope, String wanted, Supplier<String> search) {
        Map<String, String> known = script.suggestions.computeIfAbsent(scope, key -> new HashMap<>());
        String key = wanted.toLowerCase(Locale.ROOT);
        if (known.containsKey(key)) return known.get(key);
        String found = search.get();
        known.put(key, found);
        return found;
    }

    /** The closest name within a typo's distance, or null. */
    @Nullable String nearest(String wanted, Collection<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int limit = editLimit(wanted);
        String lowerWanted = wanted.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (Math.abs(candidate.length() - lowerWanted.length()) > limit || candidate.equalsIgnoreCase(wanted)) continue;
            int distance = SqlEditDistance.distance(lowerWanted, candidate.toLowerCase(Locale.ROOT), limit);
            if (distance <= limit && distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }
}
