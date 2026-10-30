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

    /**
     * The inspections page loads after it is shown and resets its own selection while it does, so one call to select the group
     * is not enough. The page here ignores the first calls (not loaded yet) and drops the selection once after taking it.
     */
    public void testTheInspectionsPageIsAskedAgainUntilTheGroupHolds() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var selected = new java.util.concurrent.atomic.AtomicBoolean();
        var asked = new java.util.ArrayList<String>();
        var page = (com.intellij.profile.codeInspection.ui.ErrorsConfigurable) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {com.intellij.profile.codeInspection.ui.ErrorsConfigurable.class}, (proxy, method, args) -> {
                    if (method.getName().equals("selectInspectionGroup")) {
                        asked.add(((String[]) args[0])[0]);
                        int call = calls.incrementAndGet();
                        if (call == 1) throw new IllegalStateException("the panel does not exist yet");
                        if (call >= 3) selected.set(true);
                    }
                    return null;
                });
        var settled = new java.util.concurrent.atomic.AtomicInteger();
        InspectionsPage.selectGroup(page, () -> null, root -> {
            // Once the group has been taken, the page drops it again once, as it does when it finishes loading.
            if (calls.get() == 3 && settled.getAndIncrement() == 0) selected.set(false);
            return selected.get();
        });
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("The group was never selected for good",
                () -> calls.get() >= 4 && selected.get(), 20);
        assertEquals("Lattice SQL", asked.get(0));
        assertTrue("it asked again after the page dropped the selection", calls.get() >= 4);
    }
}
