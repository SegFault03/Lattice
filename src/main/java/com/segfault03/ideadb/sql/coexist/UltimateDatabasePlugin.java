package com.segfault03.ideadb.sql.coexist;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;

/** IntelliJ IDEA Ultimate's Database plugin brings its own SQL language; Lattice stands down where it is enabled. */
public final class UltimateDatabasePlugin {
    /** Taken from the Database plugin's id as referenced by JetBrains' own bundled descriptors. */
    public static final PluginId ID = PluginId.getId("com.intellij.database");

    private UltimateDatabasePlugin() {}

    public static boolean isActive() {
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(ID);
        return descriptor != null && descriptor.isEnabled();
    }
}
