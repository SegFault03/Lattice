package com.segfault03.ideadb.sql.settings;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.sql.FixtureConnections;
import com.segfault03.ideadb.sql.inject.SqlKnownCalls;

import java.util.function.BooleanSupplier;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.util.List;

/** Settings | Tools | Lattice SQL Editor: what the page shows, what makes it modified, and what apply and reset do. */
public class SqlEditorSettingsPageFixtureTest extends BasePlatformTestCase {
    private SqlEditorConfigurable page;
    private JComponent component;

    @Override protected void setUp() throws Exception {
        super.setUp();
        FixtureConnections.install(getTestRootDisposable(), false);
        SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
        SqlFileTypeClaim.apply(true);
        page = new SqlEditorConfigurable();
        component = page.createComponent();
    }

    @Override protected void tearDown() throws Exception {
        try {
            page.disposeUIResources();
            SqlEditorSettings.getInstance().loadState(new SqlEditorSettings.State());
            SqlFileTypeClaim.apply(true);
        } finally {
            super.tearDown();
        }
    }

    private JBCheckBox box(String textStart) {
        return UIUtil.findComponentsOfType(component, JBCheckBox.class).stream()
                .filter(candidate -> candidate.getText().startsWith(textStart)).findFirst().orElseThrow(() -> new AssertionError("no checkbox " + textStart));
    }

    @SuppressWarnings("unchecked")
    private CollectionListModel<String> methods() {
        return (CollectionListModel<String>) UIUtil.findComponentsOfType(component, JBList.class).get(0).getModel();
    }

    @SuppressWarnings("unchecked")
    private JComboBox<SqlEditorSettings.KeywordCase> keywordCase() {
        return (JComboBox<SqlEditorSettings.KeywordCase>) UIUtil.findComponentsOfType(component, JComboBox.class).get(0);
    }

    public void testThePageKeepsItsIdAndDisplayName() {
        assertEquals("com.segfault03.lattice.sql.editor", page.getId());
        assertEquals("Lattice SQL Editor", page.getDisplayName());
    }

    public void testAFreshPageShowsTheSavedStateAndIsNotModified() {
        assertFalse(page.isModified());
        assertTrue(box("Enable Lattice SQL editor support").isSelected());
        assertTrue(box("Code completion").isSelected());
        assertTrue(box("Recognize SQL in strings").isSelected());
        assertEquals(SqlEditorSettings.KeywordCase.AS_TYPED, keywordCase().getSelectedItem());
        assertEquals(SqlKnownCalls.DEFAULT.lines().toList(), methods().getItems());
    }

    public void testEveryCheckboxIsAppliedAndResetAndNotifiesTheModifiedState() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        record Toggle(String text, BooleanSupplier saved) {}
        List<Toggle> toggles = List.of(
                new Toggle("Handle .sql files", settings::isHandleSqlFiles),
                new Toggle("Syntax highlighting", settings::isHighlighting),
                new Toggle("Code completion", settings::isCompletion),
                new Toggle("Hover documentation", settings::isHover),
                new Toggle("Validation", settings::isValidation),
                new Toggle("Recognize SQL in strings", settings::isInjection),
                new Toggle("Strings marked", settings::isInjectExplicit),
                new Toggle("Strings passed to these methods", settings::isInjectKnownCalls),
                new Toggle("Strings that look like SQL", settings::isInjectContent));
        for (Toggle toggle : toggles) {
            JBCheckBox box = box(toggle.text());
            box.setSelected(false);
            assertTrue(toggle.text() + " makes the page modified", page.isModified());
            page.apply();
            assertFalse(toggle.text() + " is saved", toggle.saved().getAsBoolean());
            assertFalse(page.isModified());
            box.setSelected(true);
            page.reset();
            assertFalse("reset puts back what was saved: " + toggle.text(), box.isSelected());
            box.setSelected(true);
            page.apply();
            assertTrue(toggle.saved().getAsBoolean());
        }
    }

    public void testTheMasterSwitchIsExplicitOnceChanged() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        assertFalse(settings.isExplicit());
        page.apply();
        assertFalse("Applying an unchanged page keeps the automatic switch", settings.isExplicit());
        box("Enable Lattice SQL editor support").setSelected(false);
        page.apply();
        assertTrue(settings.isExplicit());
        assertFalse(settings.isEnabled());
    }

    public void testKeywordCaseIsAppliedAndReset() {
        keywordCase().setSelectedItem(SqlEditorSettings.KeywordCase.UPPER);
        assertTrue(page.isModified());
        page.apply();
        assertEquals(SqlEditorSettings.KeywordCase.UPPER, SqlEditorSettings.getInstance().keywordCase());
        keywordCase().setSelectedItem(SqlEditorSettings.KeywordCase.LOWER);
        page.reset();
        assertEquals(SqlEditorSettings.KeywordCase.UPPER, keywordCase().getSelectedItem());
        assertFalse(page.isModified());
    }

    public void testTheMethodListIsEditedAppliedAndReset() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        methods().add("myQuery");
        assertTrue(page.isModified());
        page.apply();
        assertTrue(settings.knownCalls().lines().toList().contains("myQuery"));
        assertTrue(settings.injectionTiers().calls().contains("myQuery"));
        assertFalse(page.isModified());

        methods().remove(0);
        assertTrue("removing an entry is a change", page.isModified());
        page.reset();
        assertFalse(page.isModified());
        assertTrue(methods().getItems().contains("prepareStatement"));

        methods().setElementAt("renamedQuery", methods().getSize() - 1);
        page.apply();
        assertTrue(settings.knownCalls().lines().toList().contains("renamedQuery"));
        assertFalse(settings.knownCalls().lines().toList().contains("myQuery"));
    }

    public void testAnEmptyListMeansTheDefaultList() {
        methods().removeAll();
        assertTrue(page.isModified());
        page.apply();
        assertEquals(SqlKnownCalls.DEFAULT, SqlEditorSettings.getInstance().knownCalls());
        assertEquals("the page shows the default again", SqlKnownCalls.DEFAULT.lines().toList(), methods().getItems());
        assertFalse(page.isModified());
    }

    public void testASavedListWithCommentsAndBlankLinesShowsOnlyTheEntries() {
        SqlEditorSettings.getInstance().knownCalls("\nfoo\n\n  bar  \n");
        page.reset();
        assertEquals(List.of("foo", "bar"), methods().getItems());
        assertFalse("blank lines and padding are not a change", page.isModified());
    }

    public void testTheSqlSettingsAreFollowedByTheAppliedClaim() {
        box("Handle .sql files").setSelected(false);
        page.apply();
        assertFalse(SqlFileTypeClaim.isClaimed());
        box("Handle .sql files").setSelected(true);
        page.apply();
        assertTrue(SqlFileTypeClaim.isClaimed());
    }
}
