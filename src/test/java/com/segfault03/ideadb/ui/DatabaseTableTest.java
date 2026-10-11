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
