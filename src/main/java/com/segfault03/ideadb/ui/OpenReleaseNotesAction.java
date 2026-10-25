package com.segfault03.ideadb.ui;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import org.jetbrains.annotations.NotNull;

public final class OpenReleaseNotesAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) DatabaseEditorManager.getInstance(event.getProject()).openReleaseNotes();
    }
    @Override public void update(@NotNull AnActionEvent event) { event.getPresentation().setEnabled(event.getProject() != null); }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
