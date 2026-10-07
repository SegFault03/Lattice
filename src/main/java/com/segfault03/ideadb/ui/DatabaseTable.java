package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.function.BiFunction;

/** Shared database grid styling and a single tooltip anchored to the hovered cell. */
public final class DatabaseTable extends JBTable {
    private BiFunction<Integer, Integer, String> cellTooltip;
    private final Color headerBackground = JBColor.namedColor("Lattice.TableHeader.background", new JBColor(0xE9EEF5, 0x383E48));

    public DatabaseTable(TableModel model) {
        super(model);
        setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        setRowHeight(JBUI.scale(28));
        setShowGrid(true);
        setGridColor(JBColor.namedColor("Table.gridColor", new JBColor(0xE8E9ED, 0x393B40)));
        // Clipped-cell expansion is another floating popup; cell tooltips supply
        // the full value and context without competing with it.
        setExpandableItemsEnabled(false);
        getTableHeader().setReorderingAllowed(false);
        getTableHeader().setBackground(headerBackground);
        getTableHeader().setPreferredSize(new Dimension(getTableHeader().getPreferredSize().width, JBUI.scale(32)));
        getTableHeader().setDefaultRenderer(new HeaderRenderer());
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    public JBScrollPane createScrollPane() {
        JBScrollPane scroll = new JBScrollPane(this);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setColumnHeaderView(getTableHeader());
        scroll.getColumnHeader().setBackground(headerBackground);
        scroll.getViewport().setBackground(getBackground());
        return scroll;
    }

    /** The provider receives model indices, including after sorting or reordering. */
    public void setCellTooltip(BiFunction<Integer, Integer, String> provider) {
        cellTooltip = provider;
    }

    @Override public String getToolTipText(MouseEvent event) {
        if (event == null) return null;
        int row = rowAtPoint(event.getPoint());
        int column = columnAtPoint(event.getPoint());
        if (row < 0 || column < 0) return null;
        int modelRow = convertRowIndexToModel(row);
        int modelColumn = convertColumnIndexToModel(column);
        String text = cellTooltip == null
                ? getModel().getColumnName(modelColumn) + ": " + describeValue(getModel().getValueAt(modelRow, modelColumn))
                : cellTooltip.apply(modelRow, modelColumn);
        if (text == null || text.isBlank()) return null;
        // Values are database text, never tooltip markup (including literal <html>).
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("\n", "<br>");
        return text.length() > 70 || text.contains("\n")
                ? "<html><body style='width: 340px'>" + escaped + "</body></html>"
                : "<html>" + escaped + "</html>";
    }

    @Override public Point getToolTipLocation(MouseEvent event) {
        if (event == null) return null;
        int row = rowAtPoint(event.getPoint());
        int column = columnAtPoint(event.getPoint());
        if (row < 0 || column < 0) return null;
        Rectangle cell = getCellRect(row, column, true);
        int x = Math.min(event.getX() + JBUI.scale(8), cell.x + cell.width - 1);
        return new Point(x, cell.y + cell.height + JBUI.scale(2));
    }

    public static String describeValue(Object value) {
        if (value == null) return "NULL (no value)";
        if (value instanceof String text && text.isEmpty()) return "Empty string (0 characters)";
        if (value instanceof byte[] bytes) return "Binary data (" + bytes.length + " bytes)";
        String text = value.toString();
        return text.length() > 1000 ? text.substring(0, 1000) + "… (truncated)" : text;
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
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
