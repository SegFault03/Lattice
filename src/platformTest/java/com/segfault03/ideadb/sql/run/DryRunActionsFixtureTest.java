package com.segfault03.ideadb.sql.run;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.DryRunService;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.settings.SqlProjectConfigurable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Explain Statement and Run and Roll Back against the H2 fixture: the refusals, the tab, the confirmation and the guard. */
public class DryRunActionsFixtureTest extends H2SqlFixtureTestCase {
    private static final String EXPLAIN = "com.segfault03.lattice.sql.run.explain";
    private static final String ROLLBACK = "com.segfault03.lattice.sql.run.rollback";
    private static final String ALREADY_RUNNING = "A dry run is already running for this connection.";

    private final AtomicInteger opens = new AtomicInteger();
    private final List<String> errors = new ArrayList<>();
    private final List<SqlRunTarget> asked = new ArrayList<>();
    private final Set<VirtualFile> tabs = new LinkedHashSet<>();
    private volatile Boolean runningWhileOpening;
    private volatile boolean closeTabWhileOpening;

    @Override protected String[] schemaStatements() {
        return new String[] {"create table t (id int primary key, name varchar(20))", "insert into t values (1, 'a')"};
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        DryRunService.setInstanceForTests(new DryRunService(config -> {
            opens.incrementAndGet();
            DryRunPanel opening = DryRunVirtualFile.existingPanel(getProject(), config);
            runningWhileOpening = opening != null && opening.isRunning();
            if (closeTabWhileOpening) synchronized (tabs) { tabs.clear(); }
            return DatabaseConnectionManager.getInstance().openConnection(config);
        }), getTestRootDisposable());
        TestDialogManager.setTestDialog(message -> {
            errors.add(message);
            return Messages.OK;
        }, getTestRootDisposable());
        // A light test has no editor window to open a Lattice tab in, so the open tabs are recorded instead.
        DryRunVirtualFile.setTabsForTests(new DryRunVirtualFile.Tabs() {
            @Override public boolean isOpen(com.intellij.openapi.project.Project project, VirtualFile file) { return tabs.contains(file); }
            @Override public void open(com.intellij.openapi.project.Project project, VirtualFile file) { tabs.add(file); }
        }, getTestRootDisposable());
        RollbackConfirmation.setAnswerForTests(true, getTestRootDisposable());
        SqlRunState.getInstance(getProject()).loadState(new SqlRunState.State());
        SqlRunActionSupport.setValuesProviderForTests((target, initial) -> {
            asked.add(target);
            return Map.of("1", new SqlParameterValue(SqlParameterValue.Type.NUMBER, "1"));
        }, getTestRootDisposable());
    }

    @Override protected void tearDown() throws Exception {
        try {
            SqlRunState.getInstance(getProject()).loadState(new SqlRunState.State());
        } finally {
            super.tearDown();
        }
    }

    private Presentation perform(String id) {
        return myFixture.testAction(ActionManager.getInstance().getAction(id));
    }

    private DryRunPanel panel() {
        return DryRunVirtualFile.existingPanel(getProject(), h2);
    }

    private DryRunPanel waitUntilDone() {
        PlatformTestUtil.waitWithEventsDispatching("The dry run never finished", () -> panel() != null && !panel().isRunning(), 30);
        return panel();
    }

