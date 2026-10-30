package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
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

import java.awt.Component;
import java.awt.Container;

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
    private static JComboBox<Object> firstCombo(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComboBox<?> combo) return (JComboBox<Object>) combo;
            if (child instanceof Container container) { var found = firstCombo(container); if (found != null) return found; }
        }
        return null;
    }

    private static JCheckBox firstCheckBox(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JCheckBox box) return box;
            if (child instanceof Container container) { var found = firstCheckBox(container); if (found != null) return found; }
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

    public void testThePageIsCalledProjectDefaultsAndKeepsItsId() {
        var page = new SqlProjectConfigurable(getProject());
        assertEquals("Project Defaults", page.getDisplayName());
        assertEquals("com.segfault03.lattice.sql.editor.project", page.getId());
    }

    private static JCheckBox checkBoxStartingWith(Container root, String text) {
        return UIUtil.findComponentsOfType((JComponent) root, JCheckBox.class).stream()
                .filter(box -> box.getText().startsWith(text)).findFirst().orElse(null);
    }

    public void testTurnedDownOffersAreOfferedAgainOnlyWhenTheCheckboxIsApplied() {
        var bindings = SqlBindingsState.getInstance(getProject());
        var page = new SqlProjectConfigurable(getProject());
        JComponent component = page.createComponent();
        JCheckBox offer = checkBoxStartingWith(component, "Offer the");
        assertNotNull(offer);
        assertFalse("nothing was turned down, so nothing to offer again", offer.isVisible());
        page.disposeUIResources();

        bindings.dismissDiscovery("PostgreSQL localhost:5432/shop (application.yml)");
        bindings.dismissDiscovery("MySQL db:3306/legacy (persistence.xml)");
        component = page.createComponent();
        offer = checkBoxStartingWith(component, "Offer the");
        assertTrue(offer.isVisible());
        assertEquals("Offer the 2 databases I turned down again", offer.getText());
        assertFalse("it is an action, not a setting: off to begin with", offer.isSelected());
        assertFalse(page.isModified());

        offer.setSelected(true);
        assertTrue("the pending action counts as a change", page.isModified());
        assertTrue("nothing happens until Apply", bindings.isDiscoveryDismissed("MySQL db:3306/legacy (persistence.xml)"));
        page.reset();
        assertFalse("reset drops the pending action", offer.isSelected());
        assertFalse(page.isModified());

        offer.setSelected(true);
        page.apply();
        assertFalse(bindings.isDiscoveryDismissed("MySQL db:3306/legacy (persistence.xml)"));
        assertEquals(0, bindings.dismissedDiscoveryCount());
        assertFalse(page.isModified());
        assertFalse("and there is nothing left to offer again", offer.isVisible());
        page.disposeUIResources();
    }

    public void testOneTurnedDownOfferIsNamedInTheSingular() {
        SqlBindingsState.getInstance(getProject()).dismissDiscovery("PostgreSQL localhost:5432/shop (application.yml)");
        var page = new SqlProjectConfigurable(getProject());
        assertEquals("Offer the 1 database I turned down again", checkBoxStartingWith(page.createComponent(), "Offer the").getText());
        page.disposeUIResources();
    }

    public void testRestoringTheBuiltInHandlingGivesSqlFilesBackAtOnce() {
        assertEquals(LatticeSqlFileType.INSTANCE, myFixture.configureByText("q.sql", "select 1").getFileType());
        var action = new RestoreBuiltInSqlAction();
        var event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(getProject()));
        action.update(event);
        assertTrue(event.getPresentation().isEnabledAndVisible());
        action.actionPerformed(event);

        assertFalse(SqlEditorSettings.getInstance().isHandleSqlFiles());
        assertNotSame("the IDE's own type again", LatticeSqlFileType.INSTANCE, FileTypeManager.getInstance().getFileTypeByFileName("other.sql"));
        var after = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(getProject()));
        action.update(after);
        assertFalse("nothing to restore any more", after.getPresentation().isEnabledAndVisible());
    }
}
