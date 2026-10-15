package com.segfault03.ideadb.editor;

import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.LayeredIcon;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.ui.Icons;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import javax.swing.Icon;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTableIconProviderTest {
    @BeforeAll static void enableSvgIcons() {
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        com.intellij.openapi.util.IconLoader.activate();
    }

    @Test void tableTabsUseACachedSourceSpecificOverlayWithinTheStandardIconSize() {
        var provider = new DatabaseTableIconProvider();
        var icons = new HashSet<Icon>();
        for (DatabaseType type : DatabaseType.values()) {
            String name = type.name().toLowerCase(java.util.Locale.ROOT);
            assertNotNull(Icons.class.getResource("/icons/source-" + name + ".svg"), "Source artwork must not silently fall back to the generic database icon");
            String badge = type == DatabaseType.HSQLDB ? name : name + "-badge";
            assertNotNull(Icons.class.getResource("/icons/source-" + badge + ".svg"), "Table badges need solid source artwork");
            var config = new ConnectionConfig(type, "Source");
            for (String kind : List.of("TABLE", "VIEW")) {
                var file = new TableDataVirtualFile(config, "shop",
                        new TableMetadata("shop", "public", "customers", kind));
                Icon icon = provider.getIcon(file, 0, null);
                assertInstanceOf(LayeredIcon.class, icon);
                assertSame(Icons.tableWithDataSource(type), icon);
                assertSame(icon, provider.getIcon(file, 0, null));
                assertEquals(Icons.TABLE.getIconWidth(), icon.getIconWidth());
                assertEquals(Icons.TABLE.getIconHeight(), icon.getIconHeight());
                icons.add(icon);
            }
        }
        assertEquals(DatabaseType.values().length, icons.size(), "Each provider needs its own badge");
    }

    @Test void consoleWelcomeAndOrdinaryFilesKeepTheirExistingIcons() {
        var provider = new DatabaseTableIconProvider();
        var config = new ConnectionConfig(DatabaseType.MYSQL, "Source");
        assertNull(provider.getIcon(new SqlConsoleVirtualFile(config, "shop", List.of("shop")), 0, null));
        assertNull(provider.getIcon(new WelcomeVirtualFile(), 0, null));
        assertNull(provider.getIcon(new LightVirtualFile("notes.sql"), 0, null));
    }
}
