package com.vibe.ideadb.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.vibe.ideadb.editor.DatabaseEditorManager;
import com.vibe.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;

public class DatabaseToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setIcon(Icons.LATTICE);
        DatabaseMainPanel panel = new DatabaseMainPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);

        if (DatabaseSettingsState.getInstance().isShowWelcomeScreen()) {
            DatabaseEditorManager.getInstance(project).openWelcome();
        }
    }
}
