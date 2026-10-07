import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.intellij.ui.JBColor;
import com.intellij.openapi.util.IconLoader;
import com.segfault03.ideadb.dialog.ConnectionDialog;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.ui.TableDataEditorPanel;
import com.segfault03.ideadb.ui.ExplorerPreview;
import com.segfault03.ideadb.ui.SqlQueryConsolePanel;
import com.segfault03.ideadb.ui.DatabaseTable;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Paint production Swing components with fixture data, without starting IntelliJ. */
public final class UiPreview {
    static Path output;
    static final List<String> snapshots = new ArrayList<>();
    static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    static void invoke(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, values);
    }
    static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents())
            if (component instanceof Container nested) layout(nested);
    }
    static void render(JComponent component, String name, int width, int height) throws Exception {
        component.setSize(width, height);
        for (int pass = 0; pass < 4; pass++) layout(component);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
        snapshots.add(name + ".png");
        System.out.println(name + ": " + width + " x " + height);
    }
    static ConnectionConfig config(DatabaseType type) {
        ConnectionConfig config = new ConnectionConfig(type, type == DatabaseType.MYSQL ? "Local development" : "Scratch database");
        config.setHost("localhost");
        config.setPort(type == DatabaseType.MYSQL ? 3306 : 9001);
        config.setDatabaseName("shop");
        config.setUser(type == DatabaseType.MYSQL ? "developer" : "SA");
        return config;
    }
    static void connection(String theme, String variant, int width) throws Exception {
        ConnectionConfig config = config(variant.startsWith("hsql") ? DatabaseType.HSQLDB : DatabaseType.MYSQL);
        if (variant.equals("hsql-memory")) { config.setHsqlMode(HsqlMode.MEM); config.setDatabaseName("scratch"); }
        if (variant.equals("hsql-file")) { config.setHsqlMode(HsqlMode.FILE); config.setDatabaseName("/home/developer/data/shop"); }
        if (variant.equals("jdbc-url")) config.setCustomUrl("jdbc:mysql://localhost:3306/shop?useSSL=true");
        if (variant.equals("driver-download")) config.setDriverSource(DriverSource.DOWNLOAD);
        if (variant.equals("driver-local")) { config.setDriverSource(DriverSource.LOCAL_JAR); config.setDriverJarPath("/home/developer/drivers/mysql-connector-j.jar"); }
        ConnectionDialog dialog = new ConnectionDialog(null, config);
        JPanel panel = dialog.previewPanel();
        render(panel, theme + "-connection-" + variant + "-" + width, width, panel.getPreferredSize().height);
        if (variant.equals("driver-download")) {
            render(panel, theme + "-connection-driver-download-expanded-" + width, width, panel.getPreferredSize().height + 200);
            JScrollPane scroll = (JScrollPane)((JPanel)field(dialog, "rootPanel")).getParent().getParent();
            panel.setSize(width, panel.getPreferredSize().height);
            for (int pass = 0; pass < 4; pass++) layout(panel);
            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
            render(panel, theme + "-connection-driver-download-details-" + width, width, panel.getPreferredSize().height);
        }
        if (variant.equals("hsql-memory") || variant.equals("hsql-file")) {
            JPanel root = (JPanel)field(dialog, "rootPanel");
            JComponent input = (JComponent)field(dialog, variant.equals("hsql-memory") ? "hsqlMemNameField" : "hsqlFileField");
            JComponent user = (JComponent)field(dialog, "hsqlUserField");
            int bottom = SwingUtilities.convertPoint(input, 0, input.getHeight(), root).y;
            int userTop = SwingUtilities.convertPoint(user, 0, 0, root).y;
            if (userTop - bottom > 70) throw new AssertionError("Empty HSQL server rows before credentials");
        }
        if (!((JLabel)field(dialog, "urlPreviewLabel")).getText().equals(config.buildJdbcUrl()))
            throw new AssertionError("Resolved URL must retain the full value");
        // Editing settings here is safe: dialog network/test actions are never invoked.
        if (!dialog.getResultConfig().buildJdbcUrl().equals(config.buildJdbcUrl()))
            throw new AssertionError("Preview changed the configured JDBC URL for " + variant);
    }
    static TableDataEditorPanel table(boolean view) throws Exception {
        TableMetadata meta = new TableMetadata("shop", null, view ? "active_customers" : "customers", view ? "VIEW" : "TABLE");
        meta.addColumn(new ColumnMetadata("id", "BIGINT", java.sql.Types.BIGINT, 0, 0, false, true, true, null));
        meta.addColumn(new ColumnMetadata("name", "VARCHAR", java.sql.Types.VARCHAR, 120, 0, false, false, false, null));
        meta.addColumn(new ColumnMetadata("email", "VARCHAR", java.sql.Types.VARCHAR, 180, 0, true, false, false, null));
        meta.addColumn(new ColumnMetadata("status", "VARCHAR", java.sql.Types.VARCHAR, 20, 0, false, false, false, "'active'"));
        meta.addColumn(new ColumnMetadata("balance", "DECIMAL", java.sql.Types.DECIMAL, 10, 2, false, false, false, "0"));
        TableDataEditorPanel panel = new TableDataEditorPanel(null, config(DatabaseType.MYSQL), "shop", meta);
        Object model = field(panel, "tableModel");
        List<List<Object>> rows = new ArrayList<>();
        String[] names = {"Amelia Brooks", "Liam Chen", "Sofia Patel", "Noah Williams", "Olivia Reyes", "Ethan Kim", "Isabella Rossi", "Lucas Martin", "Mia Thompson", "Aiden Davis", "Charlotte Wilson", "Leo Garcia"};
        for (int i = 0; i < names.length; i++) rows.add(new ArrayList<>(Arrays.asList(1001L + i, names[i], i == 3 ? null : names[i].split(" ")[0].toLowerCase(Locale.ROOT) + "@example.com", i == 4 ? "inactive" : "active", new java.math.BigDecimal(i % 3 == 0 ? "240.50" : "0.00"))));
        invoke(model, "setData", new Class<?>[]{List.class, List.class, List.class}, List.of("id", "name", "email", "status", "balance"), List.of("BIGINT", "VARCHAR", "VARCHAR", "VARCHAR", "DECIMAL"), rows);
        JTable table = (JTable) field(panel, "dataTable");
        ((JScrollPane)table.getParent().getParent()).setColumnHeaderView(table.getTableHeader());
        int[] widths = {110, 190, 280, 150, 150};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        ((JButton) field(panel, "nextPageBtn")).setEnabled(false);
        ((JLabel) field(panel, "statusLabel")).setText("Loaded 12 row(s) in 18 ms | Total rows: not counted");
        return panel;
    }
    static SqlQueryConsolePanel console() throws Exception {
        SqlQueryConsolePanel panel = new SqlQueryConsolePanel(null, config(DatabaseType.MYSQL), "shop", List.of("shop", "analytics"));
        panel.setSqlText("-- Active customers with an outstanding balance\nSELECT id, name, email, balance\nFROM customers\nWHERE status = 'active' AND balance > 0\nORDER BY balance DESC;\n");
        DefaultTableModel model = (DefaultTableModel)field(panel, "resultsModel");
        model.setDataVector(new Object[][]{
                {1001L, "Amelia Brooks", "amelia@example.com", new java.math.BigDecimal("240.50")},
                {1004L, "Noah Williams", null, new java.math.BigDecimal("240.50")},
                {1007L, "Isabella Rossi", "isabella@example.com", new java.math.BigDecimal("240.50")},
                {1010L, "Aiden Davis", "aiden@example.com", new java.math.BigDecimal("240.50")}
        }, new Object[]{"id", "name", "email", "balance"});
        JTable table = (JTable)field(panel, "resultsTable");
        ((JScrollPane)table.getParent().getParent()).setColumnHeaderView(table.getTableHeader());
        int[] widths = {110, 220, 320, 150};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        ((JLabel)field(panel, "statusLabel")).setText("4 row(s) returned in 18 ms");
        ((JTabbedPane)field(panel, "resultsTabs")).setTitleAt(0, "Results (4)");
        ((JTextArea)field(panel, "messagesArea")).setText("Query completed successfully.\n4 rows returned in 18 ms.\n");
        return panel;
    }
    static void verifyControls(Container container) {
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) continue;
            if (child.getX() < 0 || child.getY() < 0 || child.getX() + child.getWidth() > container.getWidth()
                    || child.getY() + child.getHeight() > container.getHeight())
                throw new AssertionError("Clipped toolbar/footer control: " + child.getClass().getSimpleName());
            if (child instanceof Container nested) verifyControls(nested);
        }
    }
    static java.awt.event.MouseEvent hover(JTable table, int row, int column) {
        Rectangle cell = table.getCellRect(row, column, true);
        return new java.awt.event.MouseEvent(table, java.awt.event.MouseEvent.MOUSE_MOVED, 0, 0,
                cell.x + cell.width / 2, cell.y + cell.height / 2, 0, false);
    }
    static void verifyGrid(DatabaseTable table, int nullRow, int nullColumn) {
        Component header = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
                table, table.getColumnName(0), false, false, -1, 0);
        Color color = header.getBackground();
        Color background = table.getBackground();
        int difference = Math.abs(color.getRed() - background.getRed())
                + Math.abs(color.getGreen() - background.getGreen()) + Math.abs(color.getBlue() - background.getBlue());
        if (difference < 20) throw new AssertionError("Header background must differ from data rows");
        String tip = table.getToolTipText(hover(table, nullRow, nullColumn));
        if (!tip.contains("NULL (no value)")) throw new AssertionError("Missing readable NULL tooltip: " + tip);
    }
    static void tooltipSnapshot(JComponent panel, DatabaseTable table, int row, int column, String name) throws Exception {
        JLayeredPane layers = new JLayeredPane();
        layers.add(panel, JLayeredPane.DEFAULT_LAYER);
        panel.setBounds(0, 0, 1100, 620);
        for (int pass = 0; pass < 4; pass++) layout(panel);
        var event = hover(table, row, column);
        JToolTip tooltip = table.createToolTip();
        tooltip.setTipText(table.getToolTipText(event));
        Point anchor = SwingUtilities.convertPoint(table, table.getToolTipLocation(event), layers);
        Dimension size = tooltip.getPreferredSize();
        tooltip.setBounds(anchor.x, anchor.y, size.width, size.height);
        layers.add(tooltip, JLayeredPane.POPUP_LAYER);
        render(layers, name, 1100, 620);
        layers.remove(panel);
    }
    static JComponent findByTooltip(Container container, String tooltip) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComponent component && tooltip.equals(component.getToolTipText())) return component;
            if (child instanceof Container nested) {
                JComponent match = findByTooltip(nested, tooltip);
                if (match != null) return match;
            }
        }
        return null;
    }
    static void verifyConsoleActions() throws Exception {
        SqlQueryConsolePanel panel = console();
        JTextArea editor = (JTextArea)field(panel, "editorArea");
        panel.setSqlText("");
        JComboBox<?> template = (JComboBox<?>)findByTooltip(panel, "Insert a SQL template at the caret");
        template.setSelectedIndex(1);
        template.setSelectedIndex(1);
        if (editor.getText().split("SELECT", -1).length != 3) throw new AssertionError("Template must support repeated insertion");
        @SuppressWarnings("unchecked") JComboBox<QueryHistoryEntry> history = (JComboBox<QueryHistoryEntry>)field(panel, "historyCombo");
        history.addItem(new QueryHistoryEntry("SELECT 42;"));
        history.setSelectedIndex(1);
        if (!editor.getText().equals("SELECT 42;")) throw new AssertionError("History must restore the full query");
        ((JButton)findByTooltip(panel, "Clear query text")).doClick(0);
        if (!editor.getText().isEmpty()) throw new AssertionError("Clear action must clear the query");
        panel.setSqlText("SELECT 1; SELECT 2;");
        editor.select(10, 19);
        editor.getActionMap().get("runSql").actionPerformed(new java.awt.event.ActionEvent(editor, 0, "runSql"));
        if (((JButton)field(panel, "runBtn")).isEnabled() || !((JButton)field(panel, "cancelBtn")).isEnabled()
                || ((JComboBox<?>)field(panel, "databaseCombo")).isEnabled() || ((JComboBox<?>)field(panel, "resultLimit")).isEnabled())
            throw new AssertionError("Executing controls must reflect the active query");
        @SuppressWarnings("unchecked") List<String> queries = (List<String>)field(panel, "queryHistory");
        if (!queries.get(0).equals("SELECT 2;")) throw new AssertionError("Run shortcut must execute the selection");
        invoke(panel, "setRunning", new Class<?>[]{boolean.class}, false);
        if (!((JButton)field(panel, "runBtn")).isEnabled() || ((JButton)field(panel, "cancelBtn")).isEnabled())
            throw new AssertionError("Completed controls must restore Run/Stop state");
        System.out.println("Console templates, history, clear, shortcut and execution controls verified (background tasks blocked)");
    }
    static void prepareMenu(JTable table, int row, int column) {
        table.setRowSelectionInterval(row, row);
        table.setColumnSelectionInterval(column, column);
        JPopupMenu menu = table.getComponentPopupMenu();
        for (var listener : menu.getPopupMenuListeners())
            listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
    }
    static void verifyCellActions() throws Exception {
        TableDataEditorPanel panel = table(false);
        JTable table = (JTable)field(panel, "dataTable");
        Object model = field(panel, "tableModel");
        JPopupMenu menu = table.getComponentPopupMenu();
        JMenuItem nullAction = (JMenuItem)menu.getComponent(0);
        JMenuItem defaultAction = (JMenuItem)menu.getComponent(1);
        prepareMenu(table, 0, 0);
        if (nullAction.isEnabled() || defaultAction.isEnabled()) throw new AssertionError("Identity cell must be read-only");
        prepareMenu(table, 0, 2);
        if (!nullAction.isEnabled() || defaultAction.isEnabled()) throw new AssertionError("Nullable persisted cell actions");
        nullAction.doClick(0);
        if (table.getValueAt(0, 2) != null) throw new AssertionError("Set NULL action did not update the cell");
        invoke(model, "addNewRow", new Class<?>[]{});
        ((TableModel)model).setValueAt("inactive", 12, 3);
        prepareMenu(table, 12, 3);
        if (!defaultAction.isEnabled()) throw new AssertionError("New row default action must be available");
        defaultAction.doClick(0);
        if (table.getValueAt(12, 3) != RowDefaults.Value.USE_DEFAULT) throw new AssertionError("Use Default action did not restore the marker");
        var renderer = table.getDefaultRenderer(Object.class);
        JComponent modified = (JComponent)renderer.getTableCellRendererComponent(table, table.getValueAt(0, 2), false, false, 0, 2);
        if (modified.getBorder().getBorderInsets(modified).left <= 0) throw new AssertionError("Modified-cell cue missing");
        JComponent normal = (JComponent)renderer.getTableCellRendererComponent(table, table.getValueAt(1, 1), false, false, 1, 1);
        if (normal.getBorder().getBorderInsets(normal).top != 0) throw new AssertionError("Modified/error border leaked into a normal cell");
        System.out.println("Cell menu actions and renderer state verified");
    }
    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        Files.createDirectories(output);
        String theme = args[1];
        UIManager.setLookAndFeel(theme.equals("dark") ? new FlatDarculaLaf() : new FlatIntelliJLaf());
        UIManager.put("defaultFont", new Font("SansSerif", Font.PLAIN, 13));
        JBColor.setDark(theme.equals("dark"));
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        IconLoader.activate();
        IconLoader.setUseDarkIcons(theme.equals("dark"));
        SwingUtilities.invokeAndWait(() -> {
            try {
                render(ExplorerPreview.create(config(DatabaseType.MYSQL), false), theme + "-side-panel-340", 340, 620);
                render(ExplorerPreview.create(config(DatabaseType.MYSQL), true), theme + "-side-panel-empty-340", 340, 620);
                SqlQueryConsolePanel console = console();
                render(console, theme + "-sql-console-1100", 1100, 620);
                render(console, theme + "-sql-console-760", 760, 620);
                render(console, theme + "-sql-console-520", 520, 620);
                BorderLayout consoleLayout = (BorderLayout)console.getLayout();
                verifyControls((Container)consoleLayout.getLayoutComponent(BorderLayout.NORTH));
                verifyControls((Container)consoleLayout.getLayoutComponent(BorderLayout.SOUTH));
                DatabaseTable results = (DatabaseTable)field(console, "resultsTable");
                verifyGrid(results, 1, 2);
                tooltipSnapshot(console, results, 1, 2, theme + "-sql-console-null-tooltip-1100");
                invoke(console, "setRunning", new Class<?>[]{boolean.class}, true);
                ((JLabel)field(console, "statusLabel")).setText("Executing query…");
                render(console, theme + "-sql-console-running-1100", 1100, 620);
                invoke(console, "setRunning", new Class<?>[]{boolean.class}, false);
                JTextArea consoleEditor = (JTextArea)field(console, "editorArea");
                console.setSqlText(consoleEditor.getText().replace("WHERE status", "WHERE customer_status"));
                ((JTextArea)field(console, "messagesArea")).setText("ERROR: Unknown column 'customer_status' in WHERE clause\nElapsed: 8 ms\n\nCheck the column name and run the query again.");
                ((JTextArea)field(console, "messagesArea")).setForeground(new JBColor(0xC62828, 0xFF8282));
                ((JTabbedPane)field(console, "resultsTabs")).setSelectedIndex(1);
                ((JLabel)field(console, "statusLabel")).setText("Query failed: unknown column 'customer_status'");
                render(console, theme + "-sql-console-messages-1100", 1100, 620);
                SqlQueryConsolePanel emptyConsole = new SqlQueryConsolePanel(null, config(DatabaseType.MYSQL), "shop", List.of("shop"));
                emptyConsole.setSqlText("");
                render(emptyConsole, theme + "-sql-console-empty-1100", 1100, 620);
                verifyConsoleActions();
                for (String variant : List.of("mysql", "hsql-server", "hsql-memory", "hsql-file", "jdbc-url", "driver-download", "driver-local")) connection(theme, variant, 600);
                connection(theme, "mysql", 800);
                TableDataEditorPanel editor = table(false);
                render(editor, theme + "-table-1100", 1100, 620);
                DatabaseTable grid = (DatabaseTable)field(editor, "dataTable");
                verifyGrid(grid, 3, 2);
                tooltipSnapshot(editor, grid, 3, 2, theme + "-table-null-tooltip-1100");
                render(editor, theme + "-table-760", 760, 620);
                render(editor, theme + "-table-520", 520, 620);
                BorderLayout editorLayout = (BorderLayout)editor.getLayout();
                verifyControls((Container)editorLayout.getLayoutComponent(BorderLayout.NORTH));
                verifyControls((Container)editorLayout.getLayoutComponent(BorderLayout.SOUTH));
                Object model = field(editor, "tableModel");
                ((TableModel)model).setValueAt("Amelia Stone", 0, 1);
                if (!((JButton)field(editor, "saveBtn")).isEnabled()) throw new AssertionError("Commit must be enabled for a valid edit");
                render(editor, theme + "-table-edited-1100", 1100, 620);
                if (!grid.getToolTipText(hover(grid, 0, 1)).contains("Original: Amelia Brooks"))
                    throw new AssertionError("Modified cell tooltip must retain its original value");
                tooltipSnapshot(editor, grid, 0, 1, theme + "-table-modified-tooltip-1100");
                invoke(model, "addNewRow", new Class<?>[]{});
                ((TableModel)model).setValueAt("Grace Lee", 12, 1);
                ((TableModel)model).setValueAt("grace@example.com", 12, 2);
                invoke(editor, "updatePendingChangesState", new Class<?>[]{});
                render(editor, theme + "-table-new-row-1100", 1100, 620);
                ((TableModel)model).setValueAt("not a number", 1, 4);
                if (((JButton)field(editor, "saveBtn")).isEnabled()) throw new AssertionError("Commit must be disabled for an invalid decimal");
                if (!grid.getToolTipText(hover(grid, 1, 4)).contains("Validation error:"))
                    throw new AssertionError("Invalid cell tooltip must explain the validation error");
                render(editor, theme + "-table-error-1100", 1100, 620);
                render(table(true), theme + "-view-1100", 1100, 620);
                verifyCellActions();
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
        Files.write(output.resolve(theme + "-manifest.txt"), snapshots);
        System.exit(0); // SDK timer threads otherwise keep this screenshot process alive.
    }
}
