package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.completion.SqlSuggestion.Kind;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class SqlCompletionEngineTest {
    private static ColumnMetadata column(String name, boolean autoIncrement, ColumnReference reference) {
        var column = new ColumnMetadata(name, "INTEGER", java.sql.Types.INTEGER, 10, 0, true, false, autoIncrement, null);
        return reference == null ? column : column.withReference(reference);
    }

    private static ExplorerMetadataSnapshot.Table table(String name, String type, ColumnMetadata... columns) {
        var metadata = new TableMetadata(null, "PUBLIC", name, type);
        metadata.setColumns(List.of(columns));
        return new ExplorerMetadataSnapshot.Table(metadata, List.of(columns), true);
    }

    private static SqlSchema schema(DatabaseType type) {
        var customers = table("customers", "TABLE", column("id", true, null), column("name", false, null));
        var orders = table("orders", "TABLE", column("id", true, null), column("total", false, null),
                column("customer_id", false, new ColumnReference("PUBLIC", "customers", "id")));
        var summary = table("order_summary", "VIEW", column("total", false, null));
        var audit = table("log", "TABLE", column("message", false, null));
        var snapshot = new ExplorerMetadataSnapshot(List.of(
                new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(customers, orders, summary), true),
                new ExplorerMetadataSnapshot.Namespace("audit", List.of(audit), true)), List.of(), 3);
        return SqlSchema.of(new ConnectionConfig(type, "engine test"), snapshot, "PUBLIC", 1);
    }

    private static List<SqlSuggestion> suggest(String sql, SqlDialectProfile profile) {
        int caret = sql.indexOf('^');
        var context = SqlStatementAnalyzer.analyze(sql.substring(0, caret) + sql.substring(caret + 1), caret, profile);
        return SqlCompletionEngine.suggest(context, profile, schema(profile.databaseType() == null ? DatabaseType.H2 : profile.databaseType()));
    }

    private static List<SqlSuggestion> suggest(String sql) { return suggest(sql, SqlDialects.H2); }

    /** Labels of one kind; words are upper-cased because they follow the case the test happened to type. */
    private static List<String> labels(List<SqlSuggestion> suggestions, Kind kind) {
        boolean word = kind == Kind.KEYWORD || kind == Kind.FUNCTION || kind == Kind.TYPE;
        return suggestions.stream().filter(s -> s.kind() == kind).map(s -> word ? s.label().toUpperCase(Locale.ROOT) : s.label()).toList();
    }

    private static List<String> rawLabels(List<SqlSuggestion> suggestions, Kind kind) {
        return suggestions.stream().filter(s -> s.kind() == kind).map(SqlSuggestion::label).toList();
    }

    private static List<String> inserts(List<SqlSuggestion> suggestions, Kind kind) {
        return suggestions.stream().filter(s -> s.kind() == kind).map(SqlSuggestion::insertText).toList();
    }

    // ---- tables ----------------------------------------------------------------------------------------------------------

    @Test void aTableSlotOffersTablesViewsAndOtherSchemas() {
        var suggestions = suggest("select * from ^");
        assertEquals(List.of("customers", "orders"), labels(suggestions, Kind.TABLE));
        assertEquals(List.of("order_summary"), labels(suggestions, Kind.VIEW));
        assertEquals(List.of("PUBLIC.", "\"audit\"."), inserts(suggestions, Kind.NAMESPACE), "H2 folds to upper case, so audit needs quotes");
        assertEquals(List.of("PUBLIC.", "audit."), inserts(suggest("select * from ^", SqlDialects.MYSQL), Kind.NAMESPACE));
        assertTrue(labels(suggestions, Kind.KEYWORD).isEmpty(), "no keyword fits a bare table slot");
    }

    @Test void aSchemaPrefixOffersThatSchemasTables() {
        assertEquals(List.of("log"), labels(suggest("select * from audit.^"), Kind.TABLE));
        assertEquals(List.of(), labels(suggest("select * from nothing.^"), Kind.TABLE));
    }

    @Test void tablesThatNeedQuotesAreQuotedForTheDialect() {
        // H2 folds unquoted names to upper case, so lower-case names must be quoted there and not in MySQL.
        assertEquals(List.of("\"customers\"", "\"orders\""), inserts(suggest("select * from ^", SqlDialects.H2), Kind.TABLE));
        assertEquals(List.of("customers", "orders"), inserts(suggest("select * from ^", SqlDialects.MYSQL), Kind.TABLE));
        assertEquals(List.of("customers", "orders"), inserts(suggest("select * from ^", SqlDialects.POSTGRESQL), Kind.TABLE));
    }

    @Test void anOpenQuoteIsClosedAndKeywordsAreWithheld() {
        var suggestions = suggest("select * from `ord^", SqlDialects.MYSQL);
        assertEquals(List.of("customers`", "orders`"), inserts(suggestions, Kind.TABLE));
        assertTrue(labels(suggestions, Kind.KEYWORD).isEmpty());
    }

    // ---- columns ---------------------------------------------------------------------------------------------------------

    @Test void theSelectListOffersColumnsFromEveryTableInScope() {
        var suggestions = suggest("select ^ from orders", SqlDialects.MYSQL);
        assertEquals(List.of("id", "total", "customer_id"), labels(suggestions, Kind.COLUMN));
        assertTrue(rawLabels(suggestions, Kind.FUNCTION).contains("count"), "functions follow the typed case: lower");
    }

    @Test void aColumnSharedByTwoTablesIsInsertedQualified() {
        var suggestions = suggest("select ^ from orders o join customers c on c.id = o.customer_id", SqlDialects.MYSQL);
        var ids = suggestions.stream().filter(s -> s.kind() == Kind.COLUMN && s.label().equals("id")).map(SqlSuggestion::insertText).toList();
        assertEquals(List.of("o.id", "c.id"), ids);
        assertEquals(List.of("total"), suggestions.stream().filter(s -> s.kind() == Kind.COLUMN && s.label().equals("total")).map(SqlSuggestion::insertText).toList());
        assertEquals(List.of("o.", "c."), inserts(suggestions, Kind.ALIAS));
    }

    @Test void qualifiedColumnsResolveAnAliasATableNameOrASchemaPath() {
        assertEquals(List.of("id", "total", "customer_id"), labels(suggest("select o.^ from orders o"), Kind.COLUMN));
        assertEquals(List.of("id", "name"), labels(suggest("select customers.^ from customers"), Kind.COLUMN));
        assertEquals(List.of("message"), labels(suggest("select audit.log.^ from audit.log"), Kind.COLUMN));
        assertEquals(List.of("id", "name"), labels(suggest("select public.customers.^ from customers"), Kind.COLUMN));
        assertEquals(List.of(), labels(suggest("select zzz.^ from orders"), Kind.COLUMN));
    }

    @Test void anAliasHidesTheTableName() {
        assertEquals(List.of(), labels(suggest("select orders.^ from orders o"), Kind.COLUMN));
    }

    @Test void columnsMatchIgnoringTheCaseTheUserTypedForTheTable() {
        assertEquals(List.of("id", "total", "customer_id"), labels(suggest("select o.^ from ORDERS o"), Kind.COLUMN));
    }

    @Test void insertColumnListsSkipColumnsAlreadyNamedAndRankGeneratedOnesLast() {
        var suggestions = suggest("insert into orders (id, ^)");
        assertEquals(List.of("total", "customer_id"), labels(suggestions, Kind.COLUMN));
        var all = suggest("insert into orders (^)");
        assertEquals(List.of("total", "customer_id", "id"), labels(all, Kind.COLUMN));
    }

    @Test void updateSetOffersTheTargetsColumnsThenValuesInsteadOfColumnsAfterEquals() {
        assertEquals(List.of("total", "customer_id", "id"), labels(suggest("update orders set ^"), Kind.COLUMN));
        var value = suggest("update orders set total = ^");
        assertTrue(labels(value, Kind.KEYWORD).containsAll(List.of("NULL", "DEFAULT")));
        assertTrue(labels(value, Kind.FUNCTION).contains("COUNT"));
        assertEquals(List.of("ID", "TOTAL", "CUSTOMER_ID"), labels(value, Kind.COLUMN).stream().map(String::toUpperCase).sorted(java.util.Comparator.comparingInt(n -> List.of("ID", "TOTAL", "CUSTOMER_ID").indexOf(n))).toList(),
                "the right-hand side may refer to columns");
    }

    @Test void valuesOffersLiteralsAndFunctionsNotColumns() {
        var suggestions = suggest("insert into orders (id) values (^");
        assertTrue(labels(suggestions, Kind.COLUMN).isEmpty());
        assertTrue(labels(suggestions, Kind.KEYWORD).contains("NULL"));
    }

    // ---- joins -----------------------------------------------------------------------------------------------------------

    @Test void anOnClauseSuggestsTheForeignKeyEquality() {
        var suggestions = suggest("select * from orders o join customers c on ^", SqlDialects.MYSQL);
        assertEquals(List.of("o.customer_id = c.id"), labels(suggestions, Kind.JOIN));
        assertEquals(Kind.JOIN, suggestions.get(0).kind(), "the foreign key condition ranks first");
    }

    @Test void theJoinConditionWorksInBothDirections() {
        var forward = suggest("select * from customers c join orders o on ^", SqlDialects.MYSQL);
        assertEquals(1, labels(forward, Kind.JOIN).size());
        assertTrue(forward.get(0).insertText().contains("o.customer_id"));
        assertTrue(forward.get(0).insertText().contains("c.id"));
    }

    @Test void unrelatedTablesGetNoJoinSuggestion() {
        assertTrue(labels(suggest("select * from orders o join audit.log l on ^"), Kind.JOIN).isEmpty());
        assertTrue(labels(suggest("select * from orders o where ^"), Kind.JOIN).isEmpty());
    }

    // ---- keywords --------------------------------------------------------------------------------------------------------

    @Test void statementStartOffersStatementsForTheDialect() {
        assertTrue(labels(suggest("^", SqlDialects.MYSQL), Kind.KEYWORD).containsAll(List.of("SELECT", "INSERT INTO", "SHOW", "USE", "DESCRIBE")));
        var postgres = labels(suggest("^", SqlDialects.POSTGRESQL), Kind.KEYWORD);
        assertTrue(postgres.contains("COPY") && postgres.contains("VACUUM"));
        assertFalse(postgres.contains("USE"));
        assertFalse(labels(suggest("^", SqlDialects.ANSI), Kind.KEYWORD).contains("SHOW"));
    }

    @Test void limitFollowsTheDialect() {
        var mysql = labels(suggest("select * from orders ^", SqlDialects.MYSQL), Kind.KEYWORD);
        assertTrue(mysql.contains("LIMIT"));
        assertFalse(mysql.contains("FETCH FIRST"));
        var ansi = labels(suggest("select * from orders ^", SqlDialects.ANSI), Kind.KEYWORD);
        assertFalse(ansi.contains("LIMIT"));
        assertTrue(ansi.contains("FETCH FIRST"));
        var h2 = labels(suggest("select * from orders ^", SqlDialects.H2), Kind.KEYWORD);
        assertTrue(h2.contains("LIMIT") && h2.contains("FETCH FIRST"));
    }

    @Test void afterAJoinedTableTheOnClauseIsOffered() {
        assertTrue(labels(suggest("select * from orders o join customers c ^"), Kind.KEYWORD).containsAll(List.of("ON", "USING")));
        assertFalse(labels(suggest("select * from orders o ^"), Kind.KEYWORD).contains("ON"));
    }

    @Test void afterUpdateAndInsertTargetsTheNextWordIsKnown() {
        assertEquals(List.of("SET"), labels(suggest("update orders ^"), Kind.KEYWORD));
        assertTrue(labels(suggest("insert into orders ^"), Kind.KEYWORD).containsAll(List.of("VALUES", "SELECT")));
    }

    @Test void afterAnOperandOperatorsRankAboveFunctionsAndColumns() {
        var suggestions = suggest("select * from orders where total ^", SqlDialects.POSTGRESQL);
        assertTrue(labels(suggestions, Kind.KEYWORD).containsAll(List.of("AND", "IN", "IS NULL", "ILIKE", "ORDER BY")));
        assertTrue(labels(suggestions, Kind.COLUMN).isEmpty());
        assertFalse(labels(suggest("select * from orders where total ^", SqlDialects.MYSQL), Kind.KEYWORD).contains("ILIKE"));
    }

    @Test void nullsOrderingIsNotOfferedForMysql() {
        assertFalse(labels(suggest("select * from orders order by total ^", SqlDialects.MYSQL), Kind.KEYWORD).contains("NULLS FIRST"));
        assertTrue(labels(suggest("select * from orders order by total ^", SqlDialects.POSTGRESQL), Kind.KEYWORD).contains("NULLS FIRST"));
    }

    @Test void keywordCaseFollowsTheTypedText() {
        assertTrue(rawLabels(suggest("sel^"), Kind.KEYWORD).contains("select"));
        assertTrue(rawLabels(suggest("SEL^"), Kind.KEYWORD).contains("SELECT"));
    }

    @Test void typesComeFromTheDialectAfterACast() {
        var postgres = labels(suggest("select a::^ from t", SqlDialects.POSTGRESQL), Kind.TYPE);
        assertTrue(postgres.contains("jsonb") || postgres.contains("JSONB"));
        assertFalse(labels(suggest("select cast(a as ^) from t", SqlDialects.MYSQL), Kind.TYPE).stream().anyMatch(t -> t.equalsIgnoreCase("jsonb")));
    }

    // ---- degenerate input ------------------------------------------------------------------------------------------------

    @Test void commentsAndStringsGetNothing() {
        assertEquals(List.of(), suggest("select 'abc^'"));
        assertEquals(List.of(), suggest("-- note ^"));
    }

    @Test void withoutASchemaOnlyWordsAreOffered() {
        var profile = SqlDialects.MYSQL;
        int caret = "select * from ".length();
        var context = SqlStatementAnalyzer.analyze("select * from ", caret, profile);
        assertEquals(List.of(), SqlCompletionEngine.suggest(context, profile, null));
        var select = SqlStatementAnalyzer.analyze("select  from orders", "select ".length(), profile);
        var suggestions = SqlCompletionEngine.suggest(select, profile, null);
        assertTrue(suggestions.stream().noneMatch(s -> s.kind() == Kind.COLUMN));
        assertTrue(suggestions.stream().anyMatch(s -> s.kind() == Kind.FUNCTION));
    }

    @Test void resultsAreOrderedByPriorityAndHaveNoDuplicates() {
        var suggestions = suggest("select ^ from orders o join customers c on c.id = o.customer_id");
        for (int i = 1; i < suggestions.size(); i++) assertTrue(suggestions.get(i - 1).priority() >= suggestions.get(i).priority());
        assertEquals(suggestions.size(), suggestions.stream().map(s -> s.kind() + s.insertText()).distinct().count());
    }

    // ---- identifiers vs the product's own quoting --------------------------------------------------------------------------

    @Test void quotingAgreesWithTheConsoleDialectForEveryDatabase() {
        for (DatabaseType type : DatabaseType.values()) {
            var profile = SqlDialects.of(type);
            var dialect = com.segfault03.ideadb.provider.DatabaseProviders.get(type).dialect();
            for (String name : List.of("Order Items", "select", "weird`name", "plain")) {
                if (!SqlIdentifiers.needsQuotes(name, profile)) continue;
                assertEquals(dialect.quoteIdentifier(name), SqlIdentifiers.quote(name, profile), type + " " + name);
            }
        }
    }
}
