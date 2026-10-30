package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import org.jetbrains.annotations.NotNull;

/** Editor menu action: choose the connection for the current SQL file. */
public final class ChooseSqlConnectionAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && file != null && file.getFileType() == LatticeSqlFileType.INSTANCE);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (event.getProject() != null && file != null) SqlConnectionChooser.chooseForFile(event.getProject(), file);
    }
}
