package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class EmptyConnectionPanelTest {
    @Test void databaseChoiceDispatchesAllRegisteredTypes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<com.segfault03.ideadb.model.DatabaseType> choices = new java.util.ArrayList<>();
            JPopupMenu menu = DatabaseUi.connectionMenu(choices::add);
            assertEquals(com.segfault03.ideadb.model.DatabaseType.values().length, menu.getComponentCount());
            for (Component item : menu.getComponents()) {
                JMenuItem entry = (JMenuItem)item;
                assertNotNull(entry.getIcon(), "Each add-connection option needs its source SVG");
                assertEquals(16, entry.getIcon().getIconWidth());
                entry.doClick(0);
            }
            assertEquals(java.util.List.of(com.segfault03.ideadb.model.DatabaseType.values()), choices);
        });
    }
}
