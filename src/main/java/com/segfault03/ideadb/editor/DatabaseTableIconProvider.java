package com.segfault03.ideadb.editor;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** Supplies a connection-specific icon for the virtual files backing table editor tabs. */
public final class DatabaseTableIconProvider implements FileIconProvider {
    @Override
    public @Nullable Icon getIcon(@NotNull VirtualFile file, int flags, @Nullable Project project) {
        if (file instanceof TableDataVirtualFile table) {
            return Icons.tableWithDataSource(table.getConfig().getType());
        }
        return null;
    }
}
