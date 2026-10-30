package com.segfault03.ideadb.sql;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlConnectionBanner;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.binding.SqlEditorRefresh;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.lang.LatticeSqlSyntaxHighlighter;
import com.segfault03.ideadb.sql.lang.LatticeSqlTokens;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.settings.SqlFileTypeClaim;
import com.segfault03.ideadb.sql.settings.SqlEditorConfigurable;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;

import java.util.HashMap;
import java.util.Map;

public class SqlEditorFixtureTest extends BasePlatformTestCase {
    private ConnectionConfig mysql;
    private ConnectionConfig postgres;
    private DatabaseSettingsState connections;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Map<String, DatabaseSettingsState.Secret> secrets = new HashMap<>();
        connections = new DatabaseSettingsState(new DatabaseSettingsState.CredentialStore() {
            @Override public DatabaseSettingsState.Secret get(String id) { return secrets.get(id); }
            @Override public void set(String id, DatabaseSettingsState.Secret secret) { secrets.put(id, secret); }
        }, () -> {});
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), DatabaseSettingsState.class, connections, getTestRootDisposable());
        mysql = new ConnectionConfig(DatabaseType.MYSQL, "shop-mysql");
        postgres = new ConnectionConfig(DatabaseType.POSTGRESQL, "shop-postgres");
        SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
        SqlBindingsState.getInstance(getProject()).loadState(new SqlBindingsState.State());
        SqlFileTypeClaim.apply(true);
    }

    @Override protected void tearDown() throws Exception {
        try {
            SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
            SqlFileTypeClaim.apply(true);
        } finally {
            super.tearDown();
        }
    }

    private VirtualFile sqlFile(String text) {
        PsiFile file = myFixture.configureByText("q.sql", text);
        return file.getVirtualFile();
    }

    private IElementType firstToken() {
        HighlighterIterator iterator = ((EditorEx) myFixture.getEditor()).getHighlighter().createIterator(0);
        return iterator.getTokenType();
    }

    // --- the .sql claim -----------------------------------------------------------------------------------------

    public void testSqlFilesResolveToLatticeSql() {
        PsiFile file = myFixture.configureByText("q.sql", "select 1");
        assertEquals("Lattice SQL", file.getFileType().getName());
        assertEquals("LatticeSQL", file.getLanguage().getID());
    }

    public void testTheClaimTogglesAtRuntimeInBothDirections() {
        SqlFileTypeClaim.apply(false);
        assertFalse(SqlFileTypeClaim.isClaimed());
        PsiFile off = myFixture.configureByText("off.sql", "select 1");
        assertEquals("SQL", off.getFileType().getName());
        assertFalse("The IDE's own type has no PSI language of ours", off.getLanguage() == LatticeSqlLanguage.INSTANCE);

        SqlFileTypeClaim.apply(true);
        assertTrue(SqlFileTypeClaim.isClaimed());
        PsiFile on = myFixture.configureByText("on.sql", "select 1");
        assertSame(LatticeSqlFileType.INSTANCE, on.getFileType());
        assertEquals("*.sql", FileTypeManager.getInstance().getAssociations(LatticeSqlFileType.INSTANCE).get(0).getPresentableString());
    }

    public void testApplyingTheCurrentStateChangesNothing() {
        SqlFileTypeClaim.apply(true);
        SqlFileTypeClaim.apply(true);
        assertTrue(SqlFileTypeClaim.isClaimed());
        assertEquals(1, FileTypeManager.getInstance().getAssociations(LatticeSqlFileType.INSTANCE).size());
    }

    public void testTheAutomaticMasterSwitchStandsDownOnlyWhereTheDatabasePluginIsActive() {
        assertTrue(SqlEditorSettings.effectiveEnabled(null, false));
        assertFalse(SqlEditorSettings.effectiveEnabled(null, true));
        assertTrue("An explicit choice wins over detection", SqlEditorSettings.effectiveEnabled(true, true));
        assertFalse(SqlEditorSettings.effectiveEnabled(false, false));
    }

    // --- dialect resolution -------------------------------------------------------------------------------------

    public void testAnUnboundFileIsGenericSql() {
        assertSame(SqlDialects.ANSI, SqlDialectResolver.resolve(getProject(), sqlFile("select 1")));
    }

    public void testBindingPrecedenceIsFileThenProjectDefaultThenGeneric() {
        connections.addConnection(mysql);
        connections.addConnection(postgres);
        VirtualFile file = sqlFile("select 1");
        SqlBindingsState bindings = SqlBindingsState.getInstance(getProject());

        bindings.setDefaultConnectionId(mysql.getId());
        assertSame(SqlDialects.MYSQL, SqlDialectResolver.resolve(getProject(), file));

        bindings.bindFileToConnection(file, postgres.getId());
        assertSame("A file binding beats the project default", SqlDialects.POSTGRESQL, SqlDialectResolver.resolve(getProject(), file));
        assertEquals(postgres.getId(), SqlDialectResolver.connection(getProject(), file).getId());

        bindings.bindFileToDialect(file, SqlDialects.ANSI.id());
        assertSame("An explicit generic choice beats the project default", SqlDialects.ANSI, SqlDialectResolver.resolve(getProject(), file));
        assertNull(SqlDialectResolver.connection(getProject(), file));

        bindings.clearFile(file);
        assertSame(SqlDialects.MYSQL, SqlDialectResolver.resolve(getProject(), file));
    }

    public void testABindingToADeletedConnectionFallsThrough() {
        connections.addConnection(mysql);
        VirtualFile file = sqlFile("select 1");
        SqlBindingsState bindings = SqlBindingsState.getInstance(getProject());
        bindings.bindFileToConnection(file, mysql.getId());
        assertSame(SqlDialects.MYSQL, SqlDialectResolver.resolve(getProject(), file));
        connections.removeConnection(mysql.getId());
        assertSame(SqlDialects.ANSI, SqlDialectResolver.resolve(getProject(), file));
    }

    // --- highlighting follows the dialect ------------------------------------------------------------------------

    public void testTheSameTextHighlightsDifferentlyPerDialect() {
        connections.addConnection(mysql);
        connections.addConnection(postgres);
        VirtualFile file = sqlFile("# note\nselect 1");
        SqlBindingsState bindings = SqlBindingsState.getInstance(getProject());
        assertSame("Generic SQL has no # comments", LatticeSqlTokens.OPERATOR, firstToken());

        bindings.bindFileToConnection(file, mysql.getId());
        SqlEditorRefresh.refresh(getProject(), file);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertSame("MySQL # starts a line comment", LatticeSqlTokens.LINE_COMMENT, firstToken());

        bindings.bindFileToConnection(file, postgres.getId());
        SqlEditorRefresh.refresh(getProject(), file);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertSame("In PostgreSQL # is an operator", LatticeSqlTokens.OPERATOR, firstToken());
    }

    public void testTheHighlighterFactoryResolvesTheFilesDialect() {
        connections.addConnection(postgres);
        VirtualFile file = sqlFile("select 1");
        SqlBindingsState.getInstance(getProject()).bindFileToConnection(file, postgres.getId());
        LatticeSqlSyntaxHighlighter highlighter = (LatticeSqlSyntaxHighlighter)
                SyntaxHighlighterFactory.getSyntaxHighlighter(LatticeSqlLanguage.INSTANCE, getProject(), file);
        assertSame(SqlDialects.POSTGRESQL, highlighter.profile());
    }

    public void testTurningHighlightingOffRemovesColorButKeepsTheLexer() {
        VirtualFile file = sqlFile("select 1");
        LatticeSqlSyntaxHighlighter on = (LatticeSqlSyntaxHighlighter)
                SyntaxHighlighterFactory.getSyntaxHighlighter(LatticeSqlLanguage.INSTANCE, getProject(), file);
        assertEquals(1, on.getTokenHighlights(LatticeSqlTokens.KEYWORD).length);

        SqlEditorSettings.getInstance().setHighlighting(false);
        LatticeSqlSyntaxHighlighter off = (LatticeSqlSyntaxHighlighter)
                SyntaxHighlighterFactory.getSyntaxHighlighter(LatticeSqlLanguage.INSTANCE, getProject(), file);
        assertEquals(0, off.getTokenHighlights(LatticeSqlTokens.KEYWORD).length);
        assertNotNull(off.getHighlightingLexer());
    }

    // --- the connection banner ------------------------------------------------------------------------------------

    public void testTheBannerAppearsOnlyForUnboundSqlFilesWhenConnectionsExist() {
        SqlConnectionBanner banner = new SqlConnectionBanner();
        VirtualFile file = sqlFile("select 1");
        assertNull("No connections, nothing to offer", banner.collectNotificationData(getProject(), file));

        connections.addConnection(mysql);
        assertNotNull(banner.collectNotificationData(getProject(), file));
        FileEditor editor = myFixture.getEditor() == null ? null : com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()).getSelectedEditor(file);
        assertNotNull(banner.collectNotificationData(getProject(), file).apply(editor));

        SqlBindingsState.getInstance(getProject()).bindFileToConnection(file, mysql.getId());
        assertNull("A bound file needs no banner", banner.collectNotificationData(getProject(), file));

        SqlBindingsState.getInstance(getProject()).clearFile(file);
        SqlBindingsState.getInstance(getProject()).setDefaultConnectionId(mysql.getId());
        assertNull("A project default covers unbound files", banner.collectNotificationData(getProject(), file));

        VirtualFile other = myFixture.configureByText("notes.txt", "select 1").getVirtualFile();
        SqlBindingsState.getInstance(getProject()).setDefaultConnectionId("");
        assertNull("Only SQL files get the banner", banner.collectNotificationData(getProject(), other));
    }

    // --- startup reconciliation and the settings page --------------------------------------------------------------

    public void testTheFirstRunClaimsTheExtensionAndAnnouncesItOnce() {
        SqlFileTypeClaim.apply(false);
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        assertNull(settings.appliedClaim());
        SqlEditorSupport.initialize(getProject());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue(SqlFileTypeClaim.isClaimed());
        assertEquals(Boolean.TRUE, settings.appliedClaim());
        assertTrue(settings.takeoverNoticeShown());
    }

    public void testStartupDoesNotOverrideAManualFileTypeChange() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        settings.appliedClaim(true);
        settings.takeoverNoticeShown(true);
        SqlFileTypeClaim.apply(false); // the user reassigned *.sql under Settings | File Types
        SqlEditorSupport.initialize(getProject());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse("The setting did not change, so the user's mapping stands", SqlFileTypeClaim.isClaimed());
    }

    public void testStartupAppliesAChangedSetting() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        settings.appliedClaim(true);
        settings.takeoverNoticeShown(true);
        settings.setHandleSqlFiles(false);
        SqlEditorSupport.initialize(getProject());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse(SqlFileTypeClaim.isClaimed());
        assertEquals(Boolean.FALSE, settings.appliedClaim());
    }

    public void testNothingIsAnnouncedOrClaimedWhileTakeoverIsOff() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        settings.setHandleSqlFiles(false);
        SqlEditorSupport.initialize(getProject());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertFalse(SqlFileTypeClaim.isClaimed());
        assertFalse(settings.takeoverNoticeShown());
    }

    public void testTheSettingsPageAppliesAToggle() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        SqlEditorConfigurable page = new SqlEditorConfigurable();
        JComponent component = page.createComponent();
        JBCheckBox handleFiles = UIUtil.findComponentsOfType(component, JBCheckBox.class).stream()
                .filter(box -> box.getText().startsWith("Handle .sql files")).findFirst().orElseThrow();
        try {
            assertFalse(page.isModified());
            handleFiles.setSelected(false);
            assertTrue(page.isModified());
            page.apply();
            assertFalse(settings.isHandleSqlFiles());
            assertFalse("Applying the page gives the extension back", SqlFileTypeClaim.isClaimed());
            assertFalse(page.isModified());

            handleFiles.setSelected(true);
            page.apply();
            assertTrue(SqlFileTypeClaim.isClaimed());
        } finally {
            page.disposeUIResources();
        }
    }
}
