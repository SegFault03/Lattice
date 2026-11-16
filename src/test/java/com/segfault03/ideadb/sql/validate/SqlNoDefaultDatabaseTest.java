package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.completion.SqlCompletionEngine;
import com.segfault03.ideadb.sql.completion.SqlStatementAnalyzer;
import com.segfault03.ideadb.sql.completion.SqlSuggestion;
import com.segfault03.ideadb.sql.completion.SqlUseStatements;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A MySQL connection whose URL names no database: unqualified names are looked up in every user database. */
class SqlNoDefaultDatabaseTest {
    private static ExplorerMetadataSnapshot.Table table(String database, String name, String... columns) {
        var metadata = new TableMetadata(database, null, name, "TABLE");
        var list = Arrays.stream(columns).map(column -> new ColumnMetadata(column, "INT", Types.INTEGER, 10, 0, true, false, false, null)).toList();
        metadata.setColumns(list);
        return new ExplorerMetadataSnapshot.Table(metadata, list, true);
    }

    private static SqlSchema schema() {
        var snapshot = new ExplorerMetadataSnapshot(List.of(
                new ExplorerMetadataSnapshot.Namespace("billing", List.of(table("billing", "invoices", "id", "amount"),
                        table("billing", "accounts", "id", "owner")), true),
                new ExplorerMetadataSnapshot.Namespace("mysql", List.of(table("mysql", "user", "Host", "User")), true),
                new ExplorerMetadataSnapshot.Namespace("shop", List.of(table("shop", "customers", "id", "email"),
                        table("shop", "accounts", "id", "customer_id")), true)), List.of(), 3);
        return SqlSchema.of(new ConnectionConfig(DatabaseType.MYSQL, "server"), snapshot, null, true, 1);
    }

    private static List<SqlDiagnostic> check(String sql) { return SqlScriptValidator.validate(sql, SqlDialects.MYSQL, schema()); }

    @Test void unqualifiedNamesResolveWhenOneDatabaseHoldsThem() {
        var schema = schema();
        assertTrue(schema.searchesAllNamespaces());
        assertEquals("shop", schema.namespaceOf(schema.table(null, "customers")).name());
        assertNull(schema.table(null, "accounts"), "two databases hold accounts");
        assertNull(schema.table(null, "user"), "system databases are not searched");
        assertEquals(List.of("billing", "shop"), schema.searched().stream().map(ExplorerMetadataSnapshot.Namespace::name).toList());
        assertTrue(schema.isComplete(null));
    }

    @Test void validationAcceptsUniqueNamesAndColumns() {
        assertEquals(List.of(), check("select id, email from customers"));
        assertEquals(List.of(), check("select i.amount from invoices i"));
        assertEquals(List.of(), check("select a.owner from billing.accounts a"));
    }

    @Test void validationReportsAmbiguousAndUnknownTables() {
        var ambiguous = check("select * from accounts");
        assertEquals(1, ambiguous.size());
        assertEquals(Kind.UNKNOWN_TABLE, ambiguous.get(0).kind());
        assertTrue(ambiguous.get(0).message().contains("billing, shop"), ambiguous.get(0).message());

        var unknown = check("select * from customer");
        assertEquals(1, unknown.size());
        assertTrue(unknown.get(0).message().contains("Did you mean 'customers'"), unknown.get(0).message());

        var column = check("select nope from customers");
        assertEquals(1, column.size());
        assertEquals(Kind.UNKNOWN_COLUMN, column.get(0).kind());
    }

    @Test void useSelectsTheDatabaseForLaterStatements() {
        assertEquals(List.of(), check("use shop; select customer_id from accounts"));
        var wrong = check("use billing; select * from customers");
        assertEquals(1, wrong.size(), wrong.toString());
        assertEquals(Kind.UNKNOWN_TABLE, wrong.get(0).kind());
        assertEquals("shop", SqlUseStatements.namespaceAt("use `billing`; use shop; select ", 30, SqlDialects.MYSQL));
        assertNull(SqlUseStatements.namespaceAt("use shop; select ", 17, SqlDialects.POSTGRESQL));
    }

    @Test void completionOffersEveryDatabasesTablesQualified() {
        String sql = "select * from ";
        var context = SqlStatementAnalyzer.analyze(sql, sql.length(), SqlDialects.MYSQL);
        var inserted = SqlCompletionEngine.suggest(context, SqlDialects.MYSQL, schema()).stream()
                .filter(s -> s.kind() == SqlSuggestion.Kind.TABLE).map(SqlSuggestion::insertText).toList();
        assertTrue(inserted.containsAll(List.of("billing.invoices", "billing.accounts", "shop.customers", "shop.accounts")), inserted.toString());
        assertFalse(inserted.contains("mysql.user"), inserted.toString());

        var used = schema().withDefaultNamespace("shop");
        var plain = SqlCompletionEngine.suggest(context, SqlDialects.MYSQL, used).stream()
                .filter(s -> s.kind() == SqlSuggestion.Kind.TABLE).map(SqlSuggestion::insertText).toList();
        assertEquals(List.of("customers", "accounts"), plain);
    }
}
