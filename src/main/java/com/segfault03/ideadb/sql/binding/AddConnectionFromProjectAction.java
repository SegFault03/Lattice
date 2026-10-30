package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.segfault03.ideadb.sql.discovery.ProjectConnectionOffer;
import org.jetbrains.annotations.NotNull;

/** Tools menu: add a connection for a database that the project's configuration files point at. */
public final class AddConnectionFromProjectAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        var project = event.getProject();
        event.getPresentation().setEnabledAndVisible(project != null && !ProjectConnectionOffer.open(project).isEmpty());
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) ProjectConnectionOffer.choose(event.getProject(), event.getData(CommonDataKeys.VIRTUAL_FILE));
    }
}
