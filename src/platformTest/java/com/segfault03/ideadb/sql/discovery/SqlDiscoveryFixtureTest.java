package com.segfault03.ideadb.sql.discovery;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.FixtureConnections;
import com.segfault03.ideadb.sql.binding.AddConnectionFromProjectAction;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlConnectionBanner;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.settings.SqlFileTypeClaim;
import com.segfault03.ideadb.state.DatabaseSettingsState;

import java.util.List;

/** A sample Spring project: what its configuration files say decides the dialect and the connection of its SQL files. */
public class SqlDiscoveryFixtureTest extends BasePlatformTestCase {
    private DatabaseSettingsState connections;
    private JdbcDiscovery discovery;

    @Override protected void setUp() throws Exception {
        super.setUp();
        connections = FixtureConnections.install(getTestRootDisposable(), false);
        SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
        SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        SqlFileTypeClaim.apply(true);
        discovery = JdbcDiscovery.getInstance(getProject());
    }

    @Override protected void tearDown() throws Exception {
        try {
            SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
            SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        } finally {
            super.tearDown();
        }
    }

    private ConnectionConfig saved(DatabaseType type, String name, String host, int port, String database) {
        ConnectionConfig config = new ConnectionConfig(type, name);
        config.setHost(host);
        config.setPort(port);
        config.setDatabaseName(database);
        connections.addConnection(config);
        return config;
    }

    /** Whether the Tools menu action is shown, without running it: running it opens a modal dialog. */
    private boolean actionAvailable() {
        var action = new AddConnectionFromProjectAction();
        var event = com.intellij.testFramework.TestActionEvent.createTestEvent(action,
                com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(getProject()));
        action.update(event);
        return event.getPresentation().isEnabledAndVisible();
    }

    private VirtualFile sqlFile() { return myFixture.addFileToProject("queries/report.sql", "select 1").getVirtualFile(); }

    private static final String SPRING = """
            spring:
              datasource:
                url: jdbc:postgresql://${DB_HOST:localhost}:5432/shop
                username: shop_app
                password: hunter2
            """;

    public void testTheProjectsConfigurationFilesAreFoundAndSecretsAreNot() {
        myFixture.addFileToProject("src/main/resources/application.yml", SPRING);
        myFixture.addFileToProject("src/main/resources/application-test.properties", "spring.datasource.url=jdbc:h2:mem:testdb\nspring.datasource.password=hunter2\n");
        myFixture.addFileToProject("src/main/resources/META-INF/persistence.xml",
                "<persistence><persistence-unit name='u'><properties><property name='jakarta.persistence.jdbc.url' value='jdbc:mysql://db:3306/legacy'/></properties></persistence-unit></persistence>");
        myFixture.addFileToProject("build/resources/main/application.yml", "spring.datasource.url: jdbc:postgresql://built/copy\n");
        myFixture.addFileToProject("src/main/resources/messages.properties", "greeting=jdbc:mysql://not/config\n");
        discovery.refreshNow();

        var described = discovery.snapshot().stream().map(DiscoveredConnection::describe).sorted().toList();
        assertEquals(described.toString(), List.of("H2 in-memory testdb (application-test.properties)", "MySQL db:3306/legacy (persistence.xml)",
                "PostgreSQL localhost:5432/shop (application.yml)"), described);
        assertFalse(discovery.snapshot().toString().contains("hunter2"));
        assertFalse(discovery.snapshot().toString().contains("shop_app"));
    }

    public void testASavedConnectionThatTheFilesPointAtAppliesWithoutBeingChosen() {
        ConnectionConfig postgres = saved(DatabaseType.POSTGRESQL, "shop-pg", "127.0.0.1", 5432, "shop");
        saved(DatabaseType.MYSQL, "other-mysql", "localhost", 3306, "other");
        myFixture.addFileToProject("src/main/resources/application.yml", SPRING);
        discovery.refreshNow();
        VirtualFile file = sqlFile();

        assertEquals(postgres.getId(), SqlDialectResolver.connection(getProject(), file).getId());
        assertEquals(SqlDialects.POSTGRESQL, SqlDialectResolver.resolve(getProject(), file));
        assertTrue(SqlDialectResolver.isFromProjectFiles(getProject(), file));
        assertNull("nothing to offer: the file already has its connection", new SqlConnectionBanner().collectNotificationData(getProject(), file));
    }

    public void testAChoiceStillBeatsWhatTheFilesSay() {
        saved(DatabaseType.POSTGRESQL, "shop-pg", "localhost", 5432, "shop");
        ConnectionConfig mysql = saved(DatabaseType.MYSQL, "other-mysql", "localhost", 3306, "other");
        myFixture.addFileToProject("src/main/resources/application.yml", SPRING);
        discovery.refreshNow();
        VirtualFile file = sqlFile();

        SqlBindingsState.getInstance(getProject()).setDefaultConnectionId(mysql.getId());
        assertEquals("the project default wins", mysql.getId(), SqlDialectResolver.connection(getProject(), file).getId());
        assertFalse(SqlDialectResolver.isFromProjectFiles(getProject(), file));
        SqlBindingsState.getInstance(getProject()).bindFileToDialect(file, SqlDialects.H2.id());
        assertEquals("the file's own choice wins", SqlDialects.H2, SqlDialectResolver.resolve(getProject(), file));
    }

