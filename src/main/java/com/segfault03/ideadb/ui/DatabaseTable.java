package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseEvent;

/** Shared database grids with a persistent header band and no hover surfaces. */
public final class DatabaseTable extends JBTable {
    public DatabaseTable(TableModel model) {
        super(model);
        setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        setRowHeight(JBUI.scale(28));
        setShowGrid(true);
        DatabaseInputs.styleTableEditors(this);
        setGridColor(JBColor.namedColor("Table.gridColor", new JBColor(0xE8E9ED, 0x393B40)));
        setExpandableItemsEnabled(false);
        putClientProperty(com.intellij.ui.render.RenderingUtil.PAINT_HOVERED_BACKGROUND, Boolean.FALSE);
        ((JBTableHeader)getTableHeader()).setExpandableItemsEnabled(false);
        getTableHeader().putClientProperty(com.intellij.ui.render.RenderingUtil.PAINT_HOVERED_BACKGROUND, Boolean.FALSE);
        getTableHeader().setReorderingAllowed(false);
        getTableHeader().setDefaultRenderer(new HeaderRenderer());
        ToolTipManager.sharedInstance().unregisterComponent(this);
        ToolTipManager.sharedInstance().unregisterComponent(getTableHeader());
    }

    @Override protected JTableHeader createDefaultTableHeader() {
        return new JBTableHeader() {
            // Native UI refreshes may replace a header background set only at construction.
            @Override public Color getBackground() { return headerBackground(); }
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.height = Math.max(JBUI.scale(32), getFontMetrics(getFont()).getHeight() + JBUI.scale(10));
                return size;
            }
            @Override public String getToolTipText(MouseEvent event) { return null; }
            @Override public Point getToolTipLocation(MouseEvent event) { return null; }
            @Override public void updateUI() {
                super.updateUI();
                if (getParent() instanceof JViewport viewport) viewport.setBackground(getBackground());
            }
        };
    }

    private Color headerBackground() {
        Color rows = getBackground();
        if (rows == null) rows = Color.WHITE;
        boolean light = rows.getRed() + rows.getGreen() + rows.getBlue() > 384;
        Color accent = light ? new Color(86, 111, 148) : new Color(150, 169, 198);
        double blend = light ? 0.20 : 0.24;
        return new Color((int)(rows.getRed() * (1 - blend) + accent.getRed() * blend),
                (int)(rows.getGreen() * (1 - blend) + accent.getGreen() * blend),
                (int)(rows.getBlue() * (1 - blend) + accent.getBlue() * blend));
    }

    public JBScrollPane createScrollPane() {
        JBScrollPane scroll = new JBScrollPane(this);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setColumnHeaderView(getTableHeader());
        scroll.getColumnHeader().setBackground(getTableHeader().getBackground());
        scroll.getViewport().setBackground(getBackground());
        return scroll;
    }

    /** Fit real header/value metrics, with a cap for long text and wide result sets. */
    public void sizeColumnsToContent() {
        for (int column = 0; column < getColumnCount(); column++) {
            Component header = getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
                    this, getColumnModel().getColumn(column).getHeaderValue(), false, false, -1, column);
            int width = header.getPreferredSize().width;
            for (int row = 0; row < Math.min(getRowCount(), 50); row++)
                width = Math.max(width, prepareRenderer(getCellRenderer(row, column), row, column).getPreferredSize().width);
            getColumnModel().getColumn(column).setPreferredWidth(Math.max(JBUI.scale(72), Math.min(width + JBUI.scale(8), JBUI.scale(320))));
        }
    }

    @Override public String getToolTipText(MouseEvent event) { return null; }
    @Override public Point getToolTipLocation(MouseEvent event) { return null; }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override public boolean isOpaque() { return true; }
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                 boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setBackground(table.getTableHeader().getBackground());
            setForeground(table.getForeground());
            setFont(table.getFont().deriveFont(Font.BOLD));
            setHorizontalAlignment(SwingConstants.LEFT);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, JBUI.scale(2), JBUI.scale(1), table.getGridColor()),
                    JBUI.Borders.empty(4, 8)));
            return this;
        }
    }
}
