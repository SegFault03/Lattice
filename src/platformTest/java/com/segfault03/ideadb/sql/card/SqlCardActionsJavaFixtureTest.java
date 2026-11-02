package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

import java.awt.datatransfer.DataFlavor;
import java.util.ArrayList;
import java.util.List;

/** Copy SQL, the connection chip, and the card toolbar group. */
public class SqlCardActionsJavaFixtureTest extends H2SqlFixtureTestCase {
    static final String COPY = "com.segfault03.lattice.sql.card.copy";
    static final String CHIP = "com.segfault03.lattice.sql.card.noConnection";

    @Override protected String[] schemaStatements() { return new String[0]; }

    private SqlCard card(String members) {
        myFixture.configureByText("A.java", "import java.sql.*;\nclass A {\n" + members + "\n}");
        return ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile())).get(0);
    }

    static AnActionEvent event(CodeInsightTestFixture fixture, AnAction action, SqlCard card) {
        return TestActionEvent.createTestEvent(action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, fixture.getProject())
                .add(CommonDataKeys.EDITOR, fixture.getEditor()).add(CommonDataKeys.PSI_FILE, fixture.getFile())
                .add(CommonDataKeys.VIRTUAL_FILE, fixture.getFile().getVirtualFile()).add(SqlCardDataKeys.SQL_CARD, card).build());
    }

    private Presentation update(String id, SqlCard card) {
        AnAction action = ActionManager.getInstance().getAction(id);
        AnActionEvent event = event(myFixture, action, card);
        action.update(event);
        return event.getPresentation();
    }

    static String clipboard() throws Exception { return (String) CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor); }

    public void testCopyPutsPlainSqlOnClipboard() throws Exception {
        SqlCard card = card("String f(String t, int id) { return \"select * from \" + t + \" where id = \" + id; }");
        AnAction copy = ActionManager.getInstance().getAction(COPY);
        assertTrue(update(COPY, card).isEnabledAndVisible());
        copy.actionPerformed(event(myFixture, copy, card));
        assertEquals("select * from ? where id = ?", clipboard());
    }

    public void testChipVisibleOnlyWhenUnbound() {
        SqlCard card = card("String q = \"select id from orders where id = ?\";");
        assertFalse(update(CHIP, card).isVisible());
        connections.removeConnection(h2.getId());
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "a"));
        connections.addConnection(new ConnectionConfig(DatabaseType.H2, "b"));
        Presentation chip = update(CHIP, card);
        assertTrue(chip.isEnabledAndVisible());
        assertEquals("No connection", chip.getText());
    }

    public void testChooseConnectionVisibleOnCardWithCaretElsewhere() {
        SqlCard card = card("String q = \"select id from orders where id = ?\";");
        myFixture.getEditor().getCaretModel().moveToOffset(0);
        assertTrue(update("com.segfault03.lattice.sql.chooseFileConnection", card).isEnabledAndVisible());
    }

    public void testRunActionsHaveIconsOnACard() {
        SqlCard card = card("String q = \"update orders set status = 'x' where id = ?\";");
        for (String id : List.of("com.segfault03.lattice.sql.run.rollback", "com.segfault03.lattice.sql.run.sendToConsole", "com.segfault03.lattice.sql.run.explain")) {
            assertNotNull(id, update(id, card).getIcon());
            assertNull("the editor menu keeps its icon-less entries: " + id, ActionManager.getInstance().getAction(id).getTemplatePresentation().getIcon());
        }
    }

    public void testToolbarGroupOrder() {
        ActionManager manager = ActionManager.getInstance();
        AnAction[] children = ((DefaultActionGroup) manager.getAction("com.segfault03.lattice.sql.card.toolbar")).getChildActionsOrStubs();
        List<String> ids = new ArrayList<>();
        for (AnAction child : children) ids.add(child instanceof Separator ? "-" : manager.getId(child));
        assertEquals(List.of(CHIP, "com.segfault03.lattice.sql.run.rollback", "com.segfault03.lattice.sql.run.sendToConsole", COPY, "-",
                "com.segfault03.lattice.sql.card.more"), ids);
        AnAction[] more = ((DefaultActionGroup) manager.getAction("com.segfault03.lattice.sql.card.more")).getChildActionsOrStubs();
        assertEquals(List.of("com.segfault03.lattice.sql.run.explain", "com.segfault03.lattice.sql.chooseFileConnection"),
                List.of(manager.getId(more[0]), manager.getId(more[1])));
        assertEquals("Copy SQL", manager.getAction(COPY).getTemplatePresentation().getText());
    }

    public void testCopyNotInEditorPopupMenu() {
        ActionManager manager = ActionManager.getInstance();
        List<String> found = new ArrayList<>();
        collect(manager, (ActionGroup) manager.getAction(IdeActions.GROUP_EDITOR_POPUP), found);
        assertFalse(found.contains(COPY));
        assertTrue("the run group is still there", found.contains("com.segfault03.lattice.sql.run.sendToConsole"));
    }

    private static void collect(ActionManager manager, ActionGroup group, List<String> ids) {
        AnAction[] children = group instanceof DefaultActionGroup d ? d.getChildActionsOrStubs() : group.getChildren(null);
        for (AnAction child : children) {
            String id = manager.getId(child);
            if (id != null) ids.add(id);
            if (child instanceof ActionGroup inner) collect(manager, inner, ids);
        }
    }
}
