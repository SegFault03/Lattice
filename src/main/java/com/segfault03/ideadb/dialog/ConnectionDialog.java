package com.segfault03.ideadb.dialog;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.ConnectionTestResult;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.HsqlMode;
import com.segfault03.ideadb.model.DriverSource;
import com.segfault03.ideadb.service.DriverCatalog;
import com.intellij.openapi.ui.ValidationInfo;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;

public class ConnectionDialog extends DialogWrapper {
    private final ConnectionConfig config;
    private JComboBox<DriverSource> driverSourceCombo;
    private JComboBox<String> driverVersionCombo;
    private TextFieldWithBrowseButton driverJarField;
    private JPanel driverCards;
    private final CardLayout driverLayout = new CardLayout();
    private final JButton downloadDriverButton = new JButton("Download");
    private final JButton listVersionsButton = new JButton("More versions");
    private final JBLabel driverStatusLabel = new JBLabel(" ");
    private java.util.concurrent.Future<?> driverTask;
    private java.util.concurrent.Future<?> connectionTask;
    private boolean driverBusy;

    // Header fields
    private JBTextField nameField;
    private JComboBox<DatabaseType> typeCombo;
    private JRadioButton standardRadio;
    private JRadioButton customUrlRadio;

    // Main Card switcher (Standard vs JDBC URL)
    private JPanel mainCardPanel;
    private CardLayout mainCardLayout;

    // Standard sub-card switcher (MySQL vs HSQLDB)
    private JPanel dbTypeCardPanel;
    private CardLayout dbTypeCardLayout;

    // MySQL fields
    private JBTextField mysqlHostField;
    private JBTextField mysqlPortField;
    private JBTextField mysqlDatabaseField;
    private JBTextField mysqlUserField;
    private JPasswordField mysqlPasswordField;

    // HSQLDB fields
    private JComboBox<HsqlMode> hsqlModeCombo;
    private TextFieldWithBrowseButton hsqlFileField;
    private JBTextField hsqlMemNameField;
    private JBTextField hsqlServerHostField;
    private JBTextField hsqlServerPortField;
    private JBTextField hsqlServerDbField;
    private JBTextField hsqlUserField;
    private JPasswordField hsqlPasswordField;
    private JPanel hsqlSubCardPanel;
    private CardLayout hsqlSubCardLayout;

    // Custom JDBC URL fields
    private JBTextField customUrlField;
    private JBTextField customUrlUserField;
    private JPasswordField customUrlPasswordField;

    // Preview and Action components
    private JBLabel urlPreviewLabel;
    private final JButton testButton = new JButton("Test Connection");
    private final JBLabel testStatusLabel = new JBLabel("");

    public ConnectionDialog(@Nullable Project project, ConnectionConfig config) {
        super(project, true);
        this.config = config.copy();
        setTitle(config.getName().isEmpty() || config.getName().equals("New Connection")
                ? "New Database Connection" : "Edit Database Connection");
        init();
        loadValues();
        updatePreview();
    }

