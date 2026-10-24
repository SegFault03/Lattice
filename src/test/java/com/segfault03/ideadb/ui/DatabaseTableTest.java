package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTableTest {
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

    @Test void primaryKeyHeadersHaveDistinctPaintedColorsAndFollowModelColumnIndices() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new com.segfault03.ideadb.model.ReadOnlyResultModel();
            model.setResult(com.segfault03.ideadb.model.QueryResult.forResultSet(
                    java.util.List.of("tenant", "id", "name"), java.util.List.of("INT", "INT", "VARCHAR"),
                    java.util.List.of(), 0).withPrimaryKeyColumns(java.util.Set.of(0, 1)));
            DatabaseTable table = new DatabaseTable(model);
            for (Color surface : new Color[]{Color.WHITE, new Color(0x2B2D30)}) {
                table.setBackground(surface);
                table.setForeground(surface.equals(Color.WHITE) ? Color.BLACK : Color.WHITE);
                var header = table.getTableHeader();
                header.setUI(new com.intellij.ide.ui.laf.darcula.DarculaTableHeaderUI());
                for (var column : java.util.Collections.list(table.getColumnModel().getColumns())) column.setWidth(100);
                header.setSize(300, header.getPreferredSize().height);
                BufferedImage image = new BufferedImage(300, header.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics graphics = image.createGraphics();
                graphics.setClip(0, 0, 300, header.getHeight());
                header.paint(graphics);
                graphics.dispose();
                assertEquals(image.getRGB(5, 3), image.getRGB(105, 3), "Both composite-key headers use the accent");
                assertNotEquals(image.getRGB(5, 3), image.getRGB(205, 3), "Keys differ visibly from ordinary headers");
                var renderer = header.getDefaultRenderer();
                JLabel label = (JLabel) renderer.getTableCellRendererComponent(table, "name", false, false, -1, 2);
                assertNull(label.getIcon(), "A recycled renderer must clear the key icon");
                assertEquals(header.getBackground(), label.getBackground());
            }
            table.getColumnModel().moveColumn(2, 0);
            JLabel label = (JLabel) table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table, "name", false, false, -1, 0);
            assertNull(label.getIcon(), "Highlighting follows model indices when columns move");
        });
    }

    @Test void foreignKeyHeadersHaveDistinctColorsAndDualKeysKeepBothBadges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new com.segfault03.ideadb.model.ReadOnlyResultModel();
            model.setResult(com.segfault03.ideadb.model.QueryResult.forResultSet(
                    java.util.List.of("id", "tenant", "parent_tenant", "parent_id", "name"),
                    java.util.List.of("INT", "INT", "INT", "INT", "VARCHAR"), java.util.List.of(), 0)
                    .withPrimaryKeyColumns(java.util.Set.of(0, 1)).withForeignKeyColumns(java.util.Set.of(1, 2, 3)));
            DatabaseTable table = new DatabaseTable(model);
            for (Color surface : new Color[]{Color.WHITE, new Color(0x2B2D30)}) {
                table.setBackground(surface);
                table.setForeground(surface.equals(Color.WHITE) ? Color.BLACK : Color.WHITE);
                var header = table.getTableHeader();
                header.setUI(new com.intellij.ide.ui.laf.darcula.DarculaTableHeaderUI());
                for (var column : java.util.Collections.list(table.getColumnModel().getColumns())) column.setWidth(100);
                header.setSize(500, header.getPreferredSize().height);
                BufferedImage image = new BufferedImage(500, header.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics graphics = image.createGraphics();
                graphics.setClip(0, 0, 500, header.getHeight());
                header.paint(graphics);
                graphics.dispose();
                assertEquals(image.getRGB(5, 3), image.getRGB(105, 3), "Dual keys retain the gold primary-key tint");
                assertEquals(image.getRGB(205, 3), image.getRGB(305, 3), "Every composite foreign-key column has the tint");
                assertNotEquals(image.getRGB(5, 3), image.getRGB(205, 3), "Foreign and primary keys differ");
                assertNotEquals(image.getRGB(205, 3), image.getRGB(405, 3), "Foreign keys differ from ordinary columns");
                var renderer = header.getDefaultRenderer();
                JLabel dual = (JLabel) renderer.getTableCellRendererComponent(table, "tenant", false, false, -1, 1);
                assertEquals("Primary and foreign key column", dual.getAccessibleContext().getAccessibleDescription());
                assertTrue(dual.getIcon().getIconWidth() > Icons.KEY.getIconWidth(), "Both role badges are present");
                JLabel ordinary = (JLabel) renderer.getTableCellRendererComponent(table, "name", false, false, -1, 4);
                assertNull(ordinary.getIcon());
                assertNull(ordinary.getAccessibleContext().getAccessibleDescription());
            }
            table.getColumnModel().moveColumn(2, 0);
            JLabel moved = (JLabel) table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table, "parent_tenant", false, false, -1, 0);
            assertEquals("Foreign key column", moved.getAccessibleContext().getAccessibleDescription());
        });
    }

    @Test void columnWidthsUseRenderedContentAndCapLongValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DatabaseTable table = new DatabaseTable(new DefaultTableModel(
                    new Object[][]{{1499, "recording_".repeat(80)}}, new Object[]{"ID [PK, Auto]", "ORIGINAL_FILE_NAME"}));
            table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            table.sizeColumnsToContent();
            int identity = table.getColumnModel().getColumn(0).getPreferredWidth();
            assertTrue(identity >= 72 && identity < 140);
            assertEquals(320, table.getColumnModel().getColumn(1).getPreferredWidth());
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

    @Test void nativeHeaderActuallyPaintsAContrastingBandAfterRefreshAndModelChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[][]{{1}}, new Object[]{"ID [PK, Auto]"});
            DatabaseTable table = new DatabaseTable(model);
            for (Color background : new Color[]{Color.WHITE, new Color(43, 45, 48)}) {
                table.setBackground(background);
                table.updateUI();
                model.setColumnIdentifiers(new Object[]{"ID [PK, Auto]"});
                var header = table.getTableHeader();
                header.setUI(new com.intellij.ide.ui.laf.darcula.DarculaTableHeaderUI());
                header.setSize(300, header.getPreferredSize().height);
                BufferedImage painted = new BufferedImage(300, header.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics graphics = painted.createGraphics();
                graphics.setClip(0, 0, 300, header.getHeight());
                header.paint(graphics);
                graphics.dispose();
                Color band = new Color(painted.getRGB(10, 3));
                int contrast = Math.abs(band.getRed() - background.getRed())
                        + Math.abs(band.getGreen() - background.getGreen()) + Math.abs(band.getBlue() - background.getBlue());
                assertTrue(contrast >= 60, "Painted native header must visibly differ from data rows");
                assertEquals(header.getBackground().getRGB(), band.getRGB());
                assertEquals(background.getRGB(), painted.getRGB(250, 3),
                        "The header band must stop at the last column even when its component is wider");
            }
        });
    }

    @Test void headerBlankSpaceMatchesRowsAfterResizeScrollAndModelChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[][]{{1, "value"}}, new Object[]{"id", "value"});
            DatabaseTable table = new DatabaseTable(model);
            JScrollPane scroll = table.createScrollPane();
            for (Color background : new Color[]{Color.WHITE, new Color(43, 45, 48)}) {
                table.setBackground(background);
                table.updateUI();
                scroll.getViewport().setBackground(background);
                for (String[] columns : new String[][]{{"id", "value"}, {"id"}, {}}) {
                    model.setColumnIdentifiers(columns);
                    for (var column : java.util.Collections.list(table.getColumnModel().getColumns())) {
                        column.setPreferredWidth(100);
                        column.setWidth(100);
                    }
                    for (int width : new int[]{600, 120, 600}) {
                        scroll.setSize(width, 200);
                        scroll.doLayout();
                        scroll.getViewport().doLayout();
                        JViewport header = scroll.getColumnHeader();
                        header.doLayout();
                        int total = table.getColumnModel().getTotalColumnWidth();
                        if (total > header.getWidth()) {
                            scroll.getViewport().setViewPosition(new Point(30, 0));
                            assertEquals(scroll.getViewport().getViewPosition().x, header.getViewPosition().x,
                                    "Header and body must scroll together");
                        } else {
                            BufferedImage image = new BufferedImage(header.getWidth(), header.getHeight(), BufferedImage.TYPE_INT_RGB);
                            Graphics graphics = image.createGraphics();
                            try { header.paint(graphics); } finally { graphics.dispose(); }
                            assertEquals(background.getRGB(), image.getRGB(header.getWidth() - 5, 3),
                                    "Unused header space must have the row surface color");
                            if (total > 0) assertEquals(table.getTableHeader().getBackground().getRGB(), image.getRGB(5, 3));
                        }
                    }
                }
            }
        });
    }
}
