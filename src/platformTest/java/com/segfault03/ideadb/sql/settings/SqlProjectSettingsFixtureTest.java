package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.FixtureConnections;
import com.segfault03.ideadb.sql.binding.RestoreBuiltInSqlAction;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.discovery.JdbcDiscovery;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import com.segfault03.ideadb.state.DatabaseSettingsState;

import javax.swing.*;

/** The project page of the settings, and the action that gives .sql files back to the IDE. */
public class SqlProjectSettingsFixtureTest extends BasePlatformTestCase {
    private DatabaseSettingsState connections;

    @Override protected void setUp() throws Exception {
        super.setUp();
        connections = FixtureConnections.install(getTestRootDisposable(), false);
        SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
        SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        SqlFileTypeClaim.apply(true);
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

    @SuppressWarnings("unchecked")
    private static JComboBox<Object> firstCombo(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JComboBox<?> combo) return (JComboBox<Object>) combo;
            if (child instanceof java.awt.Container container) { var found = firstCombo(container); if (found != null) return found; }
        }
        return null;
    }

    private static JCheckBox firstCheckBox(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            if (child instanceof JCheckBox box) return box;
            if (child instanceof java.awt.Container container) { var found = firstCheckBox(container); if (found != null) return found; }
        }
        return null;
    }

    public void testTheProjectPageChoosesTheDefaultConnection() {
        ConnectionConfig mysql = new ConnectionConfig(DatabaseType.MYSQL, "m");
        ConnectionConfig postgres = new ConnectionConfig(DatabaseType.POSTGRESQL, "p");
        connections.addConnection(mysql);
        connections.addConnection(postgres);
        var page = new SqlProjectConfigurable(getProject());
        JComponent component = page.createComponent();
        assertFalse(page.isModified());

        JComboBox<Object> combo = firstCombo(component);
        assertEquals("automatic, as nothing is chosen", 3, combo.getItemCount());
        for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i) instanceof ConnectionConfig c && c.getId().equals(postgres.getId())) combo.setSelectedIndex(i);
        assertTrue(page.isModified());
        page.apply();
        assertEquals(postgres.getId(), SqlBindingsState.getInstance(getProject()).defaultConnectionId());
        assertEquals(SqlDialects.POSTGRESQL, SqlDialectResolver.resolve(getProject(), null));
        assertFalse(page.isModified());

        combo.setSelectedIndex(0);
        page.apply();
        assertEquals("", SqlBindingsState.getInstance(getProject()).defaultConnectionId());
        page.disposeUIResources();
    }

    public void testTheProjectPageCanTurnOffReadingTheProjectsFiles() {
        myFixture.addFileToProject("src/main/resources/application.properties", "spring.datasource.url=jdbc:mysql://db:3306/shop\n");
        JdbcDiscovery discovery = JdbcDiscovery.getInstance(getProject());
        discovery.refreshNow();
        assertEquals(1, discovery.snapshot().size());

        var page = new SqlProjectConfigurable(getProject());
        JCheckBox box = firstCheckBox(page.createComponent());
        assertTrue(box.isSelected());
        box.setSelected(false);
        page.apply();
        assertFalse(SqlBindingsState.getInstance(getProject()).useProjectFiles());
        assertTrue("nothing from the files is used while it is off", discovery.snapshot().isEmpty());
        assertEquals(SqlDialects.ANSI, SqlDialectResolver.resolve(getProject(), null));

        box.setSelected(true);
        page.apply();
        discovery.refreshNow();
        assertEquals(SqlDialects.MYSQL, SqlDialectResolver.resolve(getProject(), null));
        page.disposeUIResources();
    }

    public void testRestoringTheBuiltInHandlingGivesSqlFilesBackAtOnce() {
        assertEquals(LatticeSqlFileType.INSTANCE, myFixture.configureByText("q.sql", "select 1").getFileType());
        var action = new RestoreBuiltInSqlAction();
        var event = com.intellij.testFramework.TestActionEvent.createTestEvent(action, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(getProject()));
        action.update(event);
        assertTrue(event.getPresentation().isEnabledAndVisible());
        action.actionPerformed(event);

        assertFalse(SqlEditorSettings.getInstance().isHandleSqlFiles());
        assertNotSame("the IDE's own type again", LatticeSqlFileType.INSTANCE, FileTypeManager.getInstance().getFileTypeByFileName("other.sql"));
        var after = com.intellij.testFramework.TestActionEvent.createTestEvent(action, com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(getProject()));
        action.update(after);
        assertFalse("nothing to restore any more", after.getPresentation().isEnabledAndVisible());
    }
}
