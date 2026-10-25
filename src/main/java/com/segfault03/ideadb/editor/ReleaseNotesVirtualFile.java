package com.segfault03.ideadb.editor;

import com.intellij.openapi.project.Project;
import com.segfault03.ideadb.release.ReleaseNotesContent;
import com.segfault03.ideadb.ui.ReleaseNotesPanel;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

public final class ReleaseNotesVirtualFile extends DatabaseVirtualFile {
    public ReleaseNotesVirtualFile() {
        super("What's New in Lattice", "release-notes:" + ReleaseNotesContent.version(), DatabaseFileTypes.WELCOME);
    }
    @Override public JComponent createComponent(@NotNull Project project) { return new ReleaseNotesPanel(project); }
}
