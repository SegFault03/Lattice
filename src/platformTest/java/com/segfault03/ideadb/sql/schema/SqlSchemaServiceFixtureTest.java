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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
            SqlSchemaLoader.columnReadHook = table -> {};
            DatabaseConnectionManager.getInstance().closeConnection(h2.getId());
            MetadataCacheState.getInstance().remove(h2.getId());
            MetadataCacheState.getInstance().remove("other-" + h2.getId());
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

    private boolean columnsLoaded(String table) {
        return service.schema(h2).table(null, table).columnsLoaded();
    }

    public void testATableWhoseColumnsCannotBeReadDoesNotMakeTheSchemaLoadAgainAndAgain() {
        AtomicInteger attempts = new AtomicInteger();
        SqlSchemaLoader.columnReadHook = table -> {
            if (table.equals("BOOKS")) {
                attempts.incrementAndGet();
                throw new IllegalStateException("no access to BOOKS");
            }
        };
        service.ensureLoaded(h2);
        awaitAnnouncement();
        assertTrue(columnsLoaded("AUTHORS"));
        assertFalse("the unreadable table stays unknown to the checks", columnsLoaded("BOOKS"));
        assertEquals(1, attempts.get());

        service.ensureLoaded(h2);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse("A table that failed counts as attempted", service.isLoading(h2.getId()));
        assertEquals(1, attempts.get());
        assertTrue(events.isEmpty());
    }

    public void testASecondChangeDuringARefreshKeepsTheSchemaStale() throws Exception {
        service.ensureLoaded(h2);
        awaitAnnouncement();
        DatabaseChangeListener.publish(DatabaseChange.connection(h2.getId()));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue(service.isStale(h2.getId()));

        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        SqlSchemaLoader.columnReadHook = table -> {
            if (!first.compareAndSet(true, false)) return;
            reading.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        service.ensureLoaded(h2);
        assertTrue("the refresh never started reading", reading.await(30, TimeUnit.SECONDS));
        DatabaseChangeListener.publish(DatabaseChange.connection(h2.getId()));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        release.countDown();
        awaitAnnouncement();
        assertTrue("The refresh began before the second change, so it does not cover it", service.isStale(h2.getId()));

        SqlSchemaLoader.columnReadHook = table -> {};
        service.ensureLoaded(h2);
        PlatformTestUtil.waitWithEventsDispatching("The second refresh never finished", () -> !service.isStale(h2.getId()) && !service.isLoading(h2.getId()), 30);
    }

    public void testABurstOfTableChangesIsOneReadAndAHugeBurstIsAConnectionWideChange() throws Exception {
        service.ensureLoaded(h2);
        awaitAnnouncement();
        AtomicInteger reads = new AtomicInteger();
        SqlSchemaLoader.columnReadHook = table -> reads.incrementAndGet();
        sql("ALTER TABLE AUTHORS ADD COLUMN BIO VARCHAR(20)", "ALTER TABLE BOOKS ADD COLUMN SUBTITLE VARCHAR(20)");
        for (int i = 0; i < 3; i++) {
            DatabaseChangeListener.publish(DatabaseChange.table(h2.getId(), "PUBLIC", "AUTHORS"));
            DatabaseChangeListener.publish(DatabaseChange.table(h2.getId(), "PUBLIC", "BOOKS"));
        }
        awaitAnnouncement();
        assertTrue(hasColumn("AUTHORS", "BIO"));
        assertTrue(hasColumn("BOOKS", "SUBTITLE"));
        assertEquals("each changed table is read once for the whole burst", 2, reads.get());
        assertFalse(service.isStale(h2.getId()));

        for (int i = 0; i < 25; i++) DatabaseChangeListener.publish(DatabaseChange.table(h2.getId(), "PUBLIC", "T" + i));
        PlatformTestUtil.waitWithEventsDispatching("A burst of table changes never became a connection-wide change", () -> service.isStale(h2.getId()), 30);
    }

    public void testAChangeToAnotherConnectionKeepsThisConnectionsSchemaView() {
        service.ensureLoaded(h2);
        awaitAnnouncement();
        SqlSchema view = service.schema(h2);
        ConnectionConfig other = new ConnectionConfig(DatabaseType.H2, "other H2");
        other.setId("other-" + h2.getId());
        other.setDatabaseName("other_" + UUID.randomUUID());
        MetadataCacheState.getInstance().tablesRefreshed(other, "PUBLIC", List.of());
        assertSame("Only changes to this connection rebuild its view", view, service.schema(h2));

        MetadataCacheState.getInstance().columnsRefreshed(h2, "PUBLIC", "AUTHORS", List.of());
        assertNotSame(view, service.schema(h2));
    }

    public void testEditingTheSavedConnectionDropsTheCachedView() {
        service.ensureLoaded(h2);
        awaitAnnouncement();
        SqlSchema view = service.schema(h2);
        assertNotNull(view);
        assertSame("nothing changed, so the view is reused", view, service.schema(h2));

        DatabaseSettingsState.getInstance().updateConnection(h2);
        assertSame("a save that leaves the connection as it was keeps its view", view, service.schema(h2));

        h2.setDatabaseName("renamed_" + UUID.randomUUID());
        DatabaseSettingsState.getInstance().updateConnection(h2);
        assertNull("the cache was read for another database", service.schema(h2));
    }
}