    private String selectedRowQuietly() {
        try { return selectedRow(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String selectedRow() throws Exception {
        Connection connection = DatabaseConnectionManager.getInstance().getConnection(h2);
        synchronized (connection) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("select name from t where id = 1")) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    public void testExplainOpensTabWithPlan() {
        myFixture.configureByText("q.sql", "select * from t where <caret>id = ?");
        perform(EXPLAIN);
        DryRunPanel panel = waitUntilDone();
        assertNotNull(panel);
        assertTrue(panel.bodyText(), panel.bodyText().toUpperCase().contains("SELECT"));
        assertTrue(panel.headerText(), panel.headerText().contains("fixture H2"));
        assertTrue(panel.headerText(), panel.headerText().contains("Query"));
        assertTrue(panel.headerText(), panel.headerText().contains("1 = 1"));
        assertEquals(1, opens.get());
        assertEquals(1, asked.size());
        assertEquals("Dry Run: fixture H2", tabs.iterator().next().getName());
        assertTrue(errors.isEmpty());
    }

    public void testExplainRefusesOtherStatements() {
        myFixture.configureByText("q.sql", "create table <caret>x (id int)");
        perform(EXPLAIN);
        assertEquals(List.of("Only queries and DML can be explained."), errors);
        assertEquals(0, opens.get());
        assertNull(panel());
    }

    public void testRollbackRefusesSelectWithoutConnecting() {
        myFixture.configureByText("q.sql", "select * from t where <caret>id = ?");
        perform(ROLLBACK);
        assertEquals(List.of(SqlStatementKind.rollbackRefusal(SqlStatementKind.QUERY)), errors);
        assertEquals(0, opens.get());
        assertTrue("no values were asked for", asked.isEmpty());
        assertNull(panel());
    }

    public void testRollbackRunsAndShowsRolledBack() throws Exception {
        myFixture.configureByText("q.sql", "update t set name = 'z' where <caret>id = ?");
        perform(ROLLBACK);
        DryRunPanel panel = waitUntilDone();
        assertTrue(panel.bodyText(), panel.bodyText().contains("1 row(s) affected"));
        assertTrue(panel.bodyText(), panel.bodyText().contains("Rolled back"));
        assertTrue(panel.headerText(), panel.headerText().contains("DML"));
        assertEquals("the table is unchanged", "a", selectedRow());
    }

    public void testRollbackAsksConfirmationUnlessSuppressed() throws Exception {
        myFixture.configureByText("q.sql", "update t set name = 'z' where <caret>id = ?");
        RollbackConfirmation.setAnswerForTests(false, getTestRootDisposable());
        perform(ROLLBACK);
        assertEquals("declined: nothing runs", 0, opens.get());
        assertNull(panel());

        // Suppressed: confirm() answers true without a dialog, which would otherwise fail the test.
        RollbackConfirmation.setAnswerForTests(null, getTestRootDisposable());
        SqlRunState.getInstance(getProject()).setConfirmRollbackRuns(false);
        assertTrue(RollbackConfirmation.confirm(getProject(), h2, SqlStatementKind.DML));
        perform(ROLLBACK);
        waitUntilDone();
        assertEquals(1, opens.get());
        assertEquals("a", selectedRow());
    }

    public void testTabReusedPerConnection() {
        myFixture.configureByText("q.sql", "select * from t where <caret>id = ?");
        perform(EXPLAIN);
        DryRunPanel first = waitUntilDone();
        perform(EXPLAIN);
        DryRunPanel second = waitUntilDone();
        assertSame(first, second);
        assertEquals(1, tabs.size());
        assertEquals(2, opens.get());
    }

    public void testRerunRunsAgainWithoutAskingForValues() {
        myFixture.configureByText("q.sql", "select * from t where <caret>id = ?");
        perform(EXPLAIN);
        DryRunPanel panel = waitUntilDone();
        panel.clickRerun();
        PlatformTestUtil.waitWithEventsDispatching("The re-run never started", () -> opens.get() == 2, 30);
        waitUntilDone();
        assertEquals(1, asked.size());
        assertEquals(2, opens.get());
    }

    public void testPanelIsRunningWhileTheConnectionIsOpened() {
        myFixture.configureByText("q.sql", "select * from t where <caret>id = ?");
        perform(EXPLAIN);
        waitUntilDone();
        assertEquals("seen from the thread that opens the connection", Boolean.TRUE, runningWhileOpening);
    }

    public void testSecondRunWhileRunningRefused() {
        myFixture.configureByText("q.sql", "update t set name = 'z' where <caret>id = ?");
        DryRunRegistry registry = DryRunRegistry.getInstance(getProject());
        assertTrue(registry.tryBegin(h2.getId()));
        assertFalse("one run per connection", registry.tryBegin(h2.getId()));

        perform(EXPLAIN);
        perform(ROLLBACK);
        assertEquals(List.of(ALREADY_RUNNING, ALREADY_RUNNING), errors);
        assertTrue("the guard comes before the values dialog", asked.isEmpty());
        assertEquals(0, opens.get());

        registry.end(h2.getId());
        perform(EXPLAIN);
        waitUntilDone();
        assertEquals(1, opens.get());
        assertEquals("no further error", 2, errors.size());
        assertEquals("a", selectedRowQuietly());
    }

    public void testSecondRunRefusedAfterTabClosed() {
        myFixture.configureByText("q.sql", "update t set name = 'z' where <caret>id = ?");
        DryRunRegistry.getInstance(getProject()).tryBegin(h2.getId());
        tabs.clear();
        assertNull("the tab is gone", panel());

        perform(ROLLBACK);
        assertEquals(List.of(ALREADY_RUNNING), errors);
        assertEquals(0, opens.get());
        assertTrue(asked.isEmpty());
    }

    public void testRegistryClearedWhenTaskFinishes() {
        myFixture.configureByText("q.sql", "select * from t where <caret>id = ?");
        perform(EXPLAIN);
        waitUntilDone();
        PlatformTestUtil.waitWithEventsDispatching("The connection stayed busy", () -> !DryRunRegistry.getInstance(getProject()).isBusy(h2.getId()), 30);
        assertTrue(errors.isEmpty());
        perform(EXPLAIN);
        waitUntilDone();
        assertTrue("a second run is allowed once the first finished", errors.isEmpty());
        assertEquals(2, opens.get());
    }

    public void testResultOfAClosedTabIsNotified() {
        List<String> notified = new ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(com.intellij.notification.Notifications.TOPIC,
                new com.intellij.notification.Notifications() {
                    @Override public void notify(com.intellij.notification.Notification notification) { notified.add(notification.getTitle() + "|" + notification.getContent()); }
                });
        closeTabWhileOpening = true;
        myFixture.configureByText("q.sql", "update t set name = 'z' where <caret>id = ?");
        perform(ROLLBACK);
        PlatformTestUtil.waitWithEventsDispatching("No notification", () -> !notified.isEmpty(), 30);
        assertEquals(1, notified.size());
        assertTrue(notified.get(0), notified.get(0).contains("1 row(s) affected"));
        assertTrue(notified.get(0), notified.get(0).contains("fixture H2"));
    }

    public void testActionsRegisteredInOrderInTheGroup() {
        ActionManager manager = ActionManager.getInstance();
        AnAction[] children = ((ActionGroup) manager.getAction("com.segfault03.lattice.sql.run.group")).getChildren(null);
        assertEquals(List.of("com.segfault03.lattice.sql.run.run", "com.segfault03.lattice.sql.run.sendToConsole", EXPLAIN, ROLLBACK),
                java.util.Arrays.stream(children).map(manager::getId).toList());
        assertEquals("Run", manager.getAction("com.segfault03.lattice.sql.run.run").getTemplatePresentation().getText());
        assertEquals("Explain Statement", manager.getAction(EXPLAIN).getTemplatePresentation().getText());
        assertEquals("Run and Roll Back", manager.getAction(ROLLBACK).getTemplatePresentation().getText());
    }

    public void testIntentionsOfferBothActions() {
        myFixture.configureByText("q.sql", "update t set name = 'z' where <caret>id = 1");
        for (String text : List.of("Explain Statement", "Run and Roll Back")) {
            IntentionAction intention = myFixture.getAvailableIntention(text);
            assertNotNull(text, intention);
            assertEquals("Lattice SQL", intention.getFamilyName());
        }
        myFixture.launchAction(myFixture.getAvailableIntention("Run and Roll Back"));
        DryRunPanel panel = waitUntilDone();
        assertTrue(panel.bodyText(), panel.bodyText().contains("Rolled back"));
    }

    public void testProjectSettingsCheckboxReenablesConfirmation() {
        SqlRunState state = SqlRunState.getInstance(getProject());
        state.setConfirmRollbackRuns(false);
        var page = new SqlProjectConfigurable(getProject());
        JComponent component = page.createComponent();
        JCheckBox box = UIUtil.findComponentsOfType(component, JCheckBox.class).stream()
                .filter(candidate -> "Confirm before Run and Roll Back".equals(candidate.getText())).findFirst().orElse(null);
        assertNotNull(box);
        assertFalse(box.isSelected());
        assertFalse(page.isModified());
        box.setSelected(true);
        assertTrue(page.isModified());
        page.apply();
        assertTrue(state.confirmRollbackRuns());
        assertFalse(page.isModified());
        page.disposeUIResources();
    }
}
