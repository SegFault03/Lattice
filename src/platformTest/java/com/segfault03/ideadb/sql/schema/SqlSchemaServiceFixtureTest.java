package com.segfault03.ideadb.sql.schema;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.DatabaseChange;
import com.segfault03.ideadb.service.DatabaseChangeListener;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.sql.FixtureConnections;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.MetadataCacheState;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class SqlSchemaServiceFixtureTest extends BasePlatformTestCase {
    private DatabaseSettingsState connections;
    private SqlSchemaService service;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private ConnectionConfig h2;

    @Override protected void setUp() throws Exception {
        super.setUp();
        connections = FixtureConnections.install(getTestRootDisposable(), true);
        service = SqlSchemaService.getInstance();
        ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
                .subscribe(SqlSchemaListener.TOPIC, (SqlSchemaListener) events::add);
        h2 = new ConnectionConfig(DatabaseType.H2, "schema H2");
        h2.setDatabaseName("service_" + UUID.randomUUID());
        connections.addConnection(h2);
        sql("CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, AUTHOR_ID INTEGER NOT NULL, FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))");
    }

    @Override protected void tearDown() throws Exception {
        try {
            DatabaseConnectionManager.getInstance().closeConnection(h2.getId());
            MetadataCacheState.getInstance().remove(h2.getId());
        } finally {
            super.tearDown();
        }
    }

    private void sql(String... statements) throws Exception {
        Connection connection = DatabaseConnectionManager.getInstance().getConnection(h2);
        synchronized (connection) {
            try (var statement = connection.createStatement()) { for (String sql : statements) statement.execute(sql); }
        }
    }

    private void awaitAnnouncement() {
        PlatformTestUtil.waitWithEventsDispatching("The schema service never announced a result", () -> !events.isEmpty(), 30);
        events.clear();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    private boolean hasColumn(String table, String column) {
        SqlSchema schema = service.schema(h2);
        var found = schema == null ? null : schema.table(null, table);
        return found != null && found.columns().stream().map(ColumnMetadata::getName).anyMatch(column::equalsIgnoreCase);
    }

    public void testEnsureLoadedReadsInTheBackgroundAndAnnouncesIt() {
        assertNull("Nothing is cached before the first read", service.schema(h2));
        service.ensureLoaded(h2);
        awaitAnnouncement();

        SqlSchema schema = service.schema(h2);
        assertNotNull(schema);
        assertEquals("PUBLIC", schema.defaultNamespace());
        assertTrue(schema.isComplete(null));
        assertNotNull(schema.table(null, "authors"));
        var target = schema.table(null, "BOOKS").columns().stream().filter(c -> c.getName().equals("AUTHOR_ID")).findFirst().orElseThrow().getReference();
        assertEquals("AUTHORS", target.table());
        assertTrue(service.isServing(h2.getId()));
        assertSame("The view is reused while the cache is unchanged", schema, service.schema(h2));

        service.ensureLoaded(h2);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse("A schema that is already complete is not read again", service.isLoading(h2.getId()));
        assertTrue(events.isEmpty());
    }

    public void testAChangeToOneTableRereadsJustThatTable() throws Exception {
        service.ensureLoaded(h2);
        awaitAnnouncement();
        assertFalse(hasColumn("BOOKS", "SUBTITLE"));

        sql("ALTER TABLE BOOKS ADD COLUMN SUBTITLE VARCHAR(20)");
        DatabaseChangeListener.publish(DatabaseChange.table(h2.getId(), "PUBLIC", "BOOKS"));
        awaitAnnouncement();
        assertTrue(hasColumn("BOOKS", "SUBTITLE"));
    }

    public void testAConnectionWideChangeOnlyMarksTheSchemaStaleUntilItIsNeeded() throws Exception {
        service.ensureLoaded(h2);
        awaitAnnouncement();

        sql("ALTER TABLE AUTHORS ADD COLUMN BIO VARCHAR(20)");
        DatabaseChangeListener.publish(DatabaseChange.connection(h2.getId()));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse("Console statements report connection-wide changes constantly; they must not trigger reads", service.isLoading(h2.getId()));
        assertFalse(hasColumn("AUTHORS", "BIO"));

        service.ensureLoaded(h2);
        awaitAnnouncement();
        assertTrue("The next request refreshes a stale schema", hasColumn("AUTHORS", "BIO"));
    }

    public void testChangesToAConnectionNobodyReadIsIgnored() {
        DatabaseChangeListener.publish(DatabaseChange.table(h2.getId(), "PUBLIC", "BOOKS"));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse(service.isLoading(h2.getId()));
        assertFalse(service.isServing(h2.getId()));
        assertTrue("Following a change must never open a connection the SQL editor has not used", events.isEmpty());
    }

    public void testAnUnreachableConnectionFailsQuietlyAndIsNotRetriedAtOnce() {
        ConnectionConfig broken = new ConnectionConfig(DatabaseType.HSQLDB, "unreachable");
        broken.setCustomUrl("jdbc:hsqldb:hsql://127.0.0.1:1/none");
        connections.addConnection(broken);
        service.ensureLoaded(broken);
        awaitAnnouncement();
        assertNull(service.schema(broken));
        assertFalse(service.isServing(broken.getId()));

        service.ensureLoaded(broken);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse("A recent failure holds off the next attempt", service.isLoading(broken.getId()));
        assertTrue(events.isEmpty());
    }

    public void testRemovingAConnectionStopsFollowingIt() {
        service.ensureLoaded(h2);
        awaitAnnouncement();
        assertTrue(service.isServing(h2.getId()));
        connections.removeConnection(h2.getId());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse(service.isServing(h2.getId()));
    }
}
