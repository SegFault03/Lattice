package com.vibe.ideadb.editor;

import com.intellij.openapi.project.Project;
import com.vibe.ideadb.ui.WelcomePanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class WelcomeVirtualFile extends DatabaseVirtualFile {
    private WelcomePanel panel;

    public WelcomeVirtualFile() {
        super("Lattice", "welcome:root", DatabaseFileTypes.WELCOME);
    }

    @Override
    public JComponent createComponent(@NotNull Project project) {
        if (panel == null) {
            panel = new WelcomePanel(project);
        }
        return panel;
    }

    public WelcomePanel getPanel() {
        return panel;
    }
}
