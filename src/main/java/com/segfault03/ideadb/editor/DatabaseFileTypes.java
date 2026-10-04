package com.segfault03.ideadb.editor;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.FileType;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class DatabaseFileTypes {
    private DatabaseFileTypes() {}

    public static final FileType TABLE = new FileType() {
        @Override public @NotNull String getName() { return "DatabaseTable"; }
        @Override public @NotNull @Nls String getDescription() { return "Database Table Data Editor"; }
        @Override public @NotNull String getDefaultExtension() { return "dbtable"; }
        @Override public @Nullable Icon getIcon() { return Icons.TABLE; }
        @Override public boolean isBinary() { return true; }
        @Override public boolean isReadOnly() { return false; }
    };

    public static final FileType CONSOLE = new FileType() {
        @Override public @NotNull String getName() { return "DatabaseConsole"; }
        @Override public @NotNull @Nls String getDescription() { return "Database SQL Console"; }
        @Override public @NotNull String getDefaultExtension() { return "dbsql"; }
        @Override public @Nullable Icon getIcon() { return Icons.CONSOLE; }
        @Override public boolean isBinary() { return true; }
        @Override public boolean isReadOnly() { return false; }
    };

    public static final FileType WELCOME = new FileType() {
        @Override public @NotNull String getName() { return "DatabaseWelcome"; }
        @Override public @NotNull @Nls String getDescription() { return "Lattice Welcome"; }
        @Override public @NotNull String getDefaultExtension() { return "dbwelcome"; }
        @Override public @Nullable Icon getIcon() { return Icons.LATTICE; }
        @Override public boolean isBinary() { return true; }
        @Override public boolean isReadOnly() { return false; }
    };
}
