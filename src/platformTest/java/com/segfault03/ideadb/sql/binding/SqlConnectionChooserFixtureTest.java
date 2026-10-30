package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.ListPopupStepEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.FixtureConnections;
import com.segfault03.ideadb.sql.binding.SqlConnectionChooser.Choice;
import com.segfault03.ideadb.sql.binding.SqlConnectionChooser.Kind;
import com.segfault03.ideadb.sql.binding.SqlConnectionChooser.Model;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.discovery.JdbcDiscovery;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.settings.SqlFileTypeClaim;
import com.segfault03.ideadb.state.DatabaseSettingsState;

import java.util.List;

/** The connection chooser's rows, labels, preselection and what choosing each row does. */
public class SqlConnectionChooserFixtureTest extends BasePlatformTestCase {
    private DatabaseSettingsState connections;
    private ConnectionConfig mysql;
    private ConnectionConfig postgres;

    @Override protected void setUp() throws Exception {
        super.setUp();
        connections = FixtureConnections.install(getTestRootDisposable(), false);
        SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
        SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        SqlFileTypeClaim.apply(true);
        mysql = new ConnectionConfig(DatabaseType.MYSQL, "shop-mysql");
        postgres = new ConnectionConfig(DatabaseType.POSTGRESQL, "shop-postgres");
        connections.addConnection(mysql);
        connections.addConnection(postgres);
    }

    @Override protected void tearDown() throws Exception {
        try {
            SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
            SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
            SqlFileTypeClaim.apply(true);
        } finally {
            super.tearDown();
        }
    }

    private VirtualFile sqlFile() { return myFixture.configureByText("q.sql", "select 1").getVirtualFile(); }

    private static List<String> labels(Model model) { return model.choices().stream().map(Choice::label).toList(); }

    private static ListPopupStepEx<Choice> step(ListPopup popup) {
        @SuppressWarnings("unchecked") ListPopupStepEx<Choice> step = (ListPopupStepEx<Choice>) popup.getListStep();
        return step;
    }

    /** Choosing a row hands the work to the step's final runnable, which the popup runs once it has closed. */
    private static void choose(ListPopupStepEx<Choice> step, Choice choice) {
        step.onChosen(choice, true);
        Runnable finalRunnable = step.getFinalRunnable();
        assertNotNull(finalRunnable);
        finalRunnable.run();
    }

    public void testAFileWithoutABindingListsConnectionsThenGenericSqlAndSetProjectDefault() {
        Model model = SqlConnectionChooser.fileModel(getProject(), sqlFile());
        assertEquals(List.of("shop-mysql", "shop-postgres", "Generic SQL", "Set project default…"), labels(model));
        assertEquals("Without a choice, generic SQL is in effect", 2, model.defaultIndex());
        assertEquals("SQL connection for q.sql", model.title());
    }

    public void testConnectionRowsCarryTheirTypeAndDatabaseIcon() {
        Model model = SqlConnectionChooser.fileModel(getProject(), sqlFile());
        Choice first = model.choices().get(0);
        assertEquals("MySQL", first.secondary());
        assertNotNull(first.icon());
        assertEquals("PostgreSQL", model.choices().get(1).secondary());
        assertNull(model.choices().get(2).secondary());
    }

    public void testTheFilesConnectionIsPreselected() {
        VirtualFile file = sqlFile();
        SqlBindingsState.getInstance(getProject()).bindFileToConnection(file, postgres.getId());
        Model model = SqlConnectionChooser.fileModel(getProject(), file);
        assertEquals(1, model.defaultIndex());
        assertTrue("An explicit choice can be undone", labels(model).contains("Follow project default (Generic SQL)"));
    }

    public void testFollowProjectDefaultNamesWhatItResolvesToAndOnlyAppearsWithABinding() {
        VirtualFile file = sqlFile();
        assertFalse(labels(SqlConnectionChooser.fileModel(getProject(), file)).stream().anyMatch(label -> label.startsWith("Follow")));

        SqlBindingsState bindings = SqlBindingsState.getInstance(getProject());
        bindings.setDefaultConnectionId(mysql.getId());
        bindings.bindFileToDialect(file, SqlDialects.ANSI.id());
        Model model = SqlConnectionChooser.fileModel(getProject(), file);
        assertEquals(List.of("shop-mysql", "shop-postgres", "Generic SQL", "Follow project default (shop-mysql)", "Set project default…"), labels(model));
        assertEquals("The explicit generic choice is the one in effect", 2, model.defaultIndex());
    }

