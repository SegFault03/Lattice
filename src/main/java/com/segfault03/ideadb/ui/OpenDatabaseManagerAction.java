package com.segfault03.ideadb.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public class OpenDatabaseManagerAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("Lattice");
            if (tw == null) {
                tw = ToolWindowManager.getInstance(project).getToolWindow("Database Manager");
            }
            if (tw != null) {
                tw.activate(null, true);
            }
        }
    }
}
