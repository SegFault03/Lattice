package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseEvent;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTableTest {
    @Test void selectedHeaderUsesBlueCellTreatmentWithoutChangingColumnSizing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var table = new DatabaseTable(new DefaultTableModel(new Object[]{"id", "name"}, 0));
            var renderer = table.getTableHeader().getDefaultRenderer();
            JLabel normal = (JLabel)renderer.getTableCellRendererComponent(table, "name", false, false, -1, 1);
            Color background = normal.getBackground(); Insets padding = normal.getInsets();
            int columnWidth = table.getColumnModel().getColumn(1).getWidth();
            table.setSelectedHeaderColumn(1);
            JLabel selected = (JLabel)renderer.getTableCellRendererComponent(table, "name", false, false, -1, 1);
            assertNotEquals(background, selected.getBackground()); assertEquals(padding, selected.getInsets());
            assertEquals(columnWidth, table.getColumnModel().getColumn(1).getWidth());
            table.setSelectedHeaderColumn(-1);
            JLabel cleared = (JLabel)renderer.getTableCellRendererComponent(table, "name", false, false, -1, 1);
            assertEquals(background, cleared.getBackground());
        });
    }
    @Test void headerResizeCursorDoesNotBecomeTheDefaultAfterUiRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var table = new DatabaseTable(new DefaultTableModel(new Object[]{"id", "name"}, 0));
            var header = table.getTableHeader();
            table.getColumnModel().getColumn(0).setWidth(100);
            table.getColumnModel().getColumn(1).setWidth(100);
            header.setSize(300, 32);
            for (int refresh = 0; refresh < 2; refresh++) {
                header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0, 0, 99, 16, 0, false));
                assertEquals(Cursor.E_RESIZE_CURSOR, header.getCursor().getType());
                header.updateUI();
                for (int x : new int[]{50, 150, 250}) {
                    header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_MOVED, 0, 0, x, 16, 0, false));
                    assertEquals(Cursor.DEFAULT_CURSOR, header.getCursor().getType(), "Only column boundaries should show resize");
                }
            }
        });
    }

    @Test void bodyAndHeaderHaveNoHoverPopupsEvenAfterSortingAndThemeRefresh() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DatabaseTable table = new DatabaseTable(new DefaultTableModel(new Object[][]{
                    {2, null}, {1, ""}, {3, "<html>literal"}
            }, new Object[]{"id", "value"}));
            table.setSize(300, 400);
            TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>((DefaultTableModel)table.getModel());
            table.setRowSorter(sorter);
            sorter.toggleSortOrder(0);
            table.moveColumn(1, 0);
            table.updateUI();
            assertTrue(com.intellij.ui.render.RenderingUtil.isHoverPaintingDisabled(table));
            assertTrue(com.intellij.ui.render.RenderingUtil.isHoverPaintingDisabled(table.getTableHeader()));
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 2; column++) {
                    Rectangle cell = table.getCellRect(row, column, true);
                    MouseEvent hover = new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0,
                            cell.x + 4, cell.y + 4, 0, false);
                    assertNull(table.getToolTipText(hover));
                    assertNull(table.getToolTipLocation(hover));
                    assertNull(table.getTableHeader().getToolTipText(hover));
                }
            }
        });
    }

}
