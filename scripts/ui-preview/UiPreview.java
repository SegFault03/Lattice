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

/** Paint production Swing components with fixture data in a headless IntelliJ test application. */
public final class UiPreview {
    static Path output;
    static final List<String> snapshots = new ArrayList<>();
    static final Set<String> FEATURED_PREVIEWS = Set.of(
            "side-panel-340", "connection-mysql-740", "table-1100", "table-new-row-1100", "sql-console-1100");
    static boolean featuredOnly;
    private static Font previewFont() {
        Set<String> available = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        List<String> preferred = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                ? List.of("Helvetica Neue", "Arial", "Liberation Sans", "SansSerif")
                : System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? List.of("Segoe UI", "Arial", "DejaVu Sans", "SansSerif")
                : List.of("DejaVu Sans", "Liberation Sans", "Noto Sans", "SansSerif");
        String family = preferred.stream().filter(available::contains).findFirst().orElse("SansSerif");
        return new Font(family, Font.PLAIN, 13);
    }
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
    static void updateToolbars(Container container) throws Exception {
        if (container instanceof com.intellij.openapi.actionSystem.impl.ActionToolbarImpl toolbar) {
            // Native action expansion can finish asynchronously, especially for the
            // first toolbar. Pump the test event queue before offscreen layout/paint.
            var update = toolbar.updateActionsAsync();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (!update.isDone()) {
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
                try { update.get(10, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException timeout) {
                    if (System.nanoTime() >= deadline) throw timeout;
                }
            }
            update.get(); // Propagate action expansion errors rather than painting an empty toolbar.
            if (!toolbar.hasVisibleActions())
                throw new AssertionError("Preview toolbar did not populate: " + toolbar.getPlace());
        } else if (container instanceof com.intellij.openapi.actionSystem.ActionToolbar toolbar)
            toolbar.updateActionsImmediately();
        for (Component component : container.getComponents())
            if (component instanceof Container nested) updateToolbars(nested);
    }
    static void render(JComponent component, String name, int width, int height) throws Exception {
        component.setSize(width, height);
        updateToolbars(component);
        for (int pass = 0; pass < 4; pass++) layout(component);
        verifyInputs(component);
        boolean featured = FEATURED_PREVIEWS.contains(name.replaceFirst("^(light|dark)-", ""));
        if (featuredOnly && !featured) return;
        // Paint at higher pixel density without changing component layout or aspect ratio.
        // Featured images also serve as Marketplace screenshots (minimum 1200 x 760).
        int scale = featured ? Math.max(2, Math.max((1200 + width - 1) / width, (760 + height - 1) / height)) : 1;
        BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.scale(scale, scale);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", output.resolve(name + ".png").toFile());
        snapshots.add(name + ".png");
        System.out.println(name + ": " + image.getWidth() + " x " + image.getHeight());
    }
    static ConnectionConfig config(DatabaseType type) {
        ConnectionConfig config = new ConnectionConfig(type, type == DatabaseType.MYSQL ? "Local development" : "Scratch database");
        config.setHost("localhost");
        config.setPort(type.getDefaultPort());
        config.setDatabaseName("shop");
        config.setUser(type == DatabaseType.MYSQL ? "developer" : type.getDefaultUser());
        return config;
    }
    static ConnectionDialog connectionDialog(ConnectionConfig config, boolean newConnection) throws Exception {
        ConnectionDialog dialog = new ConnectionDialog(null, config, newConnection);
        refreshPreviewDrivers(dialog);
        return dialog;
    }
    static void refreshPreviewDrivers(ConnectionDialog dialog) throws Exception {
        // Preview background work is blocked. Supply the actual local fixture
        // inventory and finish discovery before checking/painting ready controls.
        @SuppressWarnings("unchecked") Map<DatabaseType, List<InstalledDriver>> inventory =
                (Map<DatabaseType, List<InstalledDriver>>)field(dialog, "driverInventory");
        for (DatabaseType type : DatabaseType.values())
            inventory.put(type, com.segfault03.ideadb.service.DriverStore.installedInRepository(
                    type, output.resolve("fixture-maven-repository")));
        Field busy = ConnectionDialog.class.getDeclaredField("discoveryBusy");
        busy.setAccessible(true);
        busy.setBoolean(dialog, false);
        invoke(dialog, "refillDriverVersions", new Class<?>[0]);
    }
    static void verifyInputs(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JComponent input && (input instanceof JTextField || input instanceof JComboBox<?>
                    || input instanceof com.intellij.openapi.ui.TextFieldWithBrowseButton)) {
                boolean embedded = SwingUtilities.getAncestorOfClass(JComboBox.class, input) != null
                        || SwingUtilities.getAncestorOfClass(com.intellij.ui.SearchTextField.class, input) != null
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
        ConnectionConfig config = config(variant.contains("hsql") ? DatabaseType.HSQLDB : variant.equals("postgresql") ? DatabaseType.POSTGRESQL : DatabaseType.MYSQL);
        if (variant.startsWith("hsql-memory")) { config.setHsqlMode(HsqlMode.MEM); config.setDatabaseName("scratch"); }
        if (variant.startsWith("hsql-file")) { config.setHsqlMode(HsqlMode.FILE); config.setDatabaseName("/home/developer/data/shop"); }
        if (variant.equals("jdbc-url")) config.setCustomUrl("jdbc:mysql://localhost:3306/shop?useSSL=true");
        if (variant.equals("driver-download")) { config.setDriverSource(DriverSource.DOWNLOAD); config.setDriverVersion("26.7.0"); }
        if (variant.startsWith("driver-retained")) {
            // A download kept on this machine stays selectable in the bundled list.
            // The staged JAR is a copy of a packaged driver; the name is a display fixture.
            String version = variant.endsWith("-hsqldb") ? "2.7.4-jdk8" : "8.4.0";
            Path jar = com.segfault03.ideadb.service.DriverCatalog.downloadedJar(
                    variant.endsWith("-hsqldb") ? DatabaseType.HSQLDB : DatabaseType.MYSQL, version);
            try {
                java.nio.file.Files.createDirectories(jar.getParent());
                java.nio.file.Files.copy(Path.of(System.getProperty(variant.endsWith("-hsqldb") ? "lattice.preview.hsqldb" : "lattice.preview.mysql")), jar,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception absent) {
                throw new AssertionError("Could not stage a retained driver preview", absent);
            }
            config.setDriverSource(DriverSource.BUNDLED);
            config.setDriverVersion(version);
        }
        if (variant.equals("driver-local")) { config.setDriverSource(DriverSource.LOCAL_JAR); config.setDriverJarPath("/home/developer/drivers/mysql-connector-j.jar"); }
        if (variant.equals("mysql-incomplete")) config.setUser("");
        if (variant.equals("hsql-incomplete")) config.setDatabaseName("");
        ConnectionDialog dialog = connectionDialog(config, (variant.equals("new") || variant.equals("postgresql")));
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
            JPanel cards = (JPanel)field(dialog, "driverCards");
            // The bundled card now lists installed drivers, so it must fill its row without reserving blank space.
            if (!cards.isVisible()) throw new AssertionError("Bundled driver list must be visible");
            int gap = SwingUtilities.convertPoint(ready, 0, 0, details).y
                    - SwingUtilities.convertPoint(source, 0, source.getHeight(), details).y;
            if (gap < cards.getHeight() || gap - cards.getHeight() > 12 || details.getHeight() - ready.getY() - ready.getHeight() > 8)
                throw new AssertionError("Bundled driver reserves empty space: gap=" + gap + " cards=" + cards.getHeight());
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
        if (variant.startsWith("driver-retained")) {
            // The retained download must be offered by the bundled list, not just accepted silently.
            var bundled = (JComboBox<?>)field(dialog, "bundledDriverCombo");
            var expected = (com.segfault03.ideadb.model.InstalledDriver) bundled.getSelectedItem();
            if (expected == null || expected.packaged() || !expected.version().equals(config.getDriverVersion()))
                throw new AssertionError("Retained driver must stay selected: " + config.getDriverVersion());
            if (bundled.getItemCount() < 2) throw new AssertionError("Bundled list must offer the packaged and stored drivers");
        }
        if (variant.equals("success") || variant.equals("failed")) {
            JLabel status = (JLabel)field(dialog, "testStatusLabel");
            status.setVisible(true);
            com.segfault03.ideadb.ui.DatabaseUi.status(status,
                    variant.equals("success") ? "Connected · MySQL 8.4.4" : "Connection failed · Check the settings",
                    variant.equals("success") ? com.segfault03.ideadb.ui.DatabaseUi.Tone.SUCCESS : com.segfault03.ideadb.ui.DatabaseUi.Tone.ERROR);
        }
        JPanel panel = dialog.previewPanel();
        if (variant.equals("new")) {
            JComponent picker = (JComponent) field(dialog, "sourcePanel");
            @SuppressWarnings("unchecked") Map<DatabaseType, JToggleButton> choices =
                    (Map<DatabaseType, JToggleButton>) field(picker, "choices");
            JComboBox<?> selection = (JComboBox<?>) field(dialog, "typeCombo");
            for (DatabaseType type : DatabaseType.values()) {
                choices.get(type).doClick(0);
                if (selection.getSelectedItem() != type || !choices.get(type).isSelected())
                    throw new AssertionError("Source picker did not select " + type);
                if (!((JTextField) field(dialog, com.segfault03.ideadb.provider.DatabaseProviders.get(type).connectionForm()
                        == com.segfault03.ideadb.provider.DatabaseProvider.ConnectionForm.SERVER
                        ? "mysqlPortField" : "hsqlServerPortField")).getText().equals(String.valueOf(type.getDefaultPort())))
                    throw new AssertionError("Source picker did not update the port for " + type);
            }
            choices.get(DatabaseType.MYSQL).doClick(0);
            // A short viewport proves the source list scrolls independently of the form.
            picker.setSize(208, 150);
            for (int pass = 0; pass < 4; pass++) layout(picker);
            JScrollPane sourceScroll = (JScrollPane) ((BorderLayout) picker.getLayout())
                    .getLayoutComponent(BorderLayout.CENTER);
            if (!sourceScroll.getVerticalScrollBar().isVisible())
                throw new AssertionError("Source picker must scroll when its rows exceed the viewport");
            sourceScroll.getVerticalScrollBar().setValue(sourceScroll.getVerticalScrollBar().getMaximum());
            render(picker, theme + "-source-picker-scrolled-208", 208, 150);
            System.out.println("Source selection, provider ports, and independent vertical scrolling verified");
        }
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
        if (!(variant.equals("new") || variant.equals("postgresql")) && !variant.equals("jdbc-url-incomplete")
                && !((JLabel)field(dialog, "urlPreviewLabel")).getText().equals(config.buildJdbcUrl()))
            throw new AssertionError("Resolved URL must retain the full value");
        // Editing settings here is safe: dialog network/test actions are never invoked.
        if (!variant.equals("jdbc-url-incomplete") && !dialog.getResultConfig().buildJdbcUrl().equals(config.buildJdbcUrl()))
            throw new AssertionError("Preview changed the configured JDBC URL for " + variant);
    }
    /** Index of the stored MySQL 8.4.0 entry in the bundled driver list. */
    static int retainedIndex(JComboBox<?> bundled) {
        for (int index = 0; index < bundled.getItemCount(); index++)
            if (bundled.getItemAt(index) instanceof com.segfault03.ideadb.model.InstalledDriver driver
                    && !driver.packaged() && "8.4.0".equals(driver.version())) return index;
        return -1;
    }
    static void verifyConnectionReadiness() throws Exception {
        ConnectionDialog dialog = connectionDialog(config(DatabaseType.MYSQL), true);
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
        // A stored release must be offered without downloading and stay usable after selection.
        Path retained = com.segfault03.ideadb.service.DriverCatalog.downloadedJar(DatabaseType.MYSQL, "8.4.0");
        java.nio.file.Files.createDirectories(retained.getParent());
        java.nio.file.Files.copy(Path.of(System.getProperty("lattice.preview.mysql")), retained, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var bundled = (JComboBox<?>)field(dialog, "bundledDriverCombo");
        // The dialog was built before the file existed; a refresh must pick it up like a new download does.
        refreshPreviewDrivers(dialog);
        if (retainedIndex(bundled) < 0) throw new AssertionError("Stored driver must appear in the bundled list");
        bundled.setSelectedIndex(retainedIndex(bundled));
        if (!test.isEnabled()) throw new AssertionError("A stored driver must allow testing without downloading");
        refreshPreviewDrivers(dialog);
        if (bundled.getSelectedIndex() != retainedIndex(bundled))
            throw new AssertionError("Rebuilding the bundled list must keep the stored driver selected");
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
        meta.addColumn(new ColumnMetadata("status", "VARCHAR", java.sql.Types.VARCHAR, 20, 0, false, false, false, "'active'").withForeignKey(true));
        meta.addColumn(new ColumnMetadata("balance", "DECIMAL", java.sql.Types.DECIMAL, 10, 2, false, false, false, "0"));
        TableDataEditorPanel panel = new TableDataEditorPanel(null, config(DatabaseType.MYSQL), "shop", meta);
        Object model = field(panel, "tableModel");
        List<List<Object>> rows = new ArrayList<>();
        String[] names = {"Amelia Brooks", "Liam Chen", "Sofia Patel", "Noah Williams", "Olivia Reyes", "Ethan Kim", "Isabella Rossi", "Lucas Martin", "Mia Thompson", "Aiden Davis", "Charlotte Wilson", "Leo Garcia"};
        for (int i = 0; i < names.length; i++) rows.add(new ArrayList<>(Arrays.asList(1001L + i, names[i], i == 3 ? null : names[i].split(" ")[0].toLowerCase(Locale.ROOT) + "@example.com", i == 4 ? "inactive" : "active", new java.math.BigDecimal(i % 3 == 0 ? "240.50" : "0.00"))));
        invoke(model, "setData", new Class<?>[]{List.class, List.class, List.class}, List.of("id", "name", "email", "status", "balance"), List.of("BIGINT", "VARCHAR", "VARCHAR", "VARCHAR", "DECIMAL"), rows);
        // The preview task service intentionally does no I/O; release the production panel's
        // initial loading lock after supplying its fixture rows so edits render as usable.
        invoke(panel, "setReadRunning", new Class<?>[]{boolean.class}, false);
        JTable table = (JTable) field(panel, "dataTable");
        ((JScrollPane)table.getParent().getParent()).setColumnHeaderView(table.getTableHeader());
        int[] widths = {148, 190, 280, 150, 150};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        ((JButton) field(panel, "nextPageBtn")).setEnabled(false);
        ((JLabel) field(panel, "statusLabel")).setText("12 rows · 18 ms · Total: not counted");
        return panel;
    }
    static SqlQueryConsolePanel console() throws Exception {
        SqlQueryConsolePanel panel = new SqlQueryConsolePanel(null, config(DatabaseType.MYSQL), "shop", List.of("shop", "analytics"));
        panel.setSqlText("-- Active customers with an outstanding balance\nSELECT id, name, email, balance, status\nFROM customers\nWHERE status = 'active' AND balance > 0\nORDER BY balance DESC;\n");
        com.segfault03.ideadb.model.ReadOnlyResultModel model = (com.segfault03.ideadb.model.ReadOnlyResultModel)field(panel, "resultsModel");
        model.setDataVector(new Object[][]{
                {1001L, "Amelia Brooks", "amelia@example.com", new java.math.BigDecimal("240.50"), "active"},
                {1004L, "Noah Williams", null, new java.math.BigDecimal("240.50"), "active"},
                {1007L, "Isabella Rossi", "isabella@example.com", new java.math.BigDecimal("240.50"), "active"},
                {1010L, "Aiden Davis", "aiden@example.com", new java.math.BigDecimal("240.50"), "active"}
        }, new Object[]{"id", "name", "email", "balance", "status"});
        model.setResult(QueryResult.forResultSet(List.of("id", "name", "email", "balance", "status"),
                List.of("BIGINT", "VARCHAR", "VARCHAR", "DECIMAL", "VARCHAR"),
                java.util.stream.IntStream.range(0, model.getRowCount())
                        .mapToObj(row -> java.util.stream.IntStream.range(0, model.getColumnCount())
                                .mapToObj(column -> model.getValueAt(row, column)).toList()).toList(), 18)
                .withPrimaryKeyColumns(Set.of(0)).withForeignKeyColumns(Set.of(4)));
        JTable table = (JTable)field(panel, "resultsTable");
        ((JScrollPane)table.getParent().getParent()).setColumnHeaderView(table.getTableHeader());
        int[] widths = {110, 220, 320, 150, 150};
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
    static com.intellij.openapi.actionSystem.AnAction findToolbarAction(Container container, String tooltip) {
        for (Component child : container.getComponents()) {
            if (child instanceof com.intellij.openapi.actionSystem.ActionToolbar toolbar) {
                for (Object action : toolbar.getActions()) {
                    if (action instanceof com.intellij.openapi.actionSystem.AnAction candidate
                            && tooltip.equals(candidate.getTemplatePresentation().getDescription())) return candidate;
                }
            }
            if (child instanceof Container nested) {
                com.intellij.openapi.actionSystem.AnAction match = findToolbarAction(nested, tooltip);
                if (match != null) return match;
            }
        }
        return null;
    }
    static void performToolbarAction(Container container, String tooltip) {
        var action = findToolbarAction(container, tooltip);
        if (action == null) throw new AssertionError("Toolbar action not found: " + tooltip);
        action.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(
                action, null, com.intellij.openapi.actionSystem.ActionPlaces.TOOLBAR,
                com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT));
    }
    static void verifyConsoleActions() throws Exception {
        SqlQueryConsolePanel panel = console();
        panel.setSize(1100, 620);
        updateToolbars(panel);
        layout(panel);
        JTextArea editor = (JTextArea)field(panel, "editorArea");
        panel.setSqlText("");
        JButton run = (JButton)field(panel, "runBtn");
        JButton clear = (JButton)field(panel, "clearBtn");
        if (run.isEnabled() || clear.isEnabled()) throw new AssertionError("Empty SQL must disable Run and Clear");
        panel.setSqlText("-- Write your query\n/* comment */");
        if (run.isEnabled() || !clear.isEnabled()) throw new AssertionError("Comments can be cleared but cannot run");
        panel.setSqlText("-- comment\nSELECT 1");
        editor.select(0, 10);
        if (run.isEnabled()) throw new AssertionError("A comment-only selection cannot run");
        editor.select(11, editor.getText().length());
        if (!run.isEnabled()) throw new AssertionError("An executable selection must enable Run");
        panel.setSqlText("");
        JComboBox<?> template = (JComboBox<?>)findByTooltip(panel, "Insert a SQL template at the caret");
        template.setSelectedIndex(1);
        template.setSelectedIndex(1);
        if (editor.getText().split("SELECT", -1).length != 3) throw new AssertionError("Template must support repeated insertion");
        @SuppressWarnings("unchecked") JComboBox<QueryHistoryEntry> history = (JComboBox<QueryHistoryEntry>)field(panel, "historyCombo");
        history.addItem(new QueryHistoryEntry("SELECT 42;"));
        history.setSelectedIndex(1);
        if (!editor.getText().equals("SELECT 42;")) throw new AssertionError("History must restore the full query");
        performToolbarAction(panel, "Clear query text");
        if (!editor.getText().isEmpty()) throw new AssertionError("Clear action must clear the query");
        if (run.isEnabled() || clear.isEnabled()) throw new AssertionError("Clear must update both action states");
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
        JButton delete = (JButton)field(panel, "delRowBtn");
        JButton apply = (JButton)field(panel, "filterBtn");
        JButton clear = (JButton)field(panel, "clearFilterBtn");
        if (delete.isEnabled() || apply.isEnabled() || clear.isEnabled())
            throw new AssertionError("Unselected rows and empty clauses must disable their actions");
        Class<?> scopeType = Class.forName("com.segfault03.ideadb.ui.TableDataEditorPanel$ExportScope");
        Object selectedScope = java.util.Arrays.stream(scopeType.getEnumConstants())
                .filter(value -> value.toString().equals("SELECTED")).findFirst().orElseThrow();
        JPopupMenu exports = new JPopupMenu();
        invoke(panel, "addExportFormats", new Class<?>[]{JPopupMenu.class, String.class, scopeType}, exports, "Selected", selectedScope);
        JMenu selectedExport = (JMenu)exports.getComponent(0);
        if (selectedExport.isEnabled() || selectedExport.getItem(0).isEnabled())
            throw new AssertionError("Selected export must be disabled without a selection");
        table.setRowSelectionInterval(0, 0);
        if (!delete.isEnabled()) throw new AssertionError("Row selection must enable Delete");
        exports.removeAll();
        invoke(panel, "addExportFormats", new Class<?>[]{JPopupMenu.class, String.class, scopeType}, exports, "Selected", selectedScope);
        if (!((JMenu)exports.getComponent(0)).isEnabled()) throw new AssertionError("Selected export must enable with a selection");
        table.clearSelection();
        if (delete.isEnabled()) throw new AssertionError("Clearing selection must disable Delete");
        JTextField where = (JTextField)field(panel, "whereField");
        JTextField order = (JTextField)field(panel, "orderField");
        where.setText("id = 1"); order.setText("id DESC");
        if (!apply.isEnabled() || !clear.isEnabled()) throw new AssertionError("Clauses must enable Apply and Clear");
        clear.doClick(0);
        if (!where.getText().isEmpty() || !order.getText().isEmpty() || apply.isEnabled() || clear.isEnabled())
            throw new AssertionError("Clear must reset both clauses and their actions");
        invoke(panel, "setReadRunning", new Class<?>[]{boolean.class}, true);
        table.setRowSelectionInterval(0, 0);
        if (delete.isEnabled()) throw new AssertionError("Selection changes while busy must not enable Delete");
        invoke(panel, "setReadRunning", new Class<?>[]{boolean.class}, false);
        if (!delete.isEnabled()) throw new AssertionError("Completion must recompute selection state");
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

    static void connectionMenuPreview(JComponent content, String buttonText, String name, int width, int height) throws Exception {
        content.setBounds(0, 0, width, height);
        layout(content);
        JButton invoker = findButton(content, buttonText);
        JPopupMenu choices = com.segfault03.ideadb.ui.DatabaseUi.connectionMenu(type -> {});
        for (Component component : choices.getComponents()) {
            if (component instanceof JMenuItem item && (item.getIcon() == null || item.getIcon().getIconWidth() <= 0))
                throw new AssertionError("Connection menu needs a source icon: " + item.getText());
        }
        Point anchor = SwingUtilities.convertPoint(invoker, 0, invoker.getHeight(), content);
        choices.setBounds(anchor.x, anchor.y, choices.getPreferredSize().width, choices.getPreferredSize().height);
        layout(choices);
        JComponent popupPreview = new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                // Native popup windows supply their own surface; supply it for an offscreen capture too.
                Color surface = UIManager.getColor("PopupMenu.background");
                graphics.setColor(surface == null ? UIManager.getColor("Panel.background") : surface);
                graphics.fillRect(0, 0, getWidth(), getHeight());
                choices.paint(graphics);
            }
        };
        popupPreview.setBounds(choices.getBounds());
        JLayeredPane menuPreview = new JLayeredPane();
        menuPreview.add(content, JLayeredPane.DEFAULT_LAYER);
        menuPreview.add(popupPreview, JLayeredPane.POPUP_LAYER);
        render(menuPreview, name, width, height);
    }

    static void renderFeatured(String theme) throws Exception {
        render(ExplorerPreview.create(config(DatabaseType.MYSQL), false), theme + "-side-panel-340", 340, 620);
        connection(theme, "mysql", 740);
        render(console(), theme + "-sql-console-1100", 1100, 620);
        TableDataEditorPanel editor = table(false);
        render(editor, theme + "-table-1100", 1100, 620);
        Object model = field(editor, "tableModel");
        ((TableModel)model).setValueAt("Amelia Stone", 0, 1);
        invoke(model, "addNewRow", new Class<?>[]{});
        int newRow = ((TableModel)model).getRowCount() - 1;
        ((TableModel)model).setValueAt("Grace Lee", newRow, 1);
        ((TableModel)model).setValueAt("grace@example.com", newRow, 2);
        invoke(editor, "updatePendingChangesState", new Class<?>[]{});
        if (!((JButton)field(editor, "saveBtn")).isEnabled())
            throw new AssertionError("Featured new-row preview must be valid: " + field(model, "validationErrors"));
        render(editor, theme + "-table-new-row-1100", 1100, 620);
    }

    static void renderSourceFilter(String theme) throws Exception {
        render(ExplorerPreview.create(config(DatabaseType.MYSQL), false), theme + "-side-panel-340", 340, 620);
        render(ExplorerPreview.viewOnly(config(DatabaseType.MYSQL), true), theme + "-side-panel-view-only-picker-340", 340, 620);
        render(ExplorerPreview.viewOnly(config(DatabaseType.MYSQL), false), theme + "-side-panel-view-only-mysql-340", 340, 620);
    }

    static void renderExplorerSearch(String theme) throws Exception {
        render(ExplorerPreview.search(config(DatabaseType.MYSQL), false), theme + "-explorer-search-all-340", 340, 700);
        render(ExplorerPreview.search(config(DatabaseType.MYSQL), true), theme + "-explorer-search-focused-340", 340, 700);
        render(ExplorerPreview.search(config(DatabaseType.MYSQL), true), theme + "-explorer-search-focused-1100", 1100, 620);
    }

    static void renderTableTabIcons(String theme) throws Exception {
        JPanel preview = new JPanel(new BorderLayout(0, 18));
        preview.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JTabbedPane tabs = new JTabbedPane();
        for (DatabaseType type : DatabaseType.values()) {
            var config = config(type);
            var file = new com.segfault03.ideadb.editor.TableDataVirtualFile(config, "shop",
                    new TableMetadata("shop", "public", "customers", "TABLE"));
            Icon icon = new com.segfault03.ideadb.editor.DatabaseTableIconProvider().getIcon(file, 0, null);
            verifyTableTabBadge(type, icon);
            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(BorderFactory.createEmptyBorder(20, 16, 16, 16));
            content.add(new JLabel("customers — " + type.getDisplayName()), BorderLayout.NORTH);
            tabs.addTab("customers [" + type.getDisplayName() + "]", icon, content);
        }
        preview.add(tabs, BorderLayout.CENTER);
        JPanel detail = new JPanel(new GridLayout(1, DatabaseType.values().length, 20, 0));
        for (DatabaseType type : DatabaseType.values()) {
            Icon icon = com.segfault03.ideadb.ui.Icons.tableWithDataSource(type);
            JPanel item = new JPanel(new BorderLayout(0, 10));
            item.add(new JLabel(type.getDisplayName(), SwingConstants.CENTER), BorderLayout.NORTH);
            Icon enlarged = new Icon() {
                public int getIconWidth() { return icon.getIconWidth() * 4; }
                public int getIconHeight() { return icon.getIconHeight() * 4; }
                public void paintIcon(Component component, Graphics graphics, int x, int y) {
                    Graphics2D copy = (Graphics2D) graphics.create();
                    copy.translate(x, y);
                    copy.scale(4, 4);
                    icon.paintIcon(component, copy, 0, 0);
                    copy.dispose();
                }
            };
            item.add(new JLabel(enlarged, SwingConstants.CENTER), BorderLayout.CENTER);
            detail.add(item);
        }
        preview.add(detail, BorderLayout.SOUTH);
        render(preview, theme + "-table-tab-icons-760", 760, 300);
    }

    static void verifyTableTabBadge(DatabaseType type, Icon icon) {
        if (icon.getIconWidth() != 16 || icon.getIconHeight() != 16)
            throw new AssertionError("Table tab icon must remain 16 x 16: " + type);
        BufferedImage pixels = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = pixels.createGraphics();
        icon.paintIcon(new JLabel(), graphics, 0, 0);
        graphics.dispose();
        int expected = switch (type) {
            case MYSQL -> 0x00758F;
            case HSQLDB -> 0x7256A8;
            case POSTGRESQL -> 0x4169E1;
            case H2 -> 0x2471A3;
        };
        int filled = 0;
        for (int y = 6; y < 16; y++) for (int x = 0; x < 10; x++) {
            int argb = pixels.getRGB(x, y);
            if (x >= 2 && x < 8 && y >= 8 && y < 14 && (argb >>> 24) != 255)
                throw new AssertionError("Source badge must hide the grid at " + x + "," + y + ": " + type);
            if ((argb & 0xffffff) == expected) filled++;
        }
        if (filled < 10) throw new AssertionError("Missing solid bottom-left source badge: " + type);
    }

    private static void initializePreviewApplication() throws Exception {
        com.intellij.testFramework.common.TestApplicationKt.loadApp();
        com.intellij.openapi.extensions.PluginDescriptor descriptor = (com.intellij.openapi.extensions.PluginDescriptor)
                Proxy.newProxyInstance(com.intellij.openapi.extensions.PluginDescriptor.class.getClassLoader(),
                        new Class<?>[]{com.intellij.openapi.extensions.PluginDescriptor.class}, (proxy, method, values) -> {
                            return switch (method.getName()) {
                                case "getPluginId" -> com.intellij.openapi.extensions.PluginId.getId("com.segfault03.lattice.preview");
                                case "getName" -> "Lattice preview";
                                case "getVersion" -> "preview";
                                case "getClassLoader" -> UiPreview.class.getClassLoader();
                                case "getPluginPath" -> Path.of(".");
                                case "isEnabled", "isBundled", "isImplementationDetail", "allowBundledUpdate" -> false;
                                case "getDescription", "getVendor", "getSinceBuild", "getUntilBuild" -> "";
                                case "toString" -> "Lattice preview descriptor";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == values[0];
                                default -> null;
                            };
                        });
        Object application = com.intellij.openapi.application.ApplicationManager.getApplication();
        registerPreviewService(application, descriptor, com.segfault03.ideadb.service.DatabaseConnectionManager.class,
                new com.segfault03.ideadb.service.DatabaseConnectionManager());
        registerPreviewService(application, descriptor, com.segfault03.ideadb.service.DriverRegistry.class,
                new com.segfault03.ideadb.service.DriverRegistry());
    }

    private static void registerPreviewService(Object application,
                                               com.intellij.openapi.extensions.PluginDescriptor descriptor,
                                               Class<?> serviceClass, Object service) throws Exception {
        application.getClass().getMethod("registerServiceInstance", Class.class, Object.class,
                        com.intellij.openapi.extensions.PluginDescriptor.class)
                .invoke(application, serviceClass, service, descriptor);
    }

    public static void main(String[] args) throws Exception {
        int exitCode = 0;
        try {
            initializePreviewApplication();
            output = Path.of(args[0]);
            Files.createDirectories(output);
            String theme = args[1];
            boolean sourceFilterOnly = args.length >= 3 && args[2].equals("source-filter");
            boolean searchOnly = args.length >= 3 && args[2].equals("search");
            boolean tabIconsOnly = args.length >= 3 && args[2].equals("tab-icons");
            featuredOnly = !sourceFilterOnly && !searchOnly && !tabIconsOnly && (args.length < 3 || !args[2].equals("all"));
            UIManager.setLookAndFeel(theme.equals("dark") ? new FlatDarculaLaf() : new FlatIntelliJLaf());
            Font font = new javax.swing.plaf.FontUIResource(previewFont());
            UIManager.put("defaultFont", font);
            for (String key : List.of("Label.font", "Button.font", "ToggleButton.font", "CheckBox.font",
                    "RadioButton.font", "ComboBox.font", "TextField.font", "PasswordField.font", "TextArea.font",
                    "Table.font", "TableHeader.font", "Menu.font", "MenuItem.font", "TabbedPane.font",
                    "TitledBorder.font")) UIManager.put(key, font);
            JBColor.setDark(theme.equals("dark"));
            com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
            IconLoader.activate();
            IconLoader.setUseDarkIcons(theme.equals("dark"));
            if (sourceFilterOnly || searchOnly || tabIconsOnly) {
                SwingUtilities.invokeAndWait(() -> {
                    try { if (tabIconsOnly) renderTableTabIcons(theme); else if (searchOnly) renderExplorerSearch(theme); else renderSourceFilter(theme); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                });
                Files.write(output.resolve(theme + "-manifest.txt"), snapshots);
                return;
            }
            if (featuredOnly) {
                SwingUtilities.invokeAndWait(() -> {
                    try { renderFeatured(theme); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                });
                Files.write(output.resolve(theme + "-manifest.txt"), snapshots);
                return;
            }
            SwingUtilities.invokeAndWait(() -> {
                try {
                inputPreviews(theme);
                render(welcome(false), theme + "-welcome-900", 900, 680);
                render(welcome(false), theme + "-welcome-520", 520, 680);
                render(welcome(true), theme + "-welcome-connected-900", 900, 680);
                verifyWelcome();
                renderSourceFilter(theme);
                renderExplorerSearch(theme);
                renderTableTabIcons(theme);
                render(ExplorerPreview.create(config(DatabaseType.MYSQL), true), theme + "-side-panel-empty-340", 340, 620);
                connectionMenuPreview(ExplorerPreview.create(config(DatabaseType.MYSQL), true),
                        "Add a connection…", theme + "-side-panel-connection-menu-340", 340, 620);
                connectionMenuPreview(welcome(false), "Add connection…",
                        theme + "-welcome-connection-menu-900", 900, 680);
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
                for (String variant : List.of("mysql", "new", "postgresql", "success", "failed", "hsql-server", "hsql-memory", "hsql-file", "jdbc-url", "driver-download", "driver-retained", "driver-retained-hsqldb", "driver-local", "mysql-bundled-expanded", "hsql-bundled-expanded", "hsql-memory-bundled-expanded", "hsql-file-bundled-expanded", "mysql-collapsed-after-expansion", "mysql-incomplete", "hsql-incomplete", "jdbc-url-incomplete")) connection(theme, variant, 740);
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
        } catch (Throwable failure) {
            failure.printStackTrace();
            exitCode = 1;
        } finally {
            System.exit(exitCode); // Platform timers otherwise keep this screenshot process alive.
        }
    }
}
