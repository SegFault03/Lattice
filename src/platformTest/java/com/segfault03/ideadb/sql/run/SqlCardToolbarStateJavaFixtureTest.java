package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.testFramework.TestActionEvent;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.card.SqlCard;
import com.segfault03.ideadb.sql.card.SqlCardDataKeys;
import com.segfault03.ideadb.sql.card.SqlCardLocator;

import java.util.ArrayList;
import java.util.List;

/**
 * The card toolbar's states: which run actions are enabled on a card, and why not. It lives in the run package to
 * reach the console hook the Send to Console test needs.
 */
public class SqlCardToolbarStateJavaFixtureTest extends H2SqlFixtureTestCase {
    private static final String ROLLBACK = "com.segfault03.lattice.sql.run.rollback";
    private static final String CONSOLE = "com.segfault03.lattice.sql.run.sendToConsole";
    private static final String EXPLAIN = "com.segfault03.lattice.sql.run.explain";

    @Override protected String[] schemaStatements() { return new String[0]; }

    private SqlCard card(String sql) {
        myFixture.configureByText("A.java", "import java.sql.*;\nclass A {\n  void f(Connection c) throws Exception {\n    c.prepareStatement(\""
                + sql + "\");\n  }\n  int other() { return 1; }\n}");
        List<SqlCard> cards = ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile()));
        assertEquals(1, cards.size());
        return cards.get(0);
    }

    private Presentation update(String id, SqlCard card) {
        AnAction action = ActionManager.getInstance().getAction(id);
        var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, myFixture.getFile());
        if (card != null) context.add(SqlCardDataKeys.SQL_CARD, card);
        AnActionEvent event = TestActionEvent.createTestEvent(action, context.build());
        action.update(event);
        return event.getPresentation();
    }

    private void assertState(String id, SqlCard card, boolean enabled, String description) {
        Presentation presentation = update(id, card);
        assertTrue(id + " is visible on a card", presentation.isVisible());
        assertEquals(id, enabled, presentation.isEnabled());
        if (!enabled) assertEquals(id, description, presentation.getDescription());
    }

    private void unbind() {
        connections.removeConnection(h2.getId());
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "a"));
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "b"));
    }

    public void testDmlBound() {
        SqlCard card = card("update orders set status = 'x' where id = ?");
        assertState(ROLLBACK, card, true, null);
        assertState(CONSOLE, card, true, null);
        assertState(EXPLAIN, card, true, null);
    }

    public void testSelectBound() {
        SqlCard card = card("select id from orders where id = ?");
        assertState(ROLLBACK, card, false, SqlStatementKind.rollbackRefusal(SqlStatementKind.QUERY));
        assertState(CONSOLE, card, true, null);
        assertState(EXPLAIN, card, true, null);
    }

    public void testDdlBound() {
        SqlCard card = card("create table audit (id int primary key)");
        assertEquals(SqlStatementKind.OTHER, card.kind());
        assertState(ROLLBACK, card, false, SqlStatementKind.rollbackRefusal(SqlStatementKind.OTHER));
        assertState(CONSOLE, card, true, null);
        assertState(EXPLAIN, card, false, SqlStatementKind.explainRefusal(SqlStatementKind.OTHER));
    }

    public void testMultiStatementBound() {
        SqlCard card = card("delete from orders where id = 1; insert into orders values (1)");
        assertState(ROLLBACK, card, false, SqlRunTarget.SELECT_SINGLE_STATEMENT);
        assertState(CONSOLE, card, true, null);
        assertState(EXPLAIN, card, false, SqlRunTarget.SELECT_SINGLE_STATEMENT);
    }

    public void testUnbound() {
        SqlCard card = card("update orders set status = 'x' where id = ?");
        unbind();
        for (String id : List.of(ROLLBACK, CONSOLE, EXPLAIN)) assertState(id, card, false, SqlRunActionSupport.BIND_FIRST);
    }

    public void testWithoutCardKeyCaretBehaviourUnchanged() {
        card("update orders set status = 'x' where id = ?");
        String text = myFixture.getEditor().getDocument().getText();
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf("return 1"));
        for (String id : List.of(ROLLBACK, CONSOLE, EXPLAIN)) assertFalse(id, update(id, null).isVisible());
    }

    public void testSendToConsoleUsesCardNotCaret() {
        myFixture.configureByText("A.java", "import java.sql.*;\nclass A {\n  void f(Connection c) throws Exception {\n"
                + "    c.prepareStatement(\"select 1 from dual\");\n    c.prepareStatement(\"select 2 from dual\");\n  }\n}");
        List<SqlCard> cards = ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile()));
        String text = myFixture.getEditor().getDocument().getText();
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf("select 1") + 3);
        List<String> opened = new ArrayList<>();
        SqlRunActionSupport.setConsoleOpenerForTests((config, db, sql) -> opened.add(sql), getTestRootDisposable());
        AnAction action = ActionManager.getInstance().getAction(CONSOLE);
        var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).add(CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(CommonDataKeys.PSI_FILE, myFixture.getFile()).add(SqlCardDataKeys.SQL_CARD, cards.get(1)).build();
        action.actionPerformed(TestActionEvent.createTestEvent(action, context));
        assertEquals(List.of("select 2 from dual"), opened);
    }
}
