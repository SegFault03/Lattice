package com.segfault03.ideadb.sql.coexist;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.TestOnly;

/** IntelliJ IDEA Ultimate's Database plugin brings its own SQL language; Lattice stands down where it is enabled. */
public final class UltimateDatabasePlugin {
    /** Taken from the Database plugin's id as referenced by JetBrains' own bundled descriptors. */
    public static final PluginId ID = PluginId.getId("com.intellij.database");

    private static volatile Boolean overriddenForTests;

    private UltimateDatabasePlugin() {}

    /** Makes {@link #isActive()} answer {@code active} until {@code parent} is disposed. */
    @TestOnly
    public static void setActiveForTests(boolean active, Disposable parent) {
        overriddenForTests = active;
        Disposer.register(parent, () -> overriddenForTests = null);
    }

    public static boolean isActive() {
        Boolean override = overriddenForTests;
        if (override != null) return override;
        IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(ID);
        return descriptor != null && descriptor.isEnabled();
    }
}
