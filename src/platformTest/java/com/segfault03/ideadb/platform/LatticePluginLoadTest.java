package com.segfault03.ideadb.platform;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.segfault03.ideadb.service.MavenRepositoryLocator;

import java.nio.file.Path;

/** Runs without the Maven plugin loaded, which is what makes its dependency optional. */
public class LatticePluginLoadTest extends BasePlatformTestCase {
    public void testPluginLoadsWithoutMaven() {
        PluginId lattice = PluginId.getId("com.segfault03.lattice");
        assertTrue("Lattice must load in an IDE without the Maven plugin",
                PluginManagerCore.getLoadedPlugins().stream().anyMatch(plugin -> plugin.getPluginId().equals(lattice)));
        assertFalse("This fixture must not have Maven loaded",
                PluginManagerCore.getLoadedPlugins().stream().anyMatch(plugin -> plugin.getPluginId().getIdString().equals("org.jetbrains.idea.maven")));
    }

    public void testMavenRepositoryFallsBackToUserHome() {
        assertEquals(Path.of(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath(),
                MavenRepositoryLocator.localRepository(getProject()));
    }
}
