package com.segfault03.ideadb.sql.lang;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Which names of a statement get which role, over a schema with a key, a foreign key, a view and one column of each kind. */
class SqlSemanticAnnotatorTest {
    private static ColumnMetadata column(String name, String type, int dataType, boolean key, ColumnReference reference) {
        var column = new ColumnMetadata(name, type, dataType, 10, 0, !key, key, false, null);
        return reference == null ? column : column.withReference(reference);
    }

    private static ExplorerMetadataSnapshot.Table table(String name, String kind, ColumnMetadata... columns) {
        var metadata = new TableMetadata(null, "PUBLIC", name, kind, null);
        metadata.setColumns(List.of(columns));
        return new ExplorerMetadataSnapshot.Table(metadata, List.of(columns), true);
    }

    private final SqlSchema schema = SqlSchema.of(new ConnectionConfig(DatabaseType.MYSQL, "colors"), new ExplorerMetadataSnapshot(List.of(
            new ExplorerMetadataSnapshot.Namespace("PUBLIC", List.of(
                    table("customers", "TABLE", column("id", "INTEGER", Types.INTEGER, true, null), column("name", "VARCHAR", Types.VARCHAR, false, null)),
                    table("orders", "TABLE", column("id", "INTEGER", Types.INTEGER, true, null), column("total", "DECIMAL", Types.DECIMAL, false, null),
                            column("customer_id", "INTEGER", Types.INTEGER, false, new ColumnReference("PUBLIC", "customers", "id")),
                            column("created_at", "TIMESTAMP", Types.TIMESTAMP, false, null), column("paid", "BOOLEAN", Types.BOOLEAN, false, null),
                            column("receipt", "BLOB", Types.BLOB, false, null), column("meta", "JSON", Types.OTHER, false, null)),
                    table("open_orders", "VIEW", column("total", "DECIMAL", Types.DECIMAL, false, null))), true)), List.of(), 1), "PUBLIC", 1);

    /** The roles of the names in the statement, as "text:ROLE" in text order. */
    private List<String> roles(String sql) {
        List<String> found = new ArrayList<>();
        for (var colored : SqlSemanticAnnotator.colored(sql, SqlDialects.MYSQL, schema)) {
            found.add(sql.substring(colored.range().getStartOffset(), colored.range().getEndOffset()) + ":" + colored.role());
        }
        return found;
    }

    @Test void tablesViewsAndTheirAliasesGetTheirOwnRoles() {
        assertEquals(List.of("orders:TABLE", "o:ALIAS", "open_orders:VIEW", "v:ALIAS"),
                roles("SELECT * FROM orders o JOIN open_orders v"));
    }

    @Test void aQualifierFollowsTheAliasItNames() {
        assertEquals(List.of("c:ALIAS", "name:TEXT", "customers:TABLE", "c:ALIAS"), roles("SELECT c.name FROM customers c"));
    }

    @Test void keysAreToldApartFromOtherColumnsWhichFollowTheirDataType() {
        assertEquals(List.of("o:ALIAS", "id:PRIMARY_KEY", "o:ALIAS", "customer_id:FOREIGN_KEY", "o:ALIAS", "total:NUMERIC", "o:ALIAS", "created_at:TEMPORAL",
                        "o:ALIAS", "paid:BOOLEAN", "o:ALIAS", "receipt:BINARY", "o:ALIAS", "meta:OTHER", "orders:TABLE", "o:ALIAS"),
                roles("SELECT o.id, o.customer_id, o.total, o.created_at, o.paid, o.receipt, o.meta FROM orders o"));
    }

    @Test void unqualifiedColumnsResolveThroughTheStatementsTables() {
        assertEquals(List.of("id:PRIMARY_KEY", "name:TEXT", "customers:TABLE"), roles("SELECT id, name FROM customers"));
    }

    @Test void whatTheSchemaDoesNotKnowKeepsTheTokenColors() {
        assertEquals(List.of("customers:TABLE"), roles("SELECT missing, 1 + x AS total FROM customers WHERE missing > 2"));
        assertEquals(List.of(), roles("SELECT * FROM nowhere"));
    }

    @Test void eachStatementOfAScriptIsReadOnItsOwn() {
        assertEquals(List.of("customers:TABLE", "total:NUMERIC", "orders:TABLE"),
                roles("SELECT * FROM customers;\nSELECT total FROM orders;"));
    }

    @Test void quotedNamesAreColoredToo() {
        assertEquals(List.of("`name`:TEXT", "`customers`:TABLE"), roles("SELECT `name` FROM `customers`"));
    }
}
