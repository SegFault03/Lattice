import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.intellij.ui.JBColor;
import com.intellij.openapi.util.IconLoader;
import com.segfault03.ideadb.dialog.ConnectionDialog;
import com.segfault03.ideadb.dialog.CreateDatabaseDialog;
import com.segfault03.ideadb.dialog.CreateTableDialog;
import com.segfault03.ideadb.dialog.AlterTableDialog;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.ui.TableDataEditorPanel;
import com.segfault03.ideadb.ui.ExplorerPreview;
import com.segfault03.ideadb.ui.SqlQueryConsolePanel;
import com.segfault03.ideadb.ui.DatabaseTable;
import com.segfault03.ideadb.ui.DatabaseInputs;
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
    static final Set<String> FEATURED_PREVIEWS = Set.of(
            "side-panel-340", "connection-mysql-600", "table-1100", "table-new-row-1100", "sql-console-1100");
    static boolean featuredOnly;
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
        verifyInputs(component);
        if (featuredOnly && !FEATURED_PREVIEWS.contains(name.replaceFirst("^(light|dark)-", ""))) return;
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
    static void verifyInputs(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JComponent input && (input instanceof JTextField || input instanceof JComboBox<?>
                    || input instanceof com.intellij.openapi.ui.TextFieldWithBrowseButton)) {
                boolean embedded = SwingUtilities.getAncestorOfClass(JComboBox.class, input) != null
                        || SwingUtilities.getAncestorOfClass(com.intellij.openapi.ui.TextFieldWithBrowseButton.class, input) != null
                        || SwingUtilities.getAncestorOfClass(CellRendererPane.class, input) != null;
                if (!embedded) {
                    if (input.getPreferredSize().height != DatabaseInputs.height(input))
                        throw new AssertionError("Inconsistent input height: " + input.getClass().getSimpleName());
                    if (input.getInsets().top != input.getInsets().bottom)
                        throw new AssertionError("Uneven input padding: " + input.getClass().getSimpleName());
                    if (input.getWidth() > 0 && input.getHeight() > 0) {
                        int baseline = input.getBaseline(input.getWidth(), input.getHeight());
                        if (input instanceof com.intellij.openapi.ui.TextFieldWithBrowseButton browse) {
                            JTextField field = browse.getTextField();
                            baseline = field.getY() + field.getBaseline(field.getWidth(), field.getHeight());
                        }
                        if (baseline >= 0) {
                            FontMetrics font = input.getFontMetrics(input.getFont());
                            int top = baseline - font.getAscent();
                            int bottom = input.getHeight() - baseline - font.getDescent();
                            if (top < 0 || bottom < 0 || Math.abs(top - bottom) > 2)
                                throw new AssertionError("Uncentered input text: " + input.getClass().getSimpleName()
                                        + " top=" + top + " bottom=" + bottom + " height=" + input.getHeight());
                        }
                    }
                }
            }
            if (child instanceof Container nested) verifyInputs(nested);
        }
    }
    static void inputPreviews(String theme) throws Exception {
        JPanel samples = new JPanel(new GridBagLayout());
        samples.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JTextField disabled = DatabaseInputs.textField("Disabled connection");
        disabled.setEnabled(false);
        JTextField error = DatabaseInputs.textField("Not a valid port");
        error.putClientProperty("JComponent.outline", "error");
        JTextField large = DatabaseInputs.textField("Larger text stays readable");
        large.setFont(large.getFont().deriveFont(20f));
        JPasswordField password = DatabaseInputs.passwordField();
        password.setText("sample-password");
        JComboBox<String> editable = DatabaseInputs.comboBox(new String[]{"26.7.0", "9.0.0", "8.4.0"});
        editable.setEditable(true);
        com.intellij.openapi.ui.TextFieldWithBrowseButton browse = DatabaseInputs.browseField();
        browse.setText("/home/developer/drivers/mysql.jar");
        JComponent[] fields = {DatabaseInputs.textField("Local development"), DatabaseInputs.textField("3306"),
                password, DatabaseInputs.comboBox(new String[]{"Auto: Off", "10s", "30s"}), editable, browse, disabled, error, large};
        String[] names = {"Text", "Number", "Password", "Dropdown", "Editable dropdown", "File picker", "Disabled", "Validation error", "Larger font"};
        for (int row = 0; row < fields.length; row++) {
            GridBagConstraints cell = new GridBagConstraints();
            cell.gridx = 0; cell.gridy = row; cell.anchor = GridBagConstraints.WEST;
            cell.insets = new Insets(5, 0, 5, 16);
            samples.add(new JLabel(names[row]), cell);
            cell.gridx = 1; cell.weightx = 1; cell.fill = GridBagConstraints.HORIZONTAL;
            cell.insets = new Insets(5, 0, 5, 0);
            samples.add(fields[row], cell);
        }
        render(samples, theme + "-inputs-600", 600, samples.getPreferredSize().height);
        CreateDatabaseDialog database = new CreateDatabaseDialog(null, config(DatabaseType.MYSQL));
        ((JTextField)field(database, "nameField")).setText("shop_archive");
        render(database.previewPanel(), theme + "-schema-create-database-600", 600, database.previewPanel().getPreferredSize().height);
        CreateTableDialog create = new CreateTableDialog(null, config(DatabaseType.MYSQL), "shop");
        JPanel createPanel = create.previewPanel();
        JTable columns = (JTable)field(create, "columnsTable");
        ((JScrollPane)columns.getParent().getParent()).setColumnHeaderView(columns.getTableHeader());
        render(createPanel, theme + "-schema-create-table-740", 740, createPanel.getPreferredSize().height);
        TableMetadata metadata = (TableMetadata)field(table(false), "tableMetadata");
        AlterTableDialog alter = new AlterTableDialog(null, config(DatabaseType.MYSQL), "shop", metadata);
        render(alter.previewPanel(), theme + "-schema-alter-table-800", 800, alter.previewPanel().getPreferredSize().height);
        findButton(alter.previewPanel(), "Drop column").doClick(0);
        render(alter.previewPanel(), theme + "-schema-drop-column-800", 800, alter.previewPanel().getPreferredSize().height);
    }
    static void connection(String theme, String variant, int width) throws Exception {
        ConnectionConfig config = config(variant.startsWith("hsql") ? DatabaseType.HSQLDB : DatabaseType.MYSQL);
        if (variant.startsWith("hsql-memory")) { config.setHsqlMode(HsqlMode.MEM); config.setDatabaseName("scratch"); }
        if (variant.startsWith("hsql-file")) { config.setHsqlMode(HsqlMode.FILE); config.setDatabaseName("/home/developer/data/shop"); }
        if (variant.equals("jdbc-url")) config.setCustomUrl("jdbc:mysql://localhost:3306/shop?useSSL=true");
        if (variant.equals("driver-download")) { config.setDriverSource(DriverSource.DOWNLOAD); config.setDriverVersion("26.7.0"); }
        if (variant.equals("driver-local")) { config.setDriverSource(DriverSource.LOCAL_JAR); config.setDriverJarPath("/home/developer/drivers/mysql-connector-j.jar"); }
        if (variant.equals("mysql-incomplete")) config.setUser("");
        if (variant.equals("hsql-incomplete")) config.setDatabaseName("");
        ConnectionDialog dialog = new ConnectionDialog(null, config, variant.equals("new"));
        if (variant.endsWith("bundled-expanded") || variant.equals("mysql-collapsed-after-expansion")) {
            JPanel root = (JPanel)field(dialog, "rootPanel");
            int collapsed = root.getPreferredSize().height;
            invoke(dialog, "setDriverExpanded", new Class<?>[]{boolean.class}, true);
            int expanded = root.getPreferredSize().height;
            if (expanded <= collapsed) throw new AssertionError("Driver options must grow the form");
            root.setSize(width - 56, expanded);
            for (int pass = 0; pass < 4; pass++) layout(root);
            JComponent source = (JComponent)field(dialog, "driverSourceCombo");
            JLabel ready = (JLabel)field(dialog, "driverStatusLabel");
            JPanel details = (JPanel)field(dialog, "driverDetails");
            int gap = SwingUtilities.convertPoint(ready, 0, 0, details).y
                    - SwingUtilities.convertPoint(source, 0, source.getHeight(), details).y;
            if (gap > 12 || details.getHeight() - ready.getY() - ready.getHeight() > 8)
                throw new AssertionError("Bundled driver reserves empty space: gap=" + gap);
            if (variant.equals("mysql-collapsed-after-expansion")) {
                invoke(dialog, "setDriverExpanded", new Class<?>[]{boolean.class}, false);
                if (root.getPreferredSize().height != collapsed) throw new AssertionError("Collapsing must restore form height");
            }
        }
        if (variant.equals("jdbc-url-incomplete")) ((JRadioButton)field(dialog, "customUrlRadio")).doClick(0);
        JButton test = (JButton)field(dialog, "testButton");
        if (!test.isVisible()) throw new AssertionError("Test connection must remain visible when disabled");
        boolean incomplete = variant.endsWith("incomplete") || variant.equals("driver-local")
                || variant.equals("driver-download") && !Files.isRegularFile(com.segfault03.ideadb.service.DriverCatalog.downloadedJar(config.getType(), config.getDriverVersion()));
        if (test.isEnabled() == incomplete) throw new AssertionError("Incorrect Test connection availability: " + variant);
        if (variant.equals("success") || variant.equals("failed")) {
            JLabel status = (JLabel)field(dialog, "testStatusLabel");
            status.setVisible(true);
            com.segfault03.ideadb.ui.DatabaseUi.status(status,
                    variant.equals("success") ? "Connected · MySQL 8.4.4" : "Connection failed · Check the settings",
                    variant.equals("success") ? com.segfault03.ideadb.ui.DatabaseUi.Tone.SUCCESS : com.segfault03.ideadb.ui.DatabaseUi.Tone.ERROR);
        }
        JPanel panel = dialog.previewPanel();
        if (variant.endsWith("bundled-expanded") || variant.equals("driver-local")) {
            // Mirror the dialog's queued scroll-to-options after expansion, before painting.
            panel.setSize(width, panel.getPreferredSize().height);
            for (int pass = 0; pass < 4; pass++) layout(panel);
            ((JComponent)field(dialog, "driverDetails")).scrollRectToVisible(
                    new Rectangle(0, 0, ((JComponent)field(dialog, "driverDetails")).getWidth(),
                            ((JComponent)field(dialog, "driverDetails")).getHeight()));
        }
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
        if (!variant.equals("jdbc-url-incomplete") && !((JLabel)field(dialog, "urlPreviewLabel")).getText().equals(config.buildJdbcUrl()))
            throw new AssertionError("Resolved URL must retain the full value");
        // Editing settings here is safe: dialog network/test actions are never invoked.
        if (!variant.equals("jdbc-url-incomplete") && !dialog.getResultConfig().buildJdbcUrl().equals(config.buildJdbcUrl()))
            throw new AssertionError("Preview changed the configured JDBC URL for " + variant);
    }
    static void verifyConnectionReadiness() throws Exception {
        ConnectionDialog dialog = new ConnectionDialog(null, config(DatabaseType.MYSQL), true);
        JButton test = (JButton)field(dialog, "testButton");
        JTextField user = (JTextField)field(dialog, "mysqlUserField");
        user.setText("");
        if (test.isEnabled()) throw new AssertionError("Empty user must disable testing");
        user.setText("developer");
        if (!test.isEnabled()) throw new AssertionError("Filling user must enable testing");
        JTextField port = (JTextField)field(dialog, "mysqlPortField");
        for (String invalid : List.of("", "invalid", "65536")) {
            port.setText(invalid);
            if (test.isEnabled()) throw new AssertionError("Invalid port must disable testing");
        }
        port.setText("3306");
        if (!test.isEnabled()) throw new AssertionError("Correcting port must enable testing");
        ((JComboBox<?>)field(dialog, "driverSourceCombo")).setSelectedItem(DriverSource.DOWNLOAD);
        ((JComboBox<?>)field(dialog, "driverVersionCombo")).getEditor().setItem("999.999.999");
        if (test.isEnabled() || !((JButton)field(dialog, "downloadDriverButton")).isEnabled())
            throw new AssertionError("Missing driver disables testing while allowing Download");
        ((JComboBox<?>)field(dialog, "driverSourceCombo")).setSelectedItem(DriverSource.BUNDLED);
        if (!test.isEnabled()) throw new AssertionError("Switching to bundled restores testing");
        ((JRadioButton)field(dialog, "customUrlRadio")).doClick(0);
        if (test.isEnabled()) throw new AssertionError("Empty JDBC URL disables testing");
        ((JTextField)field(dialog, "customUrlField")).setText("jdbc:hsqldb:mem:scratch");
        if (!test.isEnabled()) throw new AssertionError("Complete JDBC URL allows testing without separate credentials");
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
        ((JLabel) field(panel, "statusLabel")).setText("12 rows · 18 ms · Total: not counted");
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
        com.segfault03.ideadb.ui.DatabaseUi.status((JLabel)field(panel, "statusLabel"), "4 rows · 18 ms", com.segfault03.ideadb.ui.DatabaseUi.Tone.SUCCESS);
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
        if (table.getToolTipText(hover(table, nullRow, nullColumn)) != null)
            throw new AssertionError("Table hover must stay disabled");
    }
    static JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton match = findButton(nested, text);
                if (match != null) return match;
            }
        }
        return null;
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
    static void nativeDelegates(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && !(button instanceof com.intellij.ui.components.ActionLink))
                button.setUI(new com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI());
            if (child instanceof JTabbedPane tabs)
                tabs.setUI(new com.intellij.ide.ui.laf.darcula.ui.DarculaTabbedPaneUI());
            if (child instanceof javax.swing.table.JTableHeader header)
                header.setUI(new com.intellij.ide.ui.laf.darcula.DarculaTableHeaderUI());
            if (child instanceof Container nested) nativeDelegates(nested);
        }
    }
    static void autoEditing(String theme) throws Exception {
        TableDataEditorPanel panel = table(false);
        JTable table = (JTable)field(panel, "dataTable");
        TableModel model = (TableModel)field(panel, "tableModel");
        if (!model.isCellEditable(0, 0) || !model.getColumnName(0).contains("Auto") || model.getColumnName(0).contains("AI"))
            throw new AssertionError("Auto columns must be labeled Auto and allow editing");
        model.setValueAt("invalid identity", 0, 0);
        if (((JButton)field(panel, "saveBtn")).isEnabled()) throw new AssertionError("Invalid auto values must disable Commit");
        model.setValueAt(2001L, 0, 0);
        @SuppressWarnings("unchecked") List<List<Object>> original = (List<List<Object>>)field(model, "originalRows");
        if (!original.get(0).get(0).equals(1001L)) throw new AssertionError("Auto edits must preserve the original row identity");
        panel.setSize(1100, 620);
        for (int pass = 0; pass < 4; pass++) layout(panel);
        if (!table.editCellAt(0, 0)) throw new AssertionError("Auto cell must open its editor");
        ((JTextField)table.getEditorComponent()).setText("2002");
        render(panel, theme + "-table-auto-cell-editing-1100", 1100, 620);
        if (!table.getCellEditor().stopCellEditing()) throw new AssertionError("Valid auto input must become a pending edit");
        invoke(model, "addNewRow", new Class<?>[]{});
        model.setValueAt("Grace Lee", 12, 1);
        model.setValueAt(3001L, 12, 0);
        if (!((JButton)field(panel, "saveBtn")).isEnabled()) throw new AssertionError("Explicit new auto value must be valid");
        render(panel, theme + "-table-new-auto-row-1100", 1100, 620);
        prepareMenu(table, 12, 0);
        JMenuItem defaultAction = (JMenuItem)table.getComponentPopupMenu().getComponent(1);
        if (!defaultAction.isEnabled()) throw new AssertionError("New auto cells can restore database generation");
        defaultAction.doClick(0);
        if (model.getValueAt(12, 0) != RowDefaults.Value.USE_DEFAULT) throw new AssertionError("Auto generation must use the default marker");
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
        if (nullAction.isEnabled() || defaultAction.isEnabled()) throw new AssertionError("Existing non-nullable identity has no NULL/default action");
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
    static JPanel welcome(boolean configured) throws Exception {
        var settings = new com.segfault03.ideadb.state.DatabaseSettingsState();
        if (configured) {
            var state = (com.segfault03.ideadb.state.DatabaseSettingsState.State)field(settings, "state");
            state.connections.add(config(DatabaseType.MYSQL));
        }
        var constructor = com.segfault03.ideadb.ui.WelcomePanel.class.getDeclaredConstructor(
                com.intellij.openapi.project.Project.class, com.segfault03.ideadb.state.DatabaseSettingsState.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, settings);
    }

    static void verifyWelcome() throws Exception {
        JPanel panel = welcome(false);
        JButton console = (JButton)field(panel, "openConsole");
        if (console.isEnabled()) throw new AssertionError("A SQL console needs a saved connection");
        var settings = (com.segfault03.ideadb.state.DatabaseSettingsState)field(panel, "settings");
        var state = (com.segfault03.ideadb.state.DatabaseSettingsState.State)field(settings, "state");
        state.connections.add(config(DatabaseType.MYSQL));
        invoke(panel, "updateConsoleAvailability", new Class<?>[]{});
        if (!console.isEnabled()) throw new AssertionError("Console must become available after adding a connection");
        verifyWelcomePreference(panel, settings);
        JLabel status = new JLabel();
        com.segfault03.ideadb.ui.DatabaseUi.status(status, "Query failed", com.segfault03.ideadb.ui.DatabaseUi.Tone.ERROR);
        if (status.getIcon() == null) throw new AssertionError("Error needs a severity icon");
        com.segfault03.ideadb.ui.DatabaseUi.status(status, "Ready", com.segfault03.ideadb.ui.DatabaseUi.Tone.NORMAL);
        if (status.getIcon() != null) throw new AssertionError("Status icon must reset");
        System.out.println("Welcome availability, startup preference and status reset verified");
    }

    static void verifyWelcomePreference(Container panel, com.segfault03.ideadb.state.DatabaseSettingsState settings) {
        for (Component component : panel.getComponents()) {
            if (component instanceof JCheckBox check && "Show welcome screen on startup".equals(check.getText())) {
                check.doClick(0);
                if (settings.isShowWelcomeScreen()) throw new AssertionError("Startup preference should follow the positive checkbox");
                check.doClick(0);
                if (!settings.isShowWelcomeScreen()) throw new AssertionError("Startup preference should be restored");
            } else if (component instanceof Container nested) verifyWelcomePreference(nested, settings);
        }
    }

    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]);
        Files.createDirectories(output);
        String theme = args[1];
        featuredOnly = args.length < 3 || !args[2].equals("all");
        UIManager.setLookAndFeel(theme.equals("dark") ? new FlatDarculaLaf() : new FlatIntelliJLaf());
        UIManager.put("defaultFont", new Font("SansSerif", Font.PLAIN, 13));
        JBColor.setDark(theme.equals("dark"));
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        IconLoader.activate();
        IconLoader.setUseDarkIcons(theme.equals("dark"));
        SwingUtilities.invokeAndWait(() -> {
            try {
                inputPreviews(theme);
                render(welcome(false), theme + "-welcome-900", 900, 680);
                render(welcome(false), theme + "-welcome-520", 520, 680);
                render(welcome(true), theme + "-welcome-connected-900", 900, 680);
                verifyWelcome();
                render(ExplorerPreview.create(config(DatabaseType.MYSQL), false), theme + "-side-panel-340", 340, 620);
                render(ExplorerPreview.create(config(DatabaseType.MYSQL), true), theme + "-side-panel-empty-340", 340, 620);
                JPanel empty = ExplorerPreview.create(config(DatabaseType.MYSQL), true);
                empty.setBounds(0, 0, 340, 620);
                layout(empty);
                JButton addConnection = findButton(empty, "Add a connection…");
                JPopupMenu choices = com.segfault03.ideadb.ui.DatabaseUi.connectionMenu(type -> {});
                Point anchor = SwingUtilities.convertPoint(addConnection, 0, addConnection.getHeight(), empty);
                choices.setBounds(anchor.x, anchor.y, choices.getPreferredSize().width, choices.getPreferredSize().height);
                layout(choices);
                // Paint the production popup directly without opening an OS popup window.
                JComponent popupPreview = new JComponent() {
                    @Override protected void paintComponent(Graphics graphics) { choices.paint(graphics); }
                };
                popupPreview.setBounds(choices.getBounds());
                JLayeredPane menuPreview = new JLayeredPane();
                menuPreview.add(empty, JLayeredPane.DEFAULT_LAYER);
                menuPreview.add(popupPreview, JLayeredPane.POPUP_LAYER);
                render(menuPreview, theme + "-side-panel-connection-menu-340", 340, 620);
                render(ExplorerPreview.error(config(DatabaseType.MYSQL)), theme + "-side-panel-error-340", 340, 620);
                SqlQueryConsolePanel console = console();
                render(console, theme + "-sql-console-1100", 1100, 620);
                render(console, theme + "-sql-console-760", 760, 620);
                render(console, theme + "-sql-console-520", 520, 620);
                BorderLayout consoleLayout = (BorderLayout)console.getLayout();
                verifyControls((Container)consoleLayout.getLayoutComponent(BorderLayout.NORTH));
                verifyControls((Container)consoleLayout.getLayoutComponent(BorderLayout.SOUTH));
                DatabaseTable results = (DatabaseTable)field(console, "resultsTable");
                verifyGrid(results, 1, 2);
                invoke(console, "setRunning", new Class<?>[]{boolean.class}, true);
                com.segfault03.ideadb.ui.DatabaseUi.status((JLabel)field(console, "statusLabel"), "Running query…", com.segfault03.ideadb.ui.DatabaseUi.Tone.BUSY);
                render(console, theme + "-sql-console-running-1100", 1100, 620);
                invoke(console, "setRunning", new Class<?>[]{boolean.class}, false);
                JTextArea consoleEditor = (JTextArea)field(console, "editorArea");
                console.setSqlText(consoleEditor.getText().replace("WHERE status", "WHERE customer_status"));
                invoke(console, "showQueryError", new Class<?>[]{String.class, long.class}, "Unknown column 'customer_status' in WHERE clause", 8L);
                render(console, theme + "-sql-console-messages-1100", 1100, 620);
                SqlQueryConsolePanel emptyConsole = new SqlQueryConsolePanel(null, config(DatabaseType.MYSQL), "shop", List.of("shop"));
                emptyConsole.setSqlText("");
                render(emptyConsole, theme + "-sql-console-empty-1100", 1100, 620);
                verifyConsoleActions();
                SqlQueryConsolePanel nativeConsole = console();
                nativeDelegates(nativeConsole);
                ((DatabaseTable)field(nativeConsole, "resultsTable")).sizeColumnsToContent();
                render(nativeConsole, theme + "-sql-console-native-1100", 1100, 620);
                verifyControls((Container)((BorderLayout)nativeConsole.getLayout()).getLayoutComponent(BorderLayout.NORTH));
                JTabbedPane nativeTabs = (JTabbedPane)field(nativeConsole, "resultsTabs");
                if (nativeTabs.getTabComponentAt(0) != null || nativeTabs.getTitleAt(0).isBlank())
                    throw new AssertionError("Native result tabs must use visible text titles");
                TableDataEditorPanel nativeTable = table(false);
                nativeDelegates(nativeTable);
                ((DatabaseTable)field(nativeTable, "dataTable")).sizeColumnsToContent();
                render(nativeTable, theme + "-table-native-1100", 1100, 620);
                verifyControls((Container)((BorderLayout)nativeTable.getLayout()).getLayoutComponent(BorderLayout.NORTH));
                autoEditing(theme);
                verifyConnectionReadiness();
                for (String variant : List.of("mysql", "new", "success", "failed", "hsql-server", "hsql-memory", "hsql-file", "jdbc-url", "driver-download", "driver-local", "mysql-bundled-expanded", "hsql-bundled-expanded", "hsql-memory-bundled-expanded", "hsql-file-bundled-expanded", "mysql-collapsed-after-expansion", "mysql-incomplete", "hsql-incomplete", "jdbc-url-incomplete")) connection(theme, variant, 600);
                connection(theme, "mysql", 800);
                TableDataEditorPanel editor = table(false);
                render(editor, theme + "-table-1100", 1100, 620);
                DatabaseTable grid = (DatabaseTable)field(editor, "dataTable");
                verifyGrid(grid, 3, 2);
                render(editor, theme + "-table-760", 760, 620);
                render(editor, theme + "-table-520", 520, 620);
                BorderLayout editorLayout = (BorderLayout)editor.getLayout();
                verifyControls((Container)editorLayout.getLayoutComponent(BorderLayout.NORTH));
                verifyControls((Container)editorLayout.getLayoutComponent(BorderLayout.SOUTH));
                Object model = field(editor, "tableModel");
                ((TableModel)model).setValueAt("Amelia Stone", 0, 1);
                if (!((JButton)field(editor, "saveBtn")).isEnabled()) throw new AssertionError("Commit must be enabled for a valid edit");
                render(editor, theme + "-table-edited-1100", 1100, 620);
                invoke(model, "addNewRow", new Class<?>[]{});
                ((TableModel)model).setValueAt("Grace Lee", 12, 1);
                ((TableModel)model).setValueAt("grace@example.com", 12, 2);
                invoke(editor, "updatePendingChangesState", new Class<?>[]{});
                render(editor, theme + "-table-new-row-1100", 1100, 620);
                if (!grid.editCellAt(12, 1)) throw new AssertionError("New-row cell editor must open");
                ((JTextField)grid.getEditorComponent()).setText("Grace Lee");
                render(editor, theme + "-table-cell-editing-1100", 1100, 620);
                if (!grid.getCellEditor().stopCellEditing()) throw new AssertionError("Valid text must commit to the pending row");
                ((TableModel)model).setValueAt("not a number", 1, 4);
                if (((JButton)field(editor, "saveBtn")).isEnabled()) throw new AssertionError("Commit must be disabled for an invalid decimal");
                render(editor, theme + "-table-error-1100", 1100, 620);
                render(table(true), theme + "-view-1100", 1100, 620);
                verifyCellActions();
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
        Files.write(output.resolve(theme + "-manifest.txt"), snapshots);
        System.exit(0); // SDK timer threads otherwise keep this screenshot process alive.
    }
}
