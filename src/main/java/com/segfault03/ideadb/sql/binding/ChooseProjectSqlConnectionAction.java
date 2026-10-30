package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/** Tools menu action: choose the default connection whose dialect applies to SQL in this project. */
public final class ChooseProjectSqlConnectionAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabledAndVisible(event.getProject() != null); }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) SqlConnectionChooser.chooseForProject(event.getProject());
    }
}
