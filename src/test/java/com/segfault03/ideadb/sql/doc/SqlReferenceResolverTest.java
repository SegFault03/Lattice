package com.segfault03.ideadb.sql.doc;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlReferenceResolverTest {
    /** The pointer is written as '^' and sits before the character it is over. */
    private static SqlTarget at(String sql) { return at(sql, SqlDialects.H2); }

    private static SqlTarget at(String sql, SqlDialectProfile profile) {
        int offset = sql.indexOf('^');
        assertTrue(offset >= 0, "no pointer in: " + sql);
        var type = profile.databaseType() == null ? DatabaseType.H2 : profile.databaseType();
        return SqlReferenceResolver.resolve(sql.substring(0, offset) + sql.substring(offset + 1), offset, profile, DocTestSchema.schema(type));
    }

    @Test void aTableAfterFromResolvesToTheTableWithTheSchemasSpelling() {
        assertEquals(new SqlTarget.Table("PUBLIC", "orders"), at("select * from ^orders"));
        assertEquals(new SqlTarget.Table("PUBLIC", "orders"), at("select * from ORD^ERS"), "matches ignoring case, reports the stored name");
        assertEquals(new SqlTarget.Table("PUBLIC", "order_summary"), at("select * from ^order_summary"));
    }

    @Test void aSchemaQualifierAndTheTableAfterIt() {
        assertEquals(new SqlTarget.Schema("audit"), at("select * from ^audit.log"));
        assertEquals(new SqlTarget.Table("audit", "log"), at("select * from audit.^log"));
        assertEquals(new SqlTarget.Table("PUBLIC", "customers"), at("select * from public.^customers"));
        assertNull(at("select * from nothing.^log"));
    }

    @Test void anAliasResolvesWhereverItIsMentioned() {
        assertEquals(new SqlTarget.Alias("o", "PUBLIC", "orders"), at("select * from orders ^o"));
        assertEquals(new SqlTarget.Alias("o", "PUBLIC", "orders"), at("select ^o.id from orders o"));
        assertEquals(new SqlTarget.Alias("c", "PUBLIC", "customers"), at("select * from orders o join customers as c on ^c.id = o.customer_id"));
    }

    @Test void aTableNameUsedAsAQualifierResolvesToTheTable() {
        assertEquals(new SqlTarget.Table("PUBLIC", "orders"), at("select ^orders.id from orders"));
    }

    @Test void anAliasHidesTheTablesOwnNameInItsQuery() {
        assertNull(at("select ^orders.id from orders o"));
    }

    @Test void aColumnAfterAnAliasOrTableResolves() {
        assertEquals(new SqlTarget.Column("PUBLIC", "orders", "total"), at("select o.^total from orders o"));
        assertEquals(new SqlTarget.Column("PUBLIC", "customers", "name"), at("select customers.^name from customers"));
        assertEquals(new SqlTarget.Column("audit", "log", "message"), at("select audit.log.^message from audit.log"));
        assertNull(at("select o.^nothing from orders o"));
    }

    @Test void anUnqualifiedColumnComesFromTheTablesInScope() {
        assertEquals(new SqlTarget.Column("PUBLIC", "orders", "total"), at("select ^total from orders"));
        assertEquals(new SqlTarget.Column("PUBLIC", "customers", "name"), at("select ^name from orders o join customers c on c.id = o.customer_id"));
        assertEquals(new SqlTarget.Column("PUBLIC", "orders", "total"), at("select * from orders where ^TOTAL > 1"));
        assertNull(at("select ^name from orders"), "orders has no name column");
    }

    @Test void updateAndInsertNameColumnsOfTheirTarget() {
        assertEquals(new SqlTarget.Column("PUBLIC", "orders", "total"), at("update orders set ^total = 1"));
        assertEquals(new SqlTarget.Column("PUBLIC", "orders", "customer_id"), at("insert into orders (id, ^customer_id) values (1, 2)"));
    }

    @Test void quotedNamesAreUnquotedBeforeMatching() {
        assertEquals(new SqlTarget.Table("PUBLIC", "orders"), at("select * from \"^orders\""));
        assertEquals(new SqlTarget.Table("PUBLIC", "orders"), at("select * from `^orders`", SqlDialects.MYSQL));
        assertEquals(new SqlTarget.Column("PUBLIC", "orders", "total"), at("select \"o\".\"^total\" from orders \"o\""));
    }

    @Test void keywordsAreNotNames() {
        assertNull(at("^select * from orders"));
        assertNull(at("select * ^from orders"));
        assertNull(at("select * from orders ^where 1 = 1"));
    }

    @Test void nothingResolvesInsideCommentsStringsOrWhitespace() {
        assertNull(at("select 1 -- ^orders"));
        assertNull(at("select '^orders'"));
        assertNull(at("select * from orders ^ where 1 = 1"));
        assertNull(at("select * from orders where id = ^1"));
    }

    @Test void aDerivedTableAliasHasNoTableBehindIt() {
        assertNull(at("select * from (select 1) ^x"));
    }

    @Test void theSameTextResolvesPerDialectQualification() {
        assertEquals(new SqlTarget.Table("PUBLIC", "orders"), at("select * from ^orders", SqlDialects.POSTGRESQL));
        assertEquals(new SqlTarget.Table("PUBLIC", "orders"), at("select * from ^orders", SqlDialects.ANSI));
    }
}