    public ConnectionConfig getResultConfig() {
        saveValues();
        return config;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));

        ConnectionFormPanel topPanel = new ConnectionFormPanel();
        nameField = new JBTextField("", 26);
        topPanel.addRow(0, "Name:", nameField, true);

        typeCombo = ConnectionFormPanel.width(new JComboBox<>(DatabaseType.values()), 220);
        topPanel.addRow(1, "Database Type:", typeCombo, false);

        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        standardRadio = new JRadioButton("Standard (Host & Port)", true);
        customUrlRadio = new JRadioButton("Custom JDBC URL", false);
        ButtonGroup group = new ButtonGroup();
        group.add(standardRadio);
        group.add(customUrlRadio);
        radioPanel.add(standardRadio);
        radioPanel.add(Box.createHorizontalStrut(JBUI.scale(12)));
        radioPanel.add(customUrlRadio);
        topPanel.addRow(2, "Connect via:", radioPanel, false);

        driverSourceCombo = ConnectionFormPanel.width(new JComboBox<>(DriverSource.values()), 220);
        topPanel.addRow(3, "JDBC driver:", driverSourceCombo, false);
        driverCards = new JPanel(driverLayout);
        driverCards.add(new JBLabel("MySQL 9.0.0 / HSQLDB 2.7.3 included"), DriverSource.BUNDLED.name());
        JPanel versionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        driverVersionCombo = new JComboBox<>();
        driverVersionCombo.setEditable(true);
        ConnectionFormPanel.width(driverVersionCombo, 120);
        versionPanel.add(driverVersionCombo);
        versionPanel.add(listVersionsButton);
        versionPanel.add(downloadDriverButton);
        driverCards.add(versionPanel, DriverSource.DOWNLOAD.name());
        driverJarField = new TextFieldWithBrowseButton();
        driverJarField.getTextField().setColumns(24);
        driverJarField.setToolTipText("Choose the driver matching the server or HSQLDB file format.");
        driverJarField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("jar").withTitle("Select JDBC Driver JAR"));
        driverCards.add(driverJarField, DriverSource.LOCAL_JAR.name());
        topPanel.addRow(4, "", driverCards, true);
        topPanel.addRow(5, "", driverStatusLabel, false);
        driverSourceCombo.addActionListener(e -> { driverLayout.show(driverCards, ((DriverSource)driverSourceCombo.getSelectedItem()).name()); updateDriverStatus(); });
        driverVersionCombo.addActionListener(e -> updateDriverStatus());
        downloadDriverButton.addActionListener(e -> runDriverAction(false));
        listVersionsButton.addActionListener(e -> runDriverAction(true));
        refillDriverVersions();
        root.add(topPanel, BorderLayout.NORTH);

        // 2. Center Section: Switchable Cards (Standard vs JDBC URL)
        mainCardLayout = new CardLayout();
        mainCardPanel = new JPanel(mainCardLayout);

        // Standard Mode Card (contains MySQL or HSQLDB views)
        JPanel standardCard = new JPanel(new BorderLayout());
        dbTypeCardLayout = new CardLayout();
        dbTypeCardPanel = new JPanel(dbTypeCardLayout);
        dbTypeCardPanel.add(createMysqlPanel(), DatabaseType.MYSQL.name());
        dbTypeCardPanel.add(createHsqlPanel(), DatabaseType.HSQLDB.name());
        standardCard.add(dbTypeCardPanel, BorderLayout.CENTER);

        // Custom JDBC URL Card
        JPanel customUrlCard = createCustomUrlPanel();

        mainCardPanel.add(standardCard, "STANDARD");
        mainCardPanel.add(customUrlCard, "JDBC_URL");

        root.add(mainCardPanel, BorderLayout.CENTER);

        // 3. Bottom Preview line (compact & clean)
        JPanel previewPanel = new JPanel(new BorderLayout(8, 0));
        previewPanel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        JBLabel prefixLabel = new JBLabel("Resolved URL:");
        prefixLabel.setForeground(JBColor.GRAY);
        previewPanel.add(prefixLabel, BorderLayout.WEST);

        urlPreviewLabel = new JBLabel();
        urlPreviewLabel.setForeground(JBColor.GRAY);
        urlPreviewLabel.setFont(urlPreviewLabel.getFont().deriveFont(Font.PLAIN, 12f));
        previewPanel.add(urlPreviewLabel, BorderLayout.CENTER);

        root.add(previewPanel, BorderLayout.SOUTH);

        setupListeners();
        Dimension preferred = root.getPreferredSize();
        preferred.width = Math.max(JBUI.scale(600), preferred.width);
        root.setPreferredSize(preferred);
        root.setMinimumSize(new Dimension(JBUI.scale(600), root.getMinimumSize().height));
        return root;
    }

    @Override
    protected JComponent createSouthPanel() {
        JPanel south = new JPanel(new BorderLayout(10, 0));
        south.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        testStatusLabel.setFont(testStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        left.add(testButton);
        left.add(testStatusLabel);

        testButton.addActionListener(e -> doTestConnection());

        JComponent defaultButtons = super.createSouthPanel();

        south.add(left, BorderLayout.WEST);
        if (defaultButtons != null) {
            south.add(defaultButtons, BorderLayout.EAST);
        }
        return south;
    }

    private JPanel createMysqlPanel() {
        ConnectionFormPanel form = new ConnectionFormPanel();
        mysqlHostField = new JBTextField("localhost", 20);
        mysqlPortField = new JBTextField("3306", 6);
        form.addRow(0, "Host:", ConnectionFormPanel.hostAndPort(mysqlHostField, mysqlPortField), true);
        mysqlDatabaseField = ConnectionFormPanel.width(new JBTextField(), 280);
        form.addRow(1, "Database:", mysqlDatabaseField, false);
        mysqlUserField = ConnectionFormPanel.width(new JBTextField(), 220);
        form.addRow(2, "User:", mysqlUserField, false);
        mysqlPasswordField = ConnectionFormPanel.width(new JPasswordField(), 220);
        form.addRow(3, "Password:", mysqlPasswordField, false);
        return form;
    }

    private JPanel createHsqlPanel() {
        ConnectionFormPanel form = new ConnectionFormPanel();
        hsqlModeCombo = ConnectionFormPanel.width(new JComboBox<>(HsqlMode.values()), 280);
        hsqlModeCombo.setSelectedItem(HsqlMode.SERVER);
        form.addRow(0, "Mode:", hsqlModeCombo, false);

        hsqlSubCardLayout = new CardLayout();
        hsqlSubCardPanel = new JPanel(hsqlSubCardLayout);
        ConnectionFormPanel serverForm = new ConnectionFormPanel();
        hsqlServerHostField = new JBTextField("localhost", 20);
        hsqlServerPortField = new JBTextField("9001", 6);
        serverForm.addRow(0, "Host:", ConnectionFormPanel.hostAndPort(hsqlServerHostField, hsqlServerPortField), true);
        hsqlServerDbField = ConnectionFormPanel.width(new JBTextField(), 280);
        serverForm.addRow(1, "Database:", hsqlServerDbField, false);
        hsqlSubCardPanel.add(serverForm, HsqlMode.SERVER.name());

        ConnectionFormPanel fileForm = new ConnectionFormPanel();
        hsqlFileField = new TextFieldWithBrowseButton();
        hsqlFileField.getTextField().setColumns(24);
        hsqlFileField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Select Database File"));
        fileForm.addRow(0, "File / Path:", hsqlFileField, true);
        hsqlSubCardPanel.add(fileForm, HsqlMode.FILE.name());

        ConnectionFormPanel memoryForm = new ConnectionFormPanel();
        hsqlMemNameField = ConnectionFormPanel.width(new JBTextField(), 280);
        memoryForm.addRow(0, "Database Name:", hsqlMemNameField, false);
        hsqlSubCardPanel.add(memoryForm, HsqlMode.MEM.name());
        form.addFullWidthRow(1, hsqlSubCardPanel);

        hsqlUserField = ConnectionFormPanel.width(new JBTextField(), 220);
        form.addRow(2, "User:", hsqlUserField, false);
        hsqlPasswordField = ConnectionFormPanel.width(new JPasswordField(), 220);
        form.addRow(3, "Password:", hsqlPasswordField, false);
        return form;
    }

    private JPanel createCustomUrlPanel() {
        ConnectionFormPanel form = new ConnectionFormPanel();
        customUrlField = new JBTextField("", 36);
        form.addRow(0, "JDBC URL:", customUrlField, true);
        customUrlUserField = ConnectionFormPanel.width(new JBTextField(), 220);
        form.addRow(1, "User:", customUrlUserField, false);
        customUrlPasswordField = ConnectionFormPanel.width(new JPasswordField(), 220);
        form.addRow(2, "Password:", customUrlPasswordField, false);
        JBLabel noteLabel = new JBLabel("<html><body width='360'>Enter the full JDBC connection string. Credentials entered above will be passed to the driver.</body></html>");
        noteLabel.setForeground(JBColor.GRAY);
        form.addRow(3, "", noteLabel, false);
        return form;
    }

    private void setupListeners() {
        // Toggle between Standard and Custom JDBC URL modes
        standardRadio.addActionListener(e -> {
            mainCardLayout.show(mainCardPanel, "STANDARD");
            updatePreview();
        });

        customUrlRadio.addActionListener(e -> {
            mainCardLayout.show(mainCardPanel, "JDBC_URL");
            updatePreview();
        });

        // Database Type Switcher
        typeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
                dbTypeCardLayout.show(dbTypeCardPanel, type.name());

                // Smart default adaptations
                if (type == DatabaseType.MYSQL) {
                    if (mysqlPortField.getText().trim().equals("9001") || mysqlPortField.getText().trim().isEmpty()) {
                        mysqlPortField.setText("3306");
                    }
                } else if (type == DatabaseType.HSQLDB) {
                    if (hsqlServerPortField.getText().trim().equals("3306") || hsqlServerPortField.getText().trim().isEmpty()) {
                        hsqlServerPortField.setText("9001");
                    }
                }
                refillDriverVersions();
                updatePreview();
            }
        });

        // HSQL Mode Switcher
        hsqlModeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                HsqlMode mode = (HsqlMode) hsqlModeCombo.getSelectedItem();
                hsqlSubCardLayout.show(hsqlSubCardPanel, mode.name());
                updatePreview();
            }
        });

        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updatePreview(); }
            public void removeUpdate(DocumentEvent e) { updatePreview(); }
            public void changedUpdate(DocumentEvent e) { updatePreview(); }
        };

        mysqlHostField.getDocument().addDocumentListener(dl);
        mysqlPortField.getDocument().addDocumentListener(dl);
        mysqlDatabaseField.getDocument().addDocumentListener(dl);
        mysqlUserField.getDocument().addDocumentListener(dl);

        hsqlMemNameField.getDocument().addDocumentListener(dl);
        hsqlFileField.getTextField().getDocument().addDocumentListener(dl);
        hsqlServerHostField.getDocument().addDocumentListener(dl);
        hsqlServerPortField.getDocument().addDocumentListener(dl);
        hsqlServerDbField.getDocument().addDocumentListener(dl);
        hsqlUserField.getDocument().addDocumentListener(dl);

        // Auto-detect type when typing custom JDBC URL
        customUrlField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { checkUrlPrefix(); updatePreview(); }
            public void removeUpdate(DocumentEvent e) { checkUrlPrefix(); updatePreview(); }
            public void changedUpdate(DocumentEvent e) { checkUrlPrefix(); updatePreview(); }

            private void checkUrlPrefix() {
                String u = customUrlField.getText().trim().toLowerCase(java.util.Locale.ROOT);
                if (u.startsWith("jdbc:hsqldb:") && typeCombo.getSelectedItem() != DatabaseType.HSQLDB) {
                    typeCombo.setSelectedItem(DatabaseType.HSQLDB);
                } else if (u.startsWith("jdbc:mysql:") && typeCombo.getSelectedItem() != DatabaseType.MYSQL) {
                    typeCombo.setSelectedItem(DatabaseType.MYSQL);
                }
            }
        });

        customUrlUserField.getDocument().addDocumentListener(dl);
    }

    private void updatePreview() {
        if (customUrlRadio.isSelected() && customUrlField.getText().trim().isEmpty()) {
            urlPreviewLabel.setText("Enter a JDBC URL");
            urlPreviewLabel.setToolTipText(null);
            return;
        }
        ConnectionConfig temp = createTempConfig();
        String resolvedUrl = temp.buildJdbcUrl();
        urlPreviewLabel.setText(resolvedUrl.length() <= 44 ? resolvedUrl : resolvedUrl.substring(0, 41) + "...");
        urlPreviewLabel.setToolTipText(resolvedUrl);
    }

    private void setDriverStatus(String message) {
        message = java.util.Objects.requireNonNullElse(message, " ");
        driverStatusLabel.setText("<html><body width='300'>" + escapeHtml(message) + "</body></html>");
        driverStatusLabel.setToolTipText(message);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private ConnectionConfig createTempConfig() {
        ConnectionConfig c = new ConnectionConfig();
        c.setId(config.getId());
        c.setName(nameField != null ? nameField.getText().trim() : config.getName());
        DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
        c.setType(type);
        c.setDriverSource((DriverSource)driverSourceCombo.getSelectedItem());
        c.setDriverVersion(String.valueOf(driverVersionCombo.getEditor().getItem()).trim());
        c.setDriverJarPath(driverJarField.getText().trim());

        if (customUrlRadio.isSelected()) {
            c.setCustomUrl(customUrlField.getText().trim());
            c.setUser(customUrlUserField.getText().trim());
            c.setPassword(new String(customUrlPasswordField.getPassword()));
            c.setHost("localhost");
            c.setPort(type == DatabaseType.MYSQL ? 3306 : 9001);
        } else {
            c.setCustomUrl("");
            if (type == DatabaseType.MYSQL) {
                c.setHost(mysqlHostField.getText().trim());
                try {
                    c.setPort(Integer.parseInt(mysqlPortField.getText().trim()));
                } catch (Exception ignored) {
                    c.setPort(3306);
                }
                c.setDatabaseName(mysqlDatabaseField.getText().trim());
                c.setUser(mysqlUserField.getText().trim());
                c.setPassword(new String(mysqlPasswordField.getPassword()));
            } else {
                HsqlMode mode = (HsqlMode) hsqlModeCombo.getSelectedItem();
                c.setHsqlMode(mode);
                if (mode == HsqlMode.MEM) {
                    c.setDatabaseName(hsqlMemNameField.getText().trim());
                } else if (mode == HsqlMode.FILE) {
                    c.setDatabaseName(hsqlFileField.getText().trim());
                } else {
                    c.setHost(hsqlServerHostField.getText().trim());
                    try {
                        c.setPort(Integer.parseInt(hsqlServerPortField.getText().trim()));
                    } catch (Exception ignored) {
                        c.setPort(9001);
                    }
                    c.setDatabaseName(hsqlServerDbField.getText().trim());
                }
                c.setUser(hsqlUserField.getText().trim());
                c.setPassword(new String(hsqlPasswordField.getPassword()));
            }
        }
        return c;
    }

    private void refillDriverVersions() {
        driverVersionCombo.removeAllItems();
        for (String version : DriverCatalog.suggestedVersions((DatabaseType)typeCombo.getSelectedItem())) driverVersionCombo.addItem(version);
        updateDriverStatus();
    }
    private void updateDriverStatus() {
        if (driverBusy || driverSourceCombo == null) return;
        DriverSource source = (DriverSource)driverSourceCombo.getSelectedItem();
        if (source == DriverSource.DOWNLOAD) {
            try {
                var path = DriverCatalog.downloadedJar((DatabaseType)typeCombo.getSelectedItem(), String.valueOf(driverVersionCombo.getEditor().getItem()).trim());
                setDriverStatus(java.nio.file.Files.isRegularFile(path) ? "Downloaded and ready" : "Choose a version, then Download. You can also enter a release version.");
            } catch (IllegalArgumentException error) { setDriverStatus(error.getMessage()); }
        } else setDriverStatus(source == DriverSource.LOCAL_JAR ? "Select the driver JAR matching your database server." : "Bundled drivers are ready to use.");
    }
    private void runDriverAction(boolean listOnly) {
        if (driverBusy || !testButton.isEnabled() || isDisposed()) return;
        DatabaseType type = (DatabaseType)typeCombo.getSelectedItem();
        String version = String.valueOf(driverVersionCombo.getEditor().getItem()).trim();
        driverBusy = true; downloadDriverButton.setEnabled(false); listVersionsButton.setEnabled(false);
        driverSourceCombo.setEnabled(false); driverVersionCombo.setEnabled(false); typeCombo.setEnabled(false);
        testButton.setEnabled(false); setOKActionEnabled(false);
        setDriverStatus(listOnly ? "Loading versions from Maven Central..." : "Downloading and verifying " + version + "...");
        driverTask = com.segfault03.ideadb.service.DatabaseTaskService.getInstance().submit(() -> {
            String error = null; java.util.List<String> versions = null;
            try { if (listOnly) versions = DriverCatalog.availableVersions(type); else DriverCatalog.download(type, version); }
            catch (Exception failure) { error = failure.getMessage(); }
            String failure = error; java.util.List<String> available = versions;
            SwingUtilities.invokeLater(() -> {
                if (isDisposed()) return;
                driverBusy = false; downloadDriverButton.setEnabled(true); listVersionsButton.setEnabled(true);
                driverSourceCombo.setEnabled(true); driverVersionCombo.setEnabled(true); typeCombo.setEnabled(true);
                testButton.setEnabled(true); setOKActionEnabled(true);
                if (available != null) {
                    driverVersionCombo.removeAllItems(); available.forEach(driverVersionCombo::addItem); driverVersionCombo.setSelectedItem(version);
                }
                updateDriverStatus();
                if (failure != null) { setDriverStatus("Download failed; use a local JAR or retry."); Messages.showErrorDialog(failure, "JDBC Driver"); }
            });
        });
    }
    @Override protected @Nullable ValidationInfo doValidate() {
        if (driverBusy) return new ValidationInfo("Wait for the driver download to finish", driverSourceCombo);
        if (customUrlRadio.isSelected() && customUrlField.getText().trim().isEmpty()) {
            return new ValidationInfo("Enter a JDBC URL", customUrlField);
        }
        ConnectionConfig candidate = createTempConfig();
        try {
            if (candidate.getDriverSource() == DriverSource.DOWNLOAD) {
                var downloaded = DriverCatalog.downloadedJar(candidate.getType(), candidate.getDriverVersion());
                if (!java.nio.file.Files.isRegularFile(downloaded)) return new ValidationInfo("Download the selected driver first", driverVersionCombo);
                DriverCatalog.validateJar(candidate.getType(), downloaded);
            } else if (candidate.getDriverSource() == DriverSource.LOCAL_JAR) {
                DriverCatalog.validateJar(candidate.getType(), java.nio.file.Path.of(candidate.getDriverJarPath()));
            }
        } catch (Exception error) { return new ValidationInfo(error.getMessage(), driverSourceCombo); }
        return null;
    }
    @Override protected void dispose() {
        if (driverTask != null) driverTask.cancel(true);
        if (connectionTask != null) connectionTask.cancel(true);
        super.dispose();
    }

    private static boolean sameRequest(ConnectionConfig left, ConnectionConfig right) {
        return left.buildJdbcUrl().equals(right.buildJdbcUrl()) && left.getType() == right.getType()
                && java.util.Objects.equals(left.getUser(), right.getUser()) && java.util.Objects.equals(left.getPassword(), right.getPassword())
                && left.getDriverSource() == right.getDriverSource() && left.getDriverVersion().equals(right.getDriverVersion())
                && left.getDriverJarPath().equals(right.getDriverJarPath());
    }

    private void doTestConnection() {
        if (driverBusy || !testButton.isEnabled() || isDisposed()) return;
        if (customUrlRadio.isSelected() && customUrlField.getText().trim().isEmpty()) {
            Messages.showErrorDialog("Enter a JDBC URL before testing the connection.", "Invalid Connection");
            return;
        }
        ConnectionConfig temp = createTempConfig();
        DatabaseType originalType = temp.getType();
        ConnectionConfig requested = temp.copy();
        testStatusLabel.setText("Connecting...");
        testStatusLabel.setForeground(JBColor.GRAY);
        testButton.setEnabled(false); downloadDriverButton.setEnabled(false); listVersionsButton.setEnabled(false);

        connectionTask = com.segfault03.ideadb.service.DatabaseTaskService.getInstance().submit(() -> {
                if(isDisposed()) return;
                ConnectionTestResult result = DatabaseConnectionManager.getInstance().testConnection(temp);

                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    testButton.setEnabled(true); downloadDriverButton.setEnabled(true); listVersionsButton.setEnabled(true);
                    if (!sameRequest(requested, createTempConfig())) {
                        testStatusLabel.setText("Settings changed; test again"); return;
                    }
                    if (result.isSuccess()) {
                        // If the backend detected a database type mismatch (e.g. MySQL port was running HSQLDB)
                        if (temp.getType() != originalType) {
                            typeCombo.setSelectedItem(temp.getType());
                            if (temp.getType() == DatabaseType.HSQLDB) {
                                hsqlModeCombo.setSelectedItem(HsqlMode.SERVER);
                                hsqlSubCardLayout.show(hsqlSubCardPanel, HsqlMode.SERVER.name());
                                hsqlServerHostField.setText(temp.getHost());
                                hsqlServerPortField.setText(String.valueOf(temp.getPort()));
                                hsqlServerDbField.setText(temp.getDatabaseName());
                                hsqlUserField.setText(temp.getUser());
                            }
                            updatePreview();
                        }
                        testStatusLabel.setText(result.getDatabaseProductName() + " " + result.getDatabaseProductVersion());
                        testStatusLabel.setToolTipText("Driver: " + result.getDriverName() + " " + result.getDriverVersion() + "; " + result.getResponseTimeMs() + " ms");
                        testStatusLabel.setForeground(new JBColor(new Color(40, 160, 80), new Color(98, 181, 67)));
                        Messages.showInfoMessage(result.getSummaryMessage(), "Connection Successful");
                    } else {
                        testStatusLabel.setText("Failed!");
                        testStatusLabel.setForeground(new JBColor(new Color(200, 40, 40), new Color(255, 107, 107)));
                        Messages.showErrorDialog(result.getSummaryMessage(), "Connection Failed");
                    }
                });
        });
    }

    private void loadValues() {
        nameField.setText(config.getName());
        typeCombo.setSelectedItem(config.getType());
        dbTypeCardLayout.show(dbTypeCardPanel, config.getType().name());

        if (config.getType() == DatabaseType.MYSQL) {
            mysqlHostField.setText(config.getHost());
            mysqlPortField.setText(String.valueOf(config.getPort()));
            mysqlDatabaseField.setText(config.getDatabaseName());
            mysqlUserField.setText(config.getUser());
            mysqlPasswordField.setText(config.getPassword());
        } else {
            HsqlMode mode = config.getHsqlMode() != null ? config.getHsqlMode() : HsqlMode.SERVER;
            hsqlModeCombo.setSelectedItem(mode);
            hsqlSubCardLayout.show(hsqlSubCardPanel, mode.name());
            if (mode == HsqlMode.FILE) {
                hsqlFileField.setText(config.getDatabaseName());
            } else if (mode == HsqlMode.MEM) {
                hsqlMemNameField.setText(config.getDatabaseName());
            } else {
                hsqlServerHostField.setText(config.getHost());
                hsqlServerPortField.setText(String.valueOf(config.getPort()));
                hsqlServerDbField.setText(config.getDatabaseName());
            }
            hsqlUserField.setText(config.getUser());
            hsqlPasswordField.setText(config.getPassword());
        }

        if (!config.getDriverVersion().isBlank()) driverVersionCombo.setSelectedItem(config.getDriverVersion());
        driverJarField.setText(config.getDriverJarPath());
        driverSourceCombo.setSelectedItem(config.getDriverSource());
        driverLayout.show(driverCards, config.getDriverSource().name());
        updateDriverStatus();

        // Custom URL or Standard mode
        if (config.getCustomUrl() != null && !config.getCustomUrl().isEmpty()) {
            customUrlRadio.setSelected(true);
            mainCardLayout.show(mainCardPanel, "JDBC_URL");
            customUrlField.setText(config.getCustomUrl());
            customUrlUserField.setText(config.getUser());
            customUrlPasswordField.setText(config.getPassword());
        } else {
            standardRadio.setSelected(true);
            mainCardLayout.show(mainCardPanel, "STANDARD");
        }
    }

    private void saveValues() {
        ConnectionConfig temp = createTempConfig();
        config.setName(temp.getName());
        config.setType(temp.getType());
        config.setHsqlMode(temp.getHsqlMode());
        config.setHost(temp.getHost());
        config.setPort(temp.getPort());
        config.setDatabaseName(temp.getDatabaseName());
        config.setUser(temp.getUser());
        config.setPassword(temp.getPassword());
        config.setCustomUrl(temp.getCustomUrl());
        config.setDriverSource(temp.getDriverSource());
        config.setDriverVersion(temp.getDriverVersion());
        config.setDriverJarPath(temp.getDriverJarPath());
    }
}
