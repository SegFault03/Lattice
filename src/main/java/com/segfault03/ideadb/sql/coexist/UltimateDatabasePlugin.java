package com.segfault03.ideadb.sql.coexist;

import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.LicensingFacade;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * The Database plugin brings its own SQL language, and unified IntelliJ IDEA builds bundle it. Lattice stands down in
 * IntelliJ IDEA Ultimate; in the Community edition it stays on and warns that the two may conflict.
 */
public final class UltimateDatabasePlugin {
    /** Taken from the Database plugin's id as referenced by JetBrains' own bundled descriptors. */
    public static final PluginId ID = PluginId.getId("com.intellij.database");

    public enum Presence {
        /** No enabled Database plugin, so there is nothing to conflict with. */
        ABSENT,
        /** The plugin is enabled in an IDE without an Ultimate license: Lattice stays on and warns. */
        COMMUNITY,
        /** The plugin is enabled in IntelliJ IDEA Ultimate, a trial included: Lattice stands down. */
        ULTIMATE
    }

    private static volatile Presence overriddenForTests;

    private UltimateDatabasePlugin() {}

    /** Makes {@link #presence()} answer {@code presence} until {@code parent} is disposed. */
    @TestOnly
    public static void setPresenceForTests(@NotNull Presence presence, Disposable parent) {
        overriddenForTests = presence;
        Disposer.register(parent, () -> overriddenForTests = null);
    }

    /** Makes {@link #isActive()} answer {@code active} until {@code parent} is disposed. */
    @TestOnly
    public static void setActiveForTests(boolean active, Disposable parent) {
        setPresenceForTests(active ? Presence.ULTIMATE : Presence.ABSENT, parent);
    }

    /** Whether Lattice should stand down: the Database plugin provides SQL support in IntelliJ IDEA Ultimate. */
    public static boolean isActive() { return presence() == Presence.ULTIMATE; }

    public static @NotNull Presence presence() {
        Presence override = overriddenForTests;
        if (override != null) return override;
        return presenceOf(PluginManager.isPluginInstalled(ID) && !PluginManagerCore.isDisabled(ID), LicensingFacade.getInstance());
    }

    /** Pure rule, so it can be tested without an Ultimate IDE. The platform's own licensing keeps a stamp only for a license or a trial. */
    public static @NotNull Presence presenceOf(boolean pluginEnabled, @Nullable LicensingFacade licensing) {
        if (!pluginEnabled) return Presence.ABSENT;
        boolean licensed = licensing != null && licensing.platformProductCode != null
                && licensing.getConfirmationStamp(licensing.platformProductCode) != null;
        return licensed ? Presence.ULTIMATE : Presence.COMMUNITY;
    }
}
