package com.segfault03.ideadb.sql.completion;

import com.segfault03.ideadb.sql.completion.SqlContext.Position;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlStatementAnalyzerTest {
    /** The caret is written as '^'. */
    private static SqlContext at(String sql) { return at(sql, SqlDialects.ANSI); }

    private static SqlContext at(String sql, SqlDialectProfile profile) {
        int caret = sql.indexOf('^');
        assertTrue(caret >= 0, "no caret in: " + sql);
        return SqlStatementAnalyzer.analyze(sql.substring(0, caret) + sql.substring(caret + 1), caret, profile);
    }

    private static List<String> names(SqlContext context) {
        return context.tables().stream().map(t -> (t.namespace() == null ? "" : t.namespace() + ".") + t.name() + (t.alias() == null ? "" : " " + t.alias())).toList();
    }

    // ---- positions -------------------------------------------------------------------------------------------------

    @Test void anEmptyOrFirstWordIsTheStartOfAStatement() {
        assertEquals(Position.STATEMENT_START, at("^").position());
        assertEquals(Position.STATEMENT_START, at("sel^").position());
        assertEquals("sel", at("sel^").prefix());
        assertEquals(Position.STATEMENT_START, at("select 1; ^").position());
        assertEquals(Position.STATEMENT_START, at("select 1;\nupd^").position());
    }

    @Test void theSelectListSeesTablesNamedAfterTheCaret() {
        SqlContext context = at("select ^ from orders o join customers c on c.id = o.customer_id");
        assertEquals(Position.SELECT_LIST, context.position());
        assertEquals(List.of("orders o", "customers c"), names(context));
    }

    @Test void aTableNameSlotFollowsFromJoinUpdateAndInto() {
        assertEquals(Position.TABLE_NAME, at("select * from ^").position());
        assertEquals(Position.TABLE_NAME, at("select * from ord^").position());
        assertEquals(Position.TABLE_NAME, at("select * from orders o join ^").position());
        assertEquals(Position.TABLE_NAME, at("update ^").position());
        assertEquals(Position.TABLE_NAME, at("insert into ^").position());
        assertEquals(Position.TABLE_NAME, at("delete from ^").position());
        assertEquals(Position.TABLE_NAME, at("select * from orders o, ^").position());
        assertEquals(Position.TABLE_NAME, at("drop table ^").position());
        assertEquals(Position.TABLE_NAME, at("drop table if exists ^").position());
        assertEquals(Position.TABLE_NAME, at("truncate table ^").position());
    }

    @Test void afterATableReferenceComesTheAliasAndTheNextClause() {
        assertEquals(Position.AFTER_TABLE, at("select * from orders ^").position());
        assertEquals(Position.AFTER_TABLE, at("select * from orders o ^").position());
        assertEquals(Position.AFTER_TABLE, at("select * from orders o left ^").position());
        assertEquals(Position.AFTER_TABLE, at("update orders ^").position());
        assertEquals(Position.AFTER_TABLE, at("insert into orders ^").position());
    }

    @Test void conditionsFollowWhereHavingAndOn() {
        assertEquals(Position.CONDITION, at("select * from orders where ^").position());
        assertEquals(Position.CONDITION, at("select * from orders where a = 1 and ^").position());
        assertEquals(Position.CONDITION, at("select a from orders group by a having ^").position());
        assertEquals(Position.CONDITION, at("select * from orders o join items i on ^").position());
    }

    @Test void groupAndOrderByShareAPosition() {
        assertEquals(Position.ORDER_GROUP, at("select * from orders order by ^").position());
        assertEquals(Position.ORDER_GROUP, at("select * from orders group by a, ^").position());
        assertEquals(Position.ORDER_GROUP, at("select a, count(*) over (partition by ^) from orders").position());
    }

    @Test void updateSetDistinguishesTheColumnFromTheValue() {
        assertEquals(Position.SET_COLUMN, at("update orders set ^").position());
        assertEquals(Position.SET_VALUE, at("update orders set a = ^").position());
        assertEquals(Position.SET_COLUMN, at("update orders set a = 1, ^").position());
        assertEquals(Position.SET_VALUE, at("update orders set a = 1, b = ^").position());
        assertEquals("orders", at("update orders set ^").target().name());
        assertEquals(Position.CONDITION, at("update orders set a = 1 where ^").position());
    }

    @Test void insertHasAColumnListThenValues() {
        SqlContext columns = at("insert into orders (id, ^)");
        assertEquals(Position.INSERT_COLUMNS, columns.position());
        assertEquals("orders", columns.target().name());
        assertEquals(java.util.Set.of("ID"), columns.usedNames());
        assertEquals(Position.INSERT_COLUMNS, at("insert into orders (^").position());
        assertEquals(Position.VALUES, at("insert into orders (id) values (^").position());
        assertEquals(Position.VALUES, at("insert into orders (id) values (1), (^").position());
    }

    @Test void castsAndPostgresCastsExpectATypeName() {
        assertEquals(Position.TYPE_NAME, at("select cast(a as ^) from t").position());
        assertEquals(Position.TYPE_NAME, at("select a::^ from t", SqlDialects.POSTGRESQL).position());
        assertEquals(Position.TYPE_NAME, at("select a::in^ from t", SqlDialects.POSTGRESQL).position());
    }

    @Test void commentsAndStringsGetNoSuggestions() {
        assertEquals(Position.NONE, at("select 1 -- note ^").position());
        assertEquals(Position.NONE, at("select 1 /* note ^ */").position());
        assertEquals(Position.NONE, at("select 1 /* note ^").position());
        assertEquals(Position.NONE, at("select 'abc^def' from t").position());
        assertEquals(Position.NONE, at("select 'abc^").position());
        assertNotEquals(Position.NONE, at("select 'abc'^").position());
        assertNotEquals(Position.NONE, at("select 1 -- note\n^").position());
        assertEquals(Position.NONE, at("select $$ body ^ $$", SqlDialects.POSTGRESQL).position());
    }

    // ---- scope -----------------------------------------------------------------------------------------------------

    @Test void tablesCarryNamespaceAndAliasWithOrWithoutAs() {
        SqlContext context = at("select ^ from shop.orders as o, audit.\"Log Entries\" l join customers c on 1 = 1");
        assertEquals(List.of("shop.orders o", "audit.Log Entries l", "customers c"), names(context));
    }

    @Test void aKeywordNamedTableIsStillATable() {
        assertEquals(List.of("user u"), names(at("select ^ from user u")));
    }

    @Test void statementsAreSeparatedBySemicolons() {
        SqlContext context = at("select * from other;\nselect ^ from orders o;\nselect * from third");
        assertEquals(List.of("orders o"), names(context));
    }

    @Test void aSubqueryAlsoSeesTheOuterQuery() {
        SqlContext inner = at("select * from orders o where exists (select 1 from items i where i.order_id = o.id and ^)");
        assertEquals(Position.CONDITION, inner.position());
        assertEquals(List.of("orders o", "items i"), names(inner));
        SqlContext outer = at("select ^ from orders o where exists (select 1 from items i)");
        assertEquals(List.of("orders o"), names(outer), "the outer query does not see the subquery's tables");
    }

    @Test void aDerivedTableHasAnAliasButNoName() {
        SqlContext context = at("select ^ from (select a from t) x");
        assertEquals(1, context.tables().size());
        assertTrue(context.tables().get(0).derived());
        assertEquals("x", context.tables().get(0).alias());
        assertNull(context.tables().get(0).name());
    }

    @Test void fromInsideExtractOrTrimIsNotATable() {
        assertEquals(List.of("orders o"), names(at("select extract(year from o.created_at), ^ from orders o")));
        assertEquals(List.of("orders o"), names(at("select trim(both ' ' from o.name), ^ from orders o")));
        assertEquals(List.of("a"), names(at("select ^ from a where x is distinct from y")));
    }

    @Test void theWordBeingTypedIsNotMistakenForAnAlias() {
        assertEquals(List.of("orders"), names(at("select * from orders ord^")));
        assertEquals(Position.AFTER_TABLE, at("select * from orders ord^").position());
    }

    @Test void aClauseWordAfterATableIsNotItsAlias() {
        for (var profile : new SqlDialectProfile[] {SqlDialects.ANSI, SqlDialects.MYSQL, SqlDialects.POSTGRESQL}) {
            for (String clause : List.of("limit 10", "offset 5", "union select 1", "except select 1", "intersect select 1", "minus select 1",
                    "fetch first 1 rows only", "for update", "lock in share mode", "qualify x > 1", "window w as (order by x)", "returning id")) {
                SqlContext context = at("select users.name from users " + clause + " ^", profile);
                assertEquals(List.of("users"), names(context), profile.displayName() + ": " + clause);
                assertNull(context.tables().get(0).alias(), profile.displayName() + ": " + clause);
            }
        }
    }

    @Test void aClauseWordIsAnAliasOnlyWhenQuotedOrAfterAs() {
        assertEquals(List.of("users limit"), names(at("select ^ from users as limit")));
        assertEquals(List.of("users limit"), names(at("select ^ from users \"limit\"")));
        assertEquals(List.of("users u"), names(at("select ^ from users u limit 1")));
    }

    @Test void aDerivedTableIsNotAliasedByAClauseWord() {
        SqlContext context = at("select ^ from (select 1 as a) limit 10", SqlDialects.ANSI);
        assertEquals(1, context.tables().size());
        assertNull(context.tables().get(0).alias());
    }

    // ---- qualifier, prefix and quoting -------------------------------------------------------------------------------

    @Test void aQualifierIsTheDottedNamesBeforeTheCaret() {
        SqlContext one = at("select o.^ from orders o");
        assertEquals(List.of("o"), one.qualifier());
        assertEquals("", one.prefix());
        SqlContext two = at("select shop.orders.to^ from shop.orders");
        assertEquals(List.of("shop", "orders"), two.qualifier());
        assertEquals("to", two.prefix());
        assertEquals(List.of(), at("select to^ from orders").qualifier());
        assertEquals(List.of("shop"), at("select * from shop.^").qualifier());
        assertEquals(Position.TABLE_NAME, at("select * from shop.^").position());
    }

    @Test void aQuotedQualifierIsUnquoted() {
        assertEquals(List.of("Order Items"), at("select \"Order Items\".^ from \"Order Items\"").qualifier());
        assertEquals(List.of("a`b"), at("select `a``b`.^ from t", SqlDialects.MYSQL).qualifier());
    }

    @Test void anOpenQuoteIsReportedAndThePrefixExcludesIt() {
        SqlContext context = at("select * from `ord^", SqlDialects.MYSQL);
        assertEquals('`', context.openQuote());
        assertEquals("ord", context.prefix());
        assertEquals(Position.TABLE_NAME, context.position());
        assertEquals(0, at("select * from ord^").openQuote());
    }

    // ---- joins -----------------------------------------------------------------------------------------------------

    @Test void anOnClauseKnowsTheJoinedTableAndWhatCameBefore() {
        SqlContext context = at("select * from orders o join customers c on ^ join items i on 1 = 1");
        assertEquals(Position.CONDITION, context.position());
        assertEquals("customers", context.joinTarget().name());
        assertEquals(List.of("orders"), context.joinPrior().stream().map(SqlContext.TableRef::name).toList());

        SqlContext second = at("select * from orders o join customers c on 1 = 1 join items i on ^");
        assertEquals("items", second.joinTarget().name());
        assertEquals(List.of("orders", "customers"), second.joinPrior().stream().map(SqlContext.TableRef::name).toList());
    }

    @Test void aWhereClauseHasNoJoinTarget() {
        assertNull(at("select * from orders o join customers c on 1 = 1 where ^").joinTarget());
    }

    // ---- operand and keyword case -------------------------------------------------------------------------------------

    @Test void afterAnOperandKeywordsComeBeforeColumns() {
        assertTrue(at("select * from t where a ^").afterOperand());
        assertTrue(at("select * from t where a = 1 ^").afterOperand());
        assertTrue(at("select * from t where (a = 1) ^").afterOperand());
        assertFalse(at("select * from t where ^").afterOperand());
        assertFalse(at("select * from t where a = ^").afterOperand());
        assertFalse(at("select * from t where a and ^").afterOperand());
    }

    @Test void keywordsFollowTheCaseTheUserTypes() {
        assertTrue(at("sel^").lowercaseKeywords());
        assertFalse(at("SEL^").lowercaseKeywords());
        assertFalse(at("Sel^").lowercaseKeywords(), "any capital means the user capitalizes");
        assertTrue(at("select * from orders ^").lowercaseKeywords(), "an empty prefix follows the last keyword");
        assertFalse(at("SELECT * FROM orders ^").lowercaseKeywords());
        assertFalse(at("^").lowercaseKeywords(), "nothing to follow: upper case");
        assertTrue(at("select 1;\n^").lowercaseKeywords(), "an empty statement follows the file's first keyword");
    }
}
