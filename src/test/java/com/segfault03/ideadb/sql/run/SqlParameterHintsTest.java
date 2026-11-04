package com.segfault03.ideadb.sql.run;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlParameterHintsTest {
    private static final ConnectionConfig CONFIG = new ConnectionConfig(DatabaseType.MYSQL, "hints");

    private static ColumnMetadata column(String name, String type, int jdbc, int size, boolean nullable) {
        return new ColumnMetadata(name, type, jdbc, size, 0, nullable, false, false, null);
    }

    private static final SqlSchema SCHEMA = SqlSchema.of(CONFIG, new ExplorerMetadataSnapshot(List.of(new ExplorerMetadataSnapshot.Namespace("shop", List.of(
            new ExplorerMetadataSnapshot.Table(new TableMetadata("shop", null, "orders", "TABLE"), List.of(
                    column("id", "BIGINT", Types.BIGINT, 19, false),
                    column("customer_id", "INT", Types.INTEGER, 10, false),
                    column("placed_at", "DATETIME", Types.TIMESTAMP, 19, true),
                    column("shipped", "BIT", Types.BIT, 1, true)), true),
            new ExplorerMetadataSnapshot.Table(new TableMetadata("shop", null, "customers", "TABLE"), List.of(
                    column("id", "INT", Types.INTEGER, 10, false),
                    column("name", "VARCHAR", Types.VARCHAR, 80, true),
                    column("born", "DATE", Types.DATE, 10, true)), true)), true)), List.of(), 3), "shop", 1);

    private static Map<String, SqlParameterHints.Hint> hints(String sql, SqlSchema schema) {
        return SqlParameterHints.of(sql, SqlDialects.MYSQL, SqlParameters.of(sql, SqlDialects.MYSQL, false), schema);
    }

    @Test void comparisonTakesTheColumnsType() {
        var hints = hints("select * from orders where customer_id = :customer and placed_at >= :since", SCHEMA);
        assertEquals("orders.customer_id", hints.get(":customer").column());
        assertEquals(SqlParameterValue.Type.NUMBER, hints.get(":customer").suggested());
        assertFalse(hints.get(":customer").nullable());
        assertEquals(SqlParameterValue.Type.TIMESTAMP, hints.get(":since").suggested());
    }

    @Test void parameterOnTheLeftAndAliasesResolve() {
        var hints = hints("select * from orders o join customers c on c.id = o.customer_id where ? < c.born and c.name like ?", SCHEMA);
        assertEquals("customers.born", hints.get("1").column());
        assertEquals(SqlParameterValue.Type.DATE, hints.get("1").suggested());
        assertEquals(SqlParameterValue.Type.TEXT, hints.get("2").suggested());
        assertTrue(hints.get("2").describe().startsWith("LIKE pattern"), hints.get("2").describe());
    }

    @Test void inBetweenAndCounts() {
        var hints = hints("select * from orders where id in (?, ?) and placed_at between ? and ? limit ? offset ?", SCHEMA);
        assertEquals(SqlParameterValue.Type.NUMBER, hints.get("1").suggested());
        assertEquals("orders.id", hints.get("2").column());
        assertEquals(SqlParameterValue.Type.TIMESTAMP, hints.get("3").suggested());
        assertEquals(SqlParameterValue.Type.TIMESTAMP, hints.get("4").suggested());
        assertEquals("row count", hints.get("5").note());
        assertEquals("row count", hints.get("6").note());
    }

    @Test void insertMapsValuesToTheColumnList() {
        var hints = hints("insert into customers (name, born) values (?, ?), (?, ?)", SCHEMA);
        assertEquals("customers.name", hints.get("1").column());
        assertEquals(SqlParameterValue.Type.DATE, hints.get("2").suggested());
        assertEquals("customers.name", hints.get("3").column());
        assertEquals(SqlParameterValue.Type.DATE, hints.get("4").suggested());
    }

    @Test void insertWithoutAColumnListUsesTheTablesColumns() {
        var hints = hints("insert into customers values (?, ?, ?)", SCHEMA);
        assertEquals("customers.id", hints.get("1").column());
        assertEquals("customers.born", hints.get("3").column());
    }

    @Test void updateSetAndBitAsBoolean() {
        var hints = hints("update orders set shipped = ? where id = ?", SCHEMA);
        assertEquals(SqlParameterValue.Type.BOOLEAN, hints.get("1").suggested());
        assertEquals(SqlParameterValue.Type.NUMBER, hints.get("2").suggested());
    }

    @Test void ambiguousColumnKeepsOnlyItsName() {
        var hint = hints("select * from orders, customers where id = ?", SCHEMA).get("1");
        assertEquals("id", hint.column());
        assertNull(hint.suggested());
    }

    @Test void withoutASchemaTheColumnNameStillShows() {
        var hints = hints("select * from orders where customer_id = ? limit ?", null);
        assertEquals("customer_id", hints.get("1").column());
        assertNull(hints.get("1").suggested());
        assertEquals(SqlParameterValue.Type.NUMBER, hints.get("2").suggested());
    }

    @Test void aParameterWithNothingAroundItHasNoHint() {
        var hint = hints("select ?", SCHEMA).get("1");
        assertNull(hint.column());
        assertNull(hint.suggested());
        assertEquals("", hint.describe());
    }

    @Test void describeJoinsWhatIsKnown() {
        var hint = hints("select * from orders where customer_id = ?", SCHEMA).get("1");
        assertEquals("orders.customer_id · INT NOT NULL", hint.describe().replace("INT(10)", "INT"));
    }
}
