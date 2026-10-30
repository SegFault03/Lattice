package com.segfault03.ideadb.sql.discovery;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.HsqlMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlsTest {
    private static ParsedJdbcUrl parse(String url) {
        ParsedJdbcUrl parsed = JdbcUrls.parse(url);
        assertNotNull(parsed, url);
        return parsed;
    }

    @Test void mysqlAndMariaDbUrlsAreMysql() {
        assertEquals(new ParsedJdbcUrl(DatabaseType.MYSQL, null, "db.example.com", 3307, "shop"), parse("jdbc:mysql://db.example.com:3307/shop?useSSL=false&serverTimezone=UTC"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.MYSQL, null, "localhost", 0, "shop"), parse("jdbc:mysql://localhost/shop"));
        assertEquals(DatabaseType.MYSQL, parse("jdbc:mariadb://localhost:3306/shop").type());
        assertEquals("host1", parse("jdbc:mysql://host1:3306,host2:3306/shop").host(), "the first of several hosts");
        assertEquals("shop", parse("jdbc:mysql:replication://primary,replica/shop").database());
        assertEquals("", parse("jdbc:mysql://localhost:3306/").database());
    }

    @Test void postgresUrlsIncludingTheShortForm() {
        assertEquals(new ParsedJdbcUrl(DatabaseType.POSTGRESQL, null, "pg", 5433, "shop"), parse("jdbc:postgresql://pg:5433/shop?currentSchema=app&ssl=true"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.POSTGRESQL, null, "localhost", 0, "shop"), parse("jdbc:postgresql:shop"));
        assertEquals("[::1]", parse("jdbc:postgresql://[::1]:5432/shop").host());
        assertEquals("Order Db", parse("jdbc:postgresql://pg/Order%20Db").database());
    }

    @Test void h2UrlsByHowTheyConnect() {
        assertEquals(new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.MEM, null, 0, "testdb"), parse("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.FILE, null, 0, "./data/db"), parse("jdbc:h2:file:./data/db;AUTO_SERVER=TRUE"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.FILE, null, 0, "~/test"), parse("jdbc:h2:~/test"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.SERVER, "dbhost", 9093, "~/test"), parse("jdbc:h2:tcp://dbhost:9093/~/test"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.H2, HsqlMode.SERVER, "localhost", 0, "mem:demo"), parse("jdbc:h2:tcp://localhost/mem:demo"));
        assertEquals("", parse("jdbc:h2:mem:").database());
    }

    @Test void hsqldbUrlsByHowTheyConnect() {
        assertEquals(new ParsedJdbcUrl(DatabaseType.HSQLDB, HsqlMode.MEM, null, 0, "demo"), parse("jdbc:hsqldb:mem:demo;shutdown=true"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.HSQLDB, HsqlMode.FILE, null, 0, "data/demo"), parse("jdbc:hsqldb:file:data/demo;hsqldb.lock_file=false"));
        assertEquals(new ParsedJdbcUrl(DatabaseType.HSQLDB, HsqlMode.SERVER, "localhost", 9001, "demo"), parse("jdbc:hsqldb:hsql://localhost:9001/demo"));
    }

    @Test void wrappersAreLookedThrough() {
        assertEquals(new ParsedJdbcUrl(DatabaseType.POSTGRESQL, null, "pg", 5432, "shop"), parse("jdbc:p6spy:postgresql://pg:5432/shop"));
        var container = parse("jdbc:tc:postgresql:16-alpine:///shop");
        assertEquals(DatabaseType.POSTGRESQL, container.type());
        assertNull(container.host(), "a Testcontainers host is only known at run time");
        assertEquals("shop", container.database());
        assertEquals(DatabaseType.MYSQL, parse("jdbc:tc:mysql:///test").type());
    }

    @Test void otherDatabasesAndOtherTextAreNotOurs() {
        assertNull(JdbcUrls.parse("jdbc:oracle:thin:@//host:1521/svc"));
        assertNull(JdbcUrls.parse("jdbc:sqlserver://host;databaseName=x"));
        assertNull(JdbcUrls.parse("r2dbc:postgresql://host/db"));
        assertNull(JdbcUrls.parse("postgresql://host/db"));
        assertNull(JdbcUrls.parse(""));
        assertNull(JdbcUrls.parse(null));
        assertNull(JdbcUrls.parse("jdbc:h2:"));
    }

    @Test void credentialsAndOptionsNeverSurviveParsing() {
        for (String url : new String[] {
                "jdbc:mysql://admin:s3cr3t@localhost:3306/shop?user=root&password=hunter2",
                "jdbc:postgresql://localhost/shop?user=bob&password=hunter2&sslpassword=x",
                "jdbc:h2:mem:shop;USER=sa;PASSWORD=hunter2",
                "jdbc:h2:tcp://localhost/shop;PASSWORD=hunter2",
                "jdbc:hsqldb:hsql://localhost/shop;password=hunter2",
                "jdbc:tc:postgresql:15:///shop?TC_INITSCRIPT=x&password=hunter2"}) {
            var parsed = parse(url);
            String everything = parsed + "|" + parsed.describe() + "|" + parsed.host() + "|" + parsed.database() + "|" + parsed.toConnection("n").buildJdbcUrl()
                    + "|" + parsed.toConnection("n").getUser() + "|" + parsed.toConnection("n").getPassword();
            for (String secret : new String[] {"hunter2", "s3cr3t", "admin", "bob", "root", "sslpassword", "TC_INIT"}) {
                assertFalse(everything.contains(secret), url + " leaked " + secret + " in " + everything);
            }
        }
    }

    @Test void theSameDatabaseIsRecognizedAcrossSpellings() {
        assertTrue(parse("jdbc:mysql://127.0.0.1:3306/Shop").sameDatabaseAs(parse("jdbc:mysql://localhost/shop")));
        assertTrue(parse("jdbc:postgresql://localhost/shop").sameDatabaseAs(parse("jdbc:postgresql://localhost:5432/shop?x=1")));
        assertFalse(parse("jdbc:postgresql://localhost:5433/shop").sameDatabaseAs(parse("jdbc:postgresql://localhost/shop")));
        assertFalse(parse("jdbc:postgresql://a/shop").sameDatabaseAs(parse("jdbc:postgresql://b/shop")));
        assertFalse(parse("jdbc:postgresql://localhost/shop").sameDatabaseAs(parse("jdbc:postgresql://localhost/other")));
        assertFalse(parse("jdbc:mysql://localhost/shop").sameDatabaseAs(parse("jdbc:postgresql://localhost/shop")));
        assertTrue(parse("jdbc:h2:file:./data/db").sameDatabaseAs(parse("jdbc:h2:file:data/db.mv.db")));
        assertFalse(parse("jdbc:h2:mem:a").sameDatabaseAs(parse("jdbc:h2:mem:b")));
        assertFalse(parse("jdbc:h2:mem:a").sameDatabaseAs(parse("jdbc:h2:file:a")), "memory is not a file");
        assertTrue(parse("jdbc:mysql://localhost:3306/").sameDatabaseAs(parse("jdbc:mysql://localhost/shop")), "a URL without a database fits any");
    }

    @Test void anUnresolvedPartMatchesAnything() {
        char unknown = JdbcUrls.UNKNOWN;
        var wildHost = parse("jdbc:postgresql://" + unknown + ":5432/shop");
        assertNull(wildHost.host());
        assertTrue(wildHost.sameDatabaseAs(parse("jdbc:postgresql://anywhere/shop")));
        assertFalse(wildHost.sameDatabaseAs(parse("jdbc:postgresql://anywhere/other")));
        var wildDatabase = parse("jdbc:mysql://db:3306/" + unknown);
        assertNull(wildDatabase.database());
        assertTrue(wildDatabase.sameDatabaseAs(parse("jdbc:mysql://db/shop")));
    }

    @Test void aSavedConnectionMatchesThroughItsOwnUrl() {
        var postgres = new ConnectionConfig(DatabaseType.POSTGRESQL, "pg");
        postgres.setHost("localhost");
        postgres.setPort(5432);
        postgres.setDatabaseName("shop");
        assertTrue(parse("jdbc:postgresql://127.0.0.1:5432/shop").matches(postgres));
        assertFalse(parse("jdbc:postgresql://127.0.0.1:5432/other").matches(postgres));

        var h2 = new ConnectionConfig(DatabaseType.H2, "h2");
        h2.setHsqlMode(HsqlMode.MEM);
        h2.setDatabaseName("testdb");
        assertTrue(parse("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1").matches(h2));

        var custom = new ConnectionConfig(DatabaseType.MYSQL, "custom");
        custom.setCustomUrl("jdbc:mysql://db.internal:3310/shop?useSSL=false");
        assertTrue(parse("jdbc:mysql://db.internal:3310/shop").matches(custom), "a custom URL is compared as written");
        assertFalse(parse("jdbc:mysql://localhost:3306/shop").matches(custom));
    }

    @Test void aConnectionMadeFromAUrlCarriesTheTargetOnly() {
        var config = parse("jdbc:postgresql://pg.example:5433/shop?user=bob&password=x").toConnection("shop (PostgreSQL)");
        assertEquals(DatabaseType.POSTGRESQL, config.getType());
        assertEquals("pg.example", config.getHost());
        assertEquals(5433, config.getPort());
        assertEquals("shop", config.getDatabaseName());
        assertEquals("", config.getUser());
        assertEquals("", config.getPassword());
        assertEquals("", config.getCustomUrl());
        var embedded = parse("jdbc:h2:file:./data/db").toConnection("db");
        assertEquals(HsqlMode.FILE, embedded.getHsqlMode());
        assertEquals("./data/db", embedded.getDatabaseName());
    }

    @Test void aDescriptionNamesTheTargetForAPerson() {
        assertEquals("PostgreSQL pg:5432/shop", parse("jdbc:postgresql://pg/shop").describe());
        assertEquals("H2 in-memory testdb", parse("jdbc:h2:mem:testdb").describe());
        assertEquals("H2 file ./data/db", parse("jdbc:h2:file:./data/db").describe());
        assertEquals("MySQL localhost:3306/shop", parse("jdbc:mysql://localhost/shop").describe());
    }

    @Test void onlyTheUserParameterIsReadFromTheQuery() {
        assertEquals("bob", JdbcUrls.userOf("jdbc:postgresql://localhost/shop?user=bob&password=hunter2"));
        assertEquals("bob", JdbcUrls.userOf("jdbc:postgresql://localhost/shop?password=hunter2&USER=bob"));
        assertEquals("sa", JdbcUrls.userOf("jdbc:h2:mem:shop;USER=sa;PASSWORD=hunter2"));
        assertEquals("a b", JdbcUrls.userOf("jdbc:mysql://h/d?user=a%20b"));
        assertNull(JdbcUrls.userOf("jdbc:mysql://h/d?password=hunter2"));
        assertNull(JdbcUrls.userOf("jdbc:mysql://h/d?username=bob"), "a longer name is another parameter");
        assertNull(JdbcUrls.userOf("jdbc:mysql://admin:s3cr3t@h/d"), "the user of the authority part is not read");
        assertNull(JdbcUrls.userOf("jdbc:mysql://h/d?user="));
        assertNull(JdbcUrls.userOf(null));
    }
}