    public void testAFileWithoutABindingPreselectsTheProjectDefault() {
        SqlBindingsState.getInstance(getProject()).setDefaultConnectionId(postgres.getId());
        assertEquals(1, SqlConnectionChooser.fileModel(getProject(), sqlFile()).defaultIndex());
    }

    public void testDiscoveredDatabasesAreOfferedAsAddRowsBeforeSetProjectDefault() {
        myFixture.addFileToProject("src/main/resources/application.yml", "spring:\n  datasource:\n    url: jdbc:mysql://db.internal:3306/billing\n");
        JdbcDiscovery.getInstance(getProject()).refreshNow();
        Model model = SqlConnectionChooser.fileModel(getProject(), sqlFile());
        List<String> labels = labels(model);
        assertEquals("Add MySQL db.internal:3306/billing (application.yml)…", labels.get(labels.size() - 2));
        assertEquals("Set project default…", labels.get(labels.size() - 1));
        assertEquals(Kind.ADD_FOUND, model.choices().get(labels.size() - 2).kind());
    }

    public void testTheProjectChooserOffersConnectionsAndGenericSql() {
        Model model = SqlConnectionChooser.projectModel(getProject());
        assertEquals(List.of("shop-mysql", "shop-postgres", "Generic SQL"), labels(model));
        assertEquals(2, model.defaultIndex());
        assertEquals("Default SQL connection for this project", model.title());

        SqlBindingsState.getInstance(getProject()).setDefaultConnectionId(mysql.getId());
        assertEquals(0, SqlConnectionChooser.projectModel(getProject()).defaultIndex());
    }

    public void testThePopupStepShowsSeparatorOnlyAboveTheFirstNonConnectionRow() {
        ListPopup popup = SqlConnectionChooser.filePopup(getProject(), sqlFile(), null);
        try {
            var step = step(popup);
            List<Choice> rows = step.getValues();
            assertNull(step.getSeparatorAbove(rows.get(0)));
            assertNull(step.getSeparatorAbove(rows.get(1)));
            assertNotNull("above Generic SQL", step.getSeparatorAbove(rows.get(2)));
            assertNull(step.getSeparatorAbove(rows.get(3)));
            assertEquals(2, step.getDefaultOptionIndex());
            assertEquals("MySQL", step.getSecondaryTextFor(rows.get(0)));
            assertEquals("shop-mysql", step.getTextFor(rows.get(0)));
            assertNotNull(step.getIconFor(rows.get(0)));
        } finally {
            popup.dispose();
        }
    }

    public void testChoosingARowBindsClearsOrOpensTheProjectChooser() {
        VirtualFile file = sqlFile();
        SqlBindingsState bindings = SqlBindingsState.getInstance(getProject());
        ListPopup popup = SqlConnectionChooser.filePopup(getProject(), file, null);
        try {
            var step = step(popup);
            choose(step, step.getValues().get(1));
            assertEquals(postgres.getId(), bindings.fileBinding(file).connectionId);
            choose(step, step.getValues().get(2));
            assertEquals(SqlDialects.ANSI.id(), bindings.fileBinding(file).dialectId);
        } finally {
            popup.dispose();
        }
        ListPopup follow = SqlConnectionChooser.filePopup(getProject(), file, null);
        try {
            var step = step(follow);
            Choice clear = step.getValues().stream().filter(choice -> choice.kind() == Kind.FOLLOW_DEFAULT).findFirst().orElseThrow();
            choose(step, clear);
            assertNull(bindings.fileBinding(file));
        } finally {
            follow.dispose();
        }
    }

    public void testChoosingInTheProjectChooserSetsOrClearsTheDefault() {
        SqlBindingsState bindings = SqlBindingsState.getInstance(getProject());
        ListPopup popup = SqlConnectionChooser.projectPopup(getProject());
        try {
            var step = step(popup);
            choose(step, step.getValues().get(0));
            assertEquals(mysql.getId(), bindings.defaultConnectionId());
            choose(step, step.getValues().get(2));
            assertEquals("", bindings.defaultConnectionId());
        } finally {
            popup.dispose();
        }
    }
}
