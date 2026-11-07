package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileTypes.ExtensionFileNameMatcher;
import com.intellij.openapi.fileTypes.FileNameMatcher;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;

/**
 * Takes the .sql extension from the IDE's SQL file type and gives it back. The same operation a user performs under
 * Settings | Editor | File Types, so it takes effect immediately and survives restarts without a restart.
 */
public final class SqlFileTypeClaim {
    private static final FileNameMatcher SQL = new ExtensionFileNameMatcher("sql");
    private static final String SAMPLE = "query.sql";

    private SqlFileTypeClaim() {}

    public static boolean isClaimed() { return FileTypeManager.getInstance().getFileTypeByFileName(SAMPLE) == LatticeSqlFileType.INSTANCE; }

    /** Applies the mapping; idempotent, and a no-op when it already matches. Runs the change under a write action. */
    public static void apply(boolean claim) {
        FileTypeManager manager = FileTypeManager.getInstance();
        if (claim == isClaimed()) return;
        WriteAction.runAndWait(() -> {
            if (claim) {
                manager.associate(LatticeSqlFileType.INSTANCE, SQL);
                return;
            }
            // "SQL" is the IDE's own SQL type: the highlighting-only type in Community, the Database plugin's type in Ultimate.
            FileType builtIn = manager.findFileTypeByName("SQL");
            if (builtIn != null && builtIn != LatticeSqlFileType.INSTANCE) manager.associate(builtIn, SQL);
            else manager.removeAssociation(LatticeSqlFileType.INSTANCE, SQL);
        });
    }
}