    public void testFilesThatPointAtTwoDifferentSavedConnectionsAreNotGuessedBetween() {
        saved(DatabaseType.POSTGRESQL, "dev", "localhost", 5432, "shop");
        saved(DatabaseType.POSTGRESQL, "test", "localhost", 5433, "shop_test");
        myFixture.addFileToProject("src/main/resources/application.yml", "spring.datasource.url: jdbc:postgresql://localhost:5432/shop\n");
        myFixture.addFileToProject("src/main/resources/application-test.yml", "spring.datasource.url: jdbc:postgresql://localhost:5433/shop_test\n");
        discovery.refreshNow();
        VirtualFile file = sqlFile();

        assertNull(SqlDialectResolver.connection(getProject(), file));
        assertEquals("both are PostgreSQL, so the dialect is still known", SqlDialects.POSTGRESQL, SqlDialectResolver.resolve(getProject(), file));
    }

    public void testWithoutASavedConnectionTheFilesStillSayWhichDialect() {
        myFixture.addFileToProject("src/main/resources/application.properties", "spring.datasource.url=jdbc:mysql://db:3306/shop\n");
        discovery.refreshNow();
        VirtualFile file = sqlFile();
        assertNull(SqlDialectResolver.connection(getProject(), file));
        assertEquals(SqlDialects.MYSQL, SqlDialectResolver.resolve(getProject(), file));
    }

    public void testFilesThatDisagreeOnTheDatabaseTypeLeaveItGeneric() {
        myFixture.addFileToProject("src/main/resources/application.yml", SPRING);
        myFixture.addFileToProject("src/main/resources/application-test.properties", "spring.datasource.url=jdbc:h2:mem:testdb\n");
        discovery.refreshNow();
        assertEquals(SqlDialects.ANSI, SqlDialectResolver.resolve(getProject(), sqlFile()));
    }

    public void testASingleSavedConnectionOfAnotherTypeStillBeatsTheDatabaseTypeInTheFiles() {
        saved(DatabaseType.H2, "scratch", "localhost", 9092, "scratch");
        myFixture.addFileToProject("src/main/resources/application.yml", SPRING);
        discovery.refreshNow();
        assertEquals("what the user saved is the stronger evidence", SqlDialects.H2, SqlDialectResolver.resolve(getProject(), sqlFile()));
    }

    public void testADatabaseThatIsNotSavedYetIsOfferedOnTheFile() {
        myFixture.addFileToProject("src/main/resources/application.yml", SPRING);
        discovery.refreshNow();
        VirtualFile file = sqlFile();

        var offers = ProjectConnectionOffer.open(getProject());
        assertEquals(1, offers.size());
        assertEquals("PostgreSQL localhost:5432/shop (application.yml)", offers.get(0).describe());
        assertNotNull("the banner offers to add it", new SqlConnectionBanner().collectNotificationData(getProject(), file));
        assertTrue(actionAvailable());

        var config = offers.get(0).url().toConnection(offers.get(0).suggestedName());
        assertEquals("shop (PostgreSQL)", config.getName());
        assertEquals("", config.getUser());
        assertEquals("", config.getPassword());
        assertEquals("localhost", config.getHost());
    }

    public void testTurningTheOfferDownSilencesIt() {
        myFixture.addFileToProject("src/main/resources/application.yml", SPRING);
        discovery.refreshNow();
        VirtualFile file = sqlFile();
        SqlBindingsState.getInstance(getProject()).dismissDiscovery(ProjectConnectionOffer.open(getProject()).get(0).describe());
        assertTrue(ProjectConnectionOffer.open(getProject()).isEmpty());
        assertNull(new SqlConnectionBanner().collectNotificationData(getProject(), file));
        assertFalse(actionAvailable());
    }

    public void testNothingIsOfferedWhenTheFilesNameNoDatabase() {
        myFixture.addFileToProject("src/main/resources/application.yml", "server:\n  port: 8080\n");
        discovery.refreshNow();
        assertTrue(discovery.snapshot().isEmpty());
        assertNull(new SqlConnectionBanner().collectNotificationData(getProject(), sqlFile()));
        assertFalse(actionAvailable());
    }

    public void testEditingAConfigurationFileIsNoticed() {
        var file = myFixture.addFileToProject("src/main/resources/application.properties", "spring.datasource.url=jdbc:mysql://db:3306/one\n");
        discovery.refreshNow();
        assertEquals("MySQL db:3306/one", discovery.snapshot().get(0).url().describe());

        myFixture.saveText(file.getVirtualFile(), "spring.datasource.url=jdbc:mysql://db:3306/two\n");
        PlatformTestUtil.waitWithEventsDispatching("The change to application.properties was never noticed",
                () -> !discovery.snapshot().isEmpty() && discovery.snapshot().get(0).url().describe().endsWith("/two"), 30);
    }
}
