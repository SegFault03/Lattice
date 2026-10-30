package com.segfault03.ideadb.sql;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlEditorRefresh;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin;
import com.segfault03.ideadb.sql.settings.SqlEditorConfigurable;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.settings.SqlFileTypeClaim;
import com.segfault03.ideadb.state.DatabaseSettingsListener;
import com.segfault03.ideadb.state.DatabaseSettingsState;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the IDE's file type mapping, the notices and open editors in step with the SQL editor settings. */
public final class SqlEditorSupport {
    private static final String NOTIFICATION_GROUP = "Lattice SQL editor";

    private SqlEditorSupport() {}

    /**
     * Runs when a project opens. The mapping is reconciled only when the effective setting differs from what was
     * last applied, so a user who changes the mapping by hand under Settings | File Types is not overridden.
     */
    public static void initialize(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            SqlEditorSettings settings = SqlEditorSettings.getInstance();
            boolean claim = settings.isTakeoverEffective();
            if (settings.appliedClaim() == null || settings.appliedClaim() != claim) {
                SqlFileTypeClaim.apply(claim);
                settings.appliedClaim(claim);
            }
            if (claim && !settings.takeoverNoticeShown()) {
                settings.takeoverNoticeShown(true);
                announceTakeover(project);
            }
            if (UltimateDatabasePlugin.isActive() && !settings.isExplicit() && !settings.ultimateNoticeShown()) {
                settings.ultimateNoticeShown(true);
                announceStandDown(project);
            }
            pruneBindings(project);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                if (!project.isDisposed()) SqlBindingsState.getInstance(project).pruneMissingFiles();
            });
            com.segfault03.ideadb.sql.discovery.JdbcDiscovery.getInstance(project).refresh();
            // A deleted or retyped connection changes the dialect of the files bound to it; a changed password or option does not.
            AtomicReference<List<List<String>>> known = new AtomicReference<>(fingerprint());
            ApplicationManager.getApplication().getMessageBus().connect(SqlEditorProjectScope.of(project))
                    .subscribe(DatabaseSettingsListener.TOPIC, (DatabaseSettingsListener) () -> {
                        pruneBindings(project);
                        List<List<String>> now = fingerprint();
                        if (now.equals(known.getAndSet(now))) return;
                        SqlBindingsState.getInstance(project).connectionsChanged();
                        SqlEditorRefresh.refreshAll(project);
                    });
        });
    }

    /** What dialect resolution and discovery matching read from the saved connections; kept in memory only, as it includes the custom URL. */
    private static List<List<String>> fingerprint() {
        return DatabaseSettingsState.getInstance().getConnections().stream().map(config -> List.of(config.getId(), config.getName(), config.getType().name(),
                String.valueOf(config.getHsqlMode()), String.valueOf(config.getHost()), String.valueOf(config.getPort()),
                String.valueOf(config.getDatabaseName()), String.valueOf(config.getUser()), String.valueOf(config.getCustomUrl()))).toList();
    }

    private static void pruneBindings(Project project) {
        var existing = DatabaseSettingsState.getInstance().getConnections().stream().map(ConnectionConfig::getId).collect(java.util.stream.Collectors.toSet());
        SqlBindingsState.getInstance(project).pruneConnections(existing);
    }

    /** Applies settings the user just changed: the file type mapping and every open SQL editor. */
    public static void settingsChanged() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        boolean claim = settings.isTakeoverEffective();
        SqlFileTypeClaim.apply(claim);
        settings.appliedClaim(claim);
        for (Project project : ProjectManager.getInstance().getOpenProjects()) SqlEditorRefresh.refreshAll(project);
    }

    /** Re-reads which strings are SQL in the open source files after the injection settings changed. */
    public static void injectionSettingsChanged() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                var files = java.util.Arrays.stream(com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getOpenFiles())
                        .filter(file -> file.getFileType() != com.segfault03.ideadb.sql.lang.LatticeSqlFileType.INSTANCE).toList();
                com.intellij.util.FileContentUtil.reparseFiles(project, files, true);
            });
        }
    }

    private static void announceTakeover(Project project) {
        Notification notification = group().createNotification("Lattice now highlights .sql files",
                "It replaces the IDE's highlighting-only SQL type. You can turn this off.", NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimpleExpiring("Turn off", () -> {
            SqlEditorSettings.getInstance().setHandleSqlFiles(false);
            settingsChanged();
        }));
        notification.addAction(NotificationAction.createSimple("Settings", () -> openSettings(project)));
        notification.notify(project);
    }

    private static void announceStandDown(Project project) {
        Notification notification = group().createNotification("Lattice SQL editor support is off",
                "The Database plugin already provides SQL support here. You can turn Lattice's on anyway.", NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimpleExpiring("Turn on anyway", () -> {
            SqlEditorSettings.getInstance().setEnabled(true);
            settingsChanged();
        }));
        notification.addAction(NotificationAction.createSimple("Settings", () -> openSettings(project)));
        notification.notify(project);
    }

    private static com.intellij.notification.NotificationGroup group() {
        return NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP);
    }

    private static void openSettings(Project project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, SqlEditorConfigurable.class);
    }
}
