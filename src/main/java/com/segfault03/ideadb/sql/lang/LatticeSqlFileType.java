package com.segfault03.ideadb.sql.lang;

import com.intellij.openapi.fileTypes.LanguageFileType;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class LatticeSqlFileType extends LanguageFileType {
    public static final LatticeSqlFileType INSTANCE = new LatticeSqlFileType();
    private LatticeSqlFileType() { super(LatticeSqlLanguage.INSTANCE); }
    @Override public @NotNull String getName() { return "Lattice SQL"; }
    @Override public @NotNull String getDescription() { return "SQL with Lattice connection awareness"; }
    @Override public @NotNull String getDefaultExtension() { return "sql"; }
    @Override public Icon getIcon() { return Icons.SQL_FILE; }
}
