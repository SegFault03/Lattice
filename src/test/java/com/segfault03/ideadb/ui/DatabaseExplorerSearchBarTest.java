package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseExplorerSearchBarTest {
    @Test void allIsExclusiveAndSpecificFiltersCanBeCombined() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<DatabaseExplorerSearch> query = new AtomicReference<>();
            DatabaseExplorerSearchBar bar = new DatabaseExplorerSearchBar(query::set, () -> {}, () -> {}, () -> {});
            JPanel popup = bar.createFilters();
            List<JCheckBox> choices = java.util.Arrays.stream(popup.getComponents()).filter(JCheckBox.class::isInstance)
                    .map(JCheckBox.class::cast).toList();
            assertTrue(choices.getFirst().isSelected());
            choices.get(2).doClick(0); // Schemas
            choices.get(3).doClick(0); // Tables
            assertFalse(choices.getFirst().isSelected());
            assertTrue(choices.get(2).isSelected());
            assertTrue(choices.get(3).isSelected());
            bar.setText("customer");
            assertEquals(2, query.get().metadataDepth());
            choices.getFirst().doClick(0);
            assertTrue(choices.getFirst().isSelected());
            assertTrue(choices.subList(1, choices.size()).stream().noneMatch(AbstractButton::isSelected));
            assertEquals(3, query.get().metadataDepth());
            choices.get(4).doClick(0);
            choices.get(4).doClick(0);
            assertTrue(choices.getFirst().isSelected(), "Clearing the last specific filter restores All");
        });
    }

    @Test void drawerUsesTheToolbarRowOnlyWhenThereIsRoomAndNeverClipsItsActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel toolbar = new JPanel();
            toolbar.setPreferredSize(new Dimension(280, 36));
            DatabaseExplorerSearchBar bar = new DatabaseExplorerSearchBar(query -> {}, () -> {}, () -> {}, () -> {});
            DatabaseExplorerHeader header = new DatabaseExplorerHeader(toolbar, bar);
            bar.setVisible(true);
            for (int width : new int[]{260, 600, 1000}) {
                header.setSize(width, 150);
                header.setSize(width, header.getPreferredSize().height);
                header.doLayout();
                bar.doLayout();
                if (width == 1000) assertEquals(toolbar.getY(), bar.getY());
                else assertEquals(toolbar.getHeight(), bar.getY());
                assertTrue(bar.getY() + bar.getHeight() <= header.getHeight());
                for (Component control : bar.getComponents()) {
                    assertTrue(control.getX() >= 0 && control.getX() + control.getWidth() <= bar.getWidth(), control.getClass().getName());
                    assertTrue(control.getY() >= 0 && control.getY() + control.getHeight() <= bar.getHeight());
                }
            }
        });
    }
}
