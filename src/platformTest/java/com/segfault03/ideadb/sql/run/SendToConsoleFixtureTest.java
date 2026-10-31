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

    public void testRegisteredInEditorPopupGroup() {
        assertNotNull(ActionManager.getInstance().getAction(ACTION_ID));
        assertTrue(ActionManager.getInstance().getAction("com.segfault03.lattice.sql.run.group") instanceof com.intellij.openapi.actionSystem.ActionGroup);
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

    public void testSendsRenderedLiteralsFromProvidedValues() {
        provide(Map.of("1", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "42"),
                "2", new SqlParameterValue(SqlParameterValue.Type.TEXT, "o'hara")));
        myFixture.configureByText("q.sql", "select * from t where a = <caret>? and b = ?");
        send();
        assertEquals(List.of("select * from t where a = 42 and b = 'o''hara'"), opened);
        assertEquals(1, asked.size());
    }

    public void testNoParametersSkipsDialog() {
        provide(Map.of());
        myFixture.configureByText("q.sql", "select <caret>1");
        send();
        assertTrue(asked.isEmpty());
        assertEquals(List.of("select 1"), opened);
    }

    public void testCancelledDialogSendsNothing() {
        SqlRunActionSupport.setValuesProviderForTests((target, initial) -> {
            asked.add(target);
            return null;
        }, getTestRootDisposable());
        myFixture.configureByText("q.sql", "select <caret>?");
        send();
        assertEquals(1, asked.size());
        assertTrue(opened.isEmpty());
    }

    public void testMixedParameterStylesAreAProblemWithoutDialog() {
        provide(Map.of());
        myFixture.configureByText("q.sql", "select <caret>? , :name");
        send();
        assertTrue(asked.isEmpty());
        assertTrue(opened.isEmpty());
    }

    public void testDisabledWithoutConnection() {
        connections.removeConnection(h2.getId());
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "a"));
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "b"));
        myFixture.configureByText("q.sql", "select <caret>1");
        Presentation presentation = send();
        assertTrue(presentation.isVisible());
        assertFalse(presentation.isEnabled());
        assertEquals("Bind this file to a connection first", presentation.getDescription());
        assertTrue(opened.isEmpty());
        assertNull(myFixture.getAvailableIntention("Send to Console"));
    }

    public void testHiddenWhenUltimateActive() {
        UltimateDatabasePlugin.setActiveForTests(true, getTestRootDisposable());
        myFixture.configureByText("q.sql", "select <caret>1");
        Presentation presentation = send();
        assertFalse(presentation.isVisible());
        assertTrue(opened.isEmpty());
        assertNull(myFixture.getAvailableIntention("Send to Console"));
    }

    public void testIntentionAvailableOnStatement() {
        myFixture.configureByText("q.sql", "select <caret>1");
        IntentionAction intention = myFixture.getAvailableIntention("Send to Console");
        assertNotNull(intention);
        assertEquals("Lattice SQL", intention.getFamilyName());
        myFixture.launchAction(intention);
        assertEquals(List.of("select 1"), opened);
    }

    public void testDialogPrefilledFromRememberedValues() {
        Map<String, SqlParameterValue> first = Map.of("1", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "42"),
                "2", new SqlParameterValue(SqlParameterValue.Type.TEXT, "abc"));
        List<Map<String, SqlParameterValue>> initials = new ArrayList<>();
        SqlRunActionSupport.setValuesProviderForTests((target, initial) -> {
            initials.add(initial);
            return first;
        }, getTestRootDisposable());
        myFixture.configureByText("q.sql", "select * from t where a = <caret>? and b = ?");
        send();
        send();
        assertEquals(2, initials.size());
        assertTrue(initials.get(0).isEmpty());
        assertEquals(first, initials.get(1));
    }
}
