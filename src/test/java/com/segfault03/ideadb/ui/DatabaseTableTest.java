package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTableTest {
    private static DatabaseTable table() {
        DatabaseTable table = new DatabaseTable(new DefaultTableModel(new Object[][]{
                {2, null}, {1, ""}, {3, "<html><b>literal & text</b>"}
        }, new Object[]{"id", "value"}));
        table.setSize(300, 400);
        table.doLayout();
        return table;
    }

    private static MouseEvent hover(DatabaseTable table, int row, int column) {
        Rectangle cell = table.getCellRect(row, column, true);
        return new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0,
                cell.x + cell.width / 2, cell.y + cell.height / 2, 0, false);
    }

    @Test void nullEmptyAndMarkupValuesHaveDistinctReadableTooltips() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DatabaseTable table = table();
            assertTrue(table.getToolTipText(hover(table, 0, 1)).contains("NULL (no value)"));
            assertTrue(table.getToolTipText(hover(table, 1, 1)).contains("Empty string (0 characters)"));
            String literal = table.getToolTipText(hover(table, 2, 1));
            assertTrue(literal.contains("&lt;html&gt;&lt;b&gt;literal &amp; text&lt;/b&gt;"));
            assertFalse(literal.contains("<b>literal"));
            MouseEvent outside = new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, 10, 300, 0, false);
            assertNull(table.getToolTipText(outside));
            assertNull(table.getToolTipLocation(outside));
        });
    }

    @Test void tooltipProviderUsesModelIndicesAfterSortingAndColumnReordering() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DatabaseTable table = table();
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>((DefaultTableModel)table.getModel());
            table.setRowSorter(sorter);
            sorter.toggleSortOrder(0);
            table.moveColumn(1, 0);
            table.setCellTooltip((row, column) -> "model cell " + row + "," + column);
            assertTrue(table.getToolTipText(hover(table, 0, 0)).contains("model cell 1,1"));
            assertTrue(table.getToolTipText(hover(table, 1, 1)).contains("model cell 0,0"));
        });
    }

    @Test void tooltipAnchorMovesWithTheHoveredCellInsideAScrolledViewport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DatabaseTable table = table();
            JViewport viewport = new JViewport();
            viewport.setSize(100, 60);
            viewport.setView(table);
            viewport.setViewPosition(new Point(40, 20));
            for (int row = 0; row < 3; row++) {
                MouseEvent event = hover(table, row, 1);
                Rectangle cell = table.getCellRect(row, 1, true);
                Point anchor = table.getToolTipLocation(event);
                assertTrue(anchor.x >= cell.x && anchor.x < cell.x + cell.width);
                assertTrue(anchor.y >= cell.y + cell.height);
                assertTrue(anchor.y < cell.y + cell.height * 2);
            }
        });
    }
}
