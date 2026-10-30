package com.segfault03.ideadb.sql.binding;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.segfault03.ideadb.sql.SqlEditorSupport;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;

/** Tools menu: give .sql files back to the IDE's own SQL file type, immediately and without a restart. */
public final class RestoreBuiltInSqlAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(SqlEditorSettings.getInstance().isTakeoverEffective());
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        SqlEditorSettings.getInstance().setHandleSqlFiles(false);
        SqlEditorSupport.settingsChanged();
        var notification = NotificationGroupManager.getInstance().getNotificationGroup("Lattice SQL editor")
                .createNotification("The IDE's own SQL file type is back",
                        "Lattice no longer handles .sql files. SQL in Java and Kotlin strings is not affected.", NotificationType.INFORMATION);
        notification.addAction(NotificationAction.createSimpleExpiring("Let Lattice handle .sql files again", () -> {
            SqlEditorSettings.getInstance().setHandleSqlFiles(true);
            SqlEditorSupport.settingsChanged();
        }));
        notification.notify(event.getProject());
    }
}
