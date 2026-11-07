package com.segfault03.ideadb.sql;

import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.settings.SqlFileTypeClaim;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.MetadataCacheState;

import java.sql.Connection;
import java.util.UUID;

/** A fixture project whose only saved connection is an in-memory H2 database created from {@link #schemaStatements()}. */
public abstract class H2SqlFixtureTestCase extends BasePlatformTestCase {
    protected DatabaseSettingsState connections;
    protected ConnectionConfig h2;

    protected abstract String[] schemaStatements();

    @Override protected void setUp() throws Exception {
        super.setUp();
        connections = FixtureConnections.install(getTestRootDisposable(), true);
        SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
        SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        SqlFileTypeClaim.apply(true);
        h2 = new ConnectionConfig(DatabaseType.H2, "fixture H2");
        h2.setDatabaseName("fixture_" + UUID.randomUUID());
        connections.addConnection(h2);
        Connection connection = DatabaseConnectionManager.getInstance().getConnection(h2);
        synchronized (connection) {
            try (var statement = connection.createStatement()) {
                for (String sql : schemaStatements()) statement.execute(sql);
            }
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            DatabaseConnectionManager.getInstance().closeConnection(h2.getId());
            MetadataCacheState.getInstance().remove(h2.getId());
            SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
            SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        } finally {
            super.tearDown();
        }
    }

    /** Completion and hover start the read themselves; tests that need the schema ready ask for it up front. */
    protected void loadSchema() {
        SqlSchemaService service = SqlSchemaService.getInstance();
        service.ensureLoaded(h2);
        PlatformTestUtil.waitWithEventsDispatching("The schema never loaded", () -> {
            var schema = service.schema(h2);
            return schema != null && schema.isComplete(null);
        }, 30);
    }
}
