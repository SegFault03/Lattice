package com.segfault03.ideadb.release;

import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.APP)
@State(name = "LatticeReleaseNotes", storages = @Storage(value = "LatticeReleaseNotes.xml", roamingType = RoamingType.DISABLED))
public final class ReleaseNotesAnnouncement implements PersistentStateComponent<ReleaseNotesAnnouncement.State> {
    public static final class State {
        public List<String> announcedVersions = new ArrayList<>();
        public boolean enabled = true;
    }
    private State state = new State();
    public static ReleaseNotesAnnouncement getInstance() {
        return ApplicationManager.getApplication().getService(ReleaseNotesAnnouncement.class);
    }
    @Override public synchronized State getState() { return state; }
    @Override public synchronized void loadState(State state) { this.state = state; }

    /** Application scope prevents duplicate notices in simultaneous project windows. */
    public synchronized boolean announceOnce(String version, Runnable show) {
        if (!state.enabled || state.announcedVersions.contains(version)) return false;
        show.run();
        state.announcedVersions.add(version);
        return true;
    }
    public synchronized void disable() { state.enabled = false; }
    public synchronized boolean isEnabled() { return state.enabled; }
    public synchronized void setEnabled(boolean enabled) { state.enabled = enabled; }
    public void announce(Project project) {
        if (project.isDisposed() || ApplicationManager.getApplication().isUnitTestMode()) return;
        announceOnce(ReleaseNotesContent.version(), () -> {
            Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("Lattice updates")
                    .createNotification("Lattice " + ReleaseNotesContent.version() + " is ready",
                            "New data sources, a redesigned alter-table dialog, and clearer value editing.", NotificationType.INFORMATION);
            notification.addAction(NotificationAction.createSimpleExpiring("View what's new",
                    () -> { if (!project.isDisposed()) DatabaseEditorManager.getInstance(project).openReleaseNotes(); }));
            notification.addAction(NotificationAction.createSimpleExpiring("Don't show again", this::disable));
            notification.notify(project);
        });
    }
}
