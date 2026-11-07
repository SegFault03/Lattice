package com.segfault03.ideadb.sql.run;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.Presentation;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Send to Console: the statement at the caret lands in the bound connection's console, never executed. */
public class SendToConsoleFixtureTest extends H2SqlFixtureTestCase {
    private static final String ACTION_ID = "com.segfault03.lattice.sql.run.sendToConsole";

    private final List<String> opened = new ArrayList<>();
    private final List<SqlRunTarget> asked = new ArrayList<>();

    @Override protected String[] schemaStatements() { return new String[0]; }

    @Override protected void setUp() throws Exception {
        super.setUp();
        SqlRunActionSupport.setConsoleOpenerForTests((config, db, sql) -> {
            assertEquals(h2.getId(), config.getId());
            assertEquals(h2.getDatabaseName(), db);
            opened.add(sql);
        }, getTestRootDisposable());
    }

    private void provide(Map<String, SqlParameterValue> values) {
        SqlRunActionSupport.setValuesProviderForTests((target, initial) -> {
            asked.add(target);
            return values;
        }, getTestRootDisposable());
    }

    private Presentation send() {
        return myFixture.testAction(ActionManager.getInstance().getAction(ACTION_ID));
    }

    public void testSendsCaretStatementWithoutRunning() {
        DatabaseConnectionManager.getInstance().closeConnection(h2.getId());
        assertFalse(DatabaseConnectionManager.getInstance().isConnected(h2.getId()));
        myFixture.configureByText("q.sql", "select 1;\nselect * from t where <caret>id = 1;\nselect 3;");
        send();
        assertEquals(List.of("select * from t where id = 1"), opened);
        assertTrue(asked.isEmpty());
        assertFalse(DatabaseConnectionManager.getInstance().isConnected(h2.getId()));
    }

    public void testSendsParametersAsWrittenWithoutAskingForValues() {
        provide(Map.of("1", new SqlParameterValue(SqlParameterValue.Type.TEXT, "42")));
        myFixture.configureByText("q.sql", "select * from t where a = <caret>? and b = ?");
        send();
        assertEquals(List.of("select * from t where a = ? and b = ?"), opened);
        assertTrue(asked.isEmpty());
    }

    /** All three run actions share SqlRunActionSupport.update; check each action and intention once here. */
    private static final Map<String, String> RUN_ACTIONS = Map.of(
            ACTION_ID, "Send to Console",
            "com.segfault03.lattice.sql.run.explain", "Explain Statement",
            "com.segfault03.lattice.sql.run.rollback", "Run and Roll Back");

    public void testDisabledWithoutConnection() {
        connections.removeConnection(h2.getId());
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "a"));
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "b"));
        myFixture.configureByText("q.sql", "select <caret>1");
        RUN_ACTIONS.forEach((id, text) -> {
            Presentation presentation = myFixture.testAction(ActionManager.getInstance().getAction(id));
            assertTrue(id, presentation.isVisible());
            assertFalse(id, presentation.isEnabled());
            assertEquals(id, "Bind this file to a connection first", presentation.getDescription());
            assertNull(text, myFixture.getAvailableIntention(text));
        });
        assertTrue(opened.isEmpty());
    }

    public void testOfferedInTheCommunityEditionWithTheDatabasePluginEnabled() {
        UltimateDatabasePlugin.setPresenceForTests(UltimateDatabasePlugin.Presence.COMMUNITY, getTestRootDisposable());
        // Without a chosen connection the actions show but cannot run, which is all this needs to see.
        connections.removeConnection(h2.getId());
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "a"));
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "b"));
        myFixture.configureByText("q.sql", "select <caret>1");
        RUN_ACTIONS.forEach((id, text) -> assertTrue(id, myFixture.testAction(ActionManager.getInstance().getAction(id)).isVisible()));
    }

    public void testHiddenWhenUltimateActive() {
        UltimateDatabasePlugin.setActiveForTests(true, getTestRootDisposable());
        myFixture.configureByText("q.sql", "select <caret>1");
        RUN_ACTIONS.forEach((id, text) -> {
            assertFalse(id, myFixture.testAction(ActionManager.getInstance().getAction(id)).isVisible());
            assertNull(text, myFixture.getAvailableIntention(text));
        });
        assertTrue(opened.isEmpty());
    }

    public void testIntentionAvailableOnStatement() {
        myFixture.configureByText("q.sql", "select <caret>1");
        IntentionAction intention = myFixture.getAvailableIntention("Send to Console");
        assertNotNull(intention);
        assertEquals("Lattice SQL", intention.getFamilyName());
        myFixture.launchAction(intention);
        assertEquals(List.of("select 1"), opened);
    }
}
