package com.segfault03.ideadb.sql.coexist;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.LicensingFacade;
import com.segfault03.ideadb.sql.SqlEditorSupport;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin.Presence;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What Lattice does beside the Database plugin: stands down in Ultimate, stays on and warns in the Community edition. */
public class DatabasePluginCoexistenceFixtureTest extends BasePlatformTestCase {
    private final List<Notification> shown = new ArrayList<>();
    private SqlEditorSettings settings;

    @Override protected void setUp() throws Exception {
        super.setUp();
        settings = SqlEditorSettings.getInstance();
        settings.loadState(new SqlEditorSettings.State());
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override public void notify(Notification notification) { shown.add(notification); }
        });
    }

    @Override protected void tearDown() throws Exception {
        try {
            settings.loadState(new SqlEditorSettings.State());
        } finally {
            super.tearDown();
        }
    }

    private static LicensingFacade licensing(String productCode, Map<String, String> stamps) {
        LicensingFacade licensing = new LicensingFacade();
        licensing.platformProductCode = productCode;
        licensing.confirmationStamps = stamps;
        return licensing;
    }

    private void announce(Presence presence) {
        UltimateDatabasePlugin.setPresenceForTests(presence, getTestRootDisposable());
        SqlEditorSupport.announceCoexistence(getProject());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    public void testALicenseOrTrialForTheProductMeansUltimate() {
        assertEquals(Presence.ULTIMATE, UltimateDatabasePlugin.presenceOf(true, licensing("II", Map.of("II", "key:stamp"))));
        assertEquals("A trial counts", Presence.ULTIMATE, UltimateDatabasePlugin.presenceOf(true, licensing("II", Map.of("II", "eval:stamp"))));
    }

    public void testNoLicenseForTheProductMeansCommunity() {
        assertEquals(Presence.COMMUNITY, UltimateDatabasePlugin.presenceOf(true, null));
        assertEquals(Presence.COMMUNITY, UltimateDatabasePlugin.presenceOf(true, licensing("II", Map.of())));
        assertEquals("A license for something else", Presence.COMMUNITY, UltimateDatabasePlugin.presenceOf(true, licensing("II", Map.of("PDB", "key:stamp"))));
        assertEquals(Presence.COMMUNITY, UltimateDatabasePlugin.presenceOf(true, licensing(null, Map.of("II", "key:stamp"))));
    }

    public void testWithoutTheEnabledPluginThereIsNothingToConflictWith() {
        assertEquals(Presence.ABSENT, UltimateDatabasePlugin.presenceOf(false, null));
        assertEquals(Presence.ABSENT, UltimateDatabasePlugin.presenceOf(false, licensing("II", Map.of("II", "key:stamp"))));
    }

    public void testUltimateStartsOffAndAnExplicitChoiceWins() {
        UltimateDatabasePlugin.setPresenceForTests(Presence.ULTIMATE, getTestRootDisposable());
        assertFalse(settings.isEnabled());
        assertFalse(settings.isSqlCardsEffective());
        settings.setEnabled(true);
        assertTrue(settings.isEnabled());
    }

    public void testTheCommunityEditionKeepsEverythingOnAndAnExplicitChoiceWins() {
        UltimateDatabasePlugin.setPresenceForTests(Presence.COMMUNITY, getTestRootDisposable());
        assertTrue(settings.isEnabled());
        assertTrue(settings.isSqlCardsEffective());
        assertTrue(settings.isHighlightingEffective());
        assertTrue(settings.isCompletionEffective());
        assertTrue(settings.isInjectionEffective());
        assertFalse(UltimateDatabasePlugin.isActive());
        settings.setEnabled(false);
        assertFalse(settings.isEnabled());
    }

    public void testUltimateWarnsThatSupportIsOffOnlyOnce() {
        announce(Presence.ULTIMATE);
        assertEquals(1, shown.size());
        assertEquals(NotificationType.WARNING, shown.get(0).getType());
        assertEquals("Lattice SQL editor support is off", shown.get(0).getTitle());
        assertEquals(List.of("Turn on anyway", "Settings"), shown.get(0).getActions().stream().map(action -> action.getTemplateText()).toList());
        announce(Presence.ULTIMATE);
        assertEquals(1, shown.size());
    }

    public void testTheCommunityEditionWarnsOfConflictsOnlyOnce() {
        announce(Presence.COMMUNITY);
        assertEquals(1, shown.size());
        assertEquals(NotificationType.WARNING, shown.get(0).getType());
        assertEquals("Lattice SQL editor support is on", shown.get(0).getTitle());
        assertTrue(shown.get(0).getContent().contains("conflicts"));
        assertEquals(List.of("Turn off", "Settings"), shown.get(0).getActions().stream().map(action -> action.getTemplateText()).toList());
        announce(Presence.COMMUNITY);
        assertEquals(1, shown.size());
    }

    public void testNoWarningWithoutThePluginOrAfterTheUserHasChosen() {
        announce(Presence.ABSENT);
        assertEmpty(shown);
        settings.setEnabled(true);
        announce(Presence.ULTIMATE);
        announce(Presence.COMMUNITY);
        assertEmpty(shown);
    }
}
