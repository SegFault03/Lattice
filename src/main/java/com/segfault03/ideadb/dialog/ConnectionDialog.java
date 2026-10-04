package com.segfault03.ideadb.dialog;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
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
        root.setPreferredSize(new Dimension(620, 520));

        // 1. Top Section: Name, Database Type, Connection Method
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Name
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.22;
        topPanel.add(new JBLabel("Name:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.78;
        nameField = new JBTextField();
        topPanel.add(nameField, gbc);

        // Database Type
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.22;
        topPanel.add(new JBLabel("Database Type:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.78;
        typeCombo = new JComboBox<>(DatabaseType.values());
        topPanel.add(typeCombo, gbc);

        // Connection Method Switcher (Radio Buttons)
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.22;
        topPanel.add(new JBLabel("Connect via:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.78;
        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        standardRadio = new JRadioButton("Standard (Host & Port)", true);
        customUrlRadio = new JRadioButton("Custom JDBC URL", false);
        ButtonGroup group = new ButtonGroup();
        group.add(standardRadio);
        group.add(customUrlRadio);
        radioPanel.add(standardRadio);
        radioPanel.add(customUrlRadio);
        topPanel.add(radioPanel, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.22;
        topPanel.add(new JBLabel("JDBC driver:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0.78;
        driverSourceCombo = new JComboBox<>(DriverSource.values());
        topPanel.add(driverSourceCombo, gbc);
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 1;
        driverCards = new JPanel(driverLayout);
        driverCards.add(new JBLabel("MySQL 9.0.0 / HSQLDB 2.7.3 included"), DriverSource.BUNDLED.name());
        JPanel versionPanel = new JPanel(new BorderLayout(6, 0));
        driverVersionCombo = new JComboBox<>(); driverVersionCombo.setEditable(true);
        versionPanel.add(driverVersionCombo, BorderLayout.CENTER);
        JPanel downloadActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        downloadActions.add(listVersionsButton); downloadActions.add(downloadDriverButton);
        versionPanel.add(downloadActions, BorderLayout.EAST);
        driverCards.add(versionPanel, DriverSource.DOWNLOAD.name());
        driverJarField = new TextFieldWithBrowseButton();
        driverJarField.setToolTipText("Choose the driver matching the server or HSQLDB file format.");
        driverJarField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("jar").withTitle("Select JDBC Driver JAR"));
        driverCards.add(driverJarField, DriverSource.LOCAL_JAR.name());
        topPanel.add(driverCards, gbc);
        gbc.gridy = 5; topPanel.add(driverStatusLabel, gbc);
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
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("MySQL Connection Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Host & Port
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.18;
        p.add(new JBLabel("Host:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.52;
        mysqlHostField = new JBTextField("localhost");
        p.add(mysqlHostField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.1;
        p.add(new JBLabel("Port:"), gbc);
        gbc.gridx = 3; gbc.gridy = 0; gbc.weightx = 0.2;
        mysqlPortField = new JBTextField("3306");
        p.add(mysqlPortField, gbc);

        // Database
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.18;
        p.add(new JBLabel("Database:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 3; gbc.weightx = 0.82;
        mysqlDatabaseField = new JBTextField("shop_db");
        p.add(mysqlDatabaseField, gbc);
        gbc.gridwidth = 1;

        // User & Password
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.18;
        p.add(new JBLabel("User:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.52;
        mysqlUserField = new JBTextField("root");
        p.add(mysqlUserField, gbc);

        gbc.gridx = 2; gbc.gridy = 2; gbc.weightx = 0.1;
        p.add(new JBLabel("Password:"), gbc);
        gbc.gridx = 3; gbc.gridy = 2; gbc.weightx = 0.2;
        mysqlPasswordField = new JPasswordField();
        p.add(mysqlPasswordField, gbc);

        return p;
    }

    private JPanel createHsqlPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("HSQLDB Connection Settings"));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.18;
        top.add(new JBLabel("Mode:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.82;
        hsqlModeCombo = new JComboBox<>(HsqlMode.values());
        hsqlModeCombo.setSelectedItem(HsqlMode.SERVER);
        top.add(hsqlModeCombo, gbc);

        p.add(top, BorderLayout.NORTH);

        hsqlSubCardLayout = new CardLayout();
        hsqlSubCardPanel = new JPanel(hsqlSubCardLayout);

        // Subcard 1: SERVER (Default)
        JPanel srvPanel = new JPanel(new GridBagLayout());
        GridBagConstraints sgbc = new GridBagConstraints();
        sgbc.insets = new Insets(4, 4, 4, 4);
        sgbc.fill = GridBagConstraints.HORIZONTAL;

        sgbc.gridx = 0; sgbc.gridy = 0; sgbc.weightx = 0.18;
        srvPanel.add(new JBLabel("Host:"), sgbc);
        sgbc.gridx = 1; sgbc.gridy = 0; sgbc.weightx = 0.52;
        hsqlServerHostField = new JBTextField("localhost");
        srvPanel.add(hsqlServerHostField, sgbc);

        sgbc.gridx = 2; sgbc.gridy = 0; sgbc.weightx = 0.1;
        srvPanel.add(new JBLabel("Port:"), sgbc);
        sgbc.gridx = 3; sgbc.gridy = 0; sgbc.weightx = 0.2;
        hsqlServerPortField = new JBTextField("9001");
        srvPanel.add(hsqlServerPortField, sgbc);

        sgbc.gridx = 0; sgbc.gridy = 1; sgbc.weightx = 0.18;
        srvPanel.add(new JBLabel("Database:"), sgbc);
        sgbc.gridx = 1; sgbc.gridy = 1; sgbc.gridwidth = 3; sgbc.weightx = 0.82;
        hsqlServerDbField = new JBTextField("testdb");
        srvPanel.add(hsqlServerDbField, sgbc);

        hsqlSubCardPanel.add(srvPanel, HsqlMode.SERVER.name());

        // Subcard 2: FILE
        JPanel filePanel = new JPanel(new GridBagLayout());
        GridBagConstraints fgbc = new GridBagConstraints();
        fgbc.insets = new Insets(4, 4, 4, 4);
        fgbc.fill = GridBagConstraints.HORIZONTAL;
        fgbc.gridx = 0; fgbc.gridy = 0; fgbc.weightx = 0.18;
        filePanel.add(new JBLabel("File / Path:"), fgbc);
        fgbc.gridx = 1; fgbc.gridy = 0; fgbc.weightx = 0.82;
        hsqlFileField = new TextFieldWithBrowseButton();
        hsqlFileField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Select Database File"));
        filePanel.add(hsqlFileField, fgbc);
        hsqlSubCardPanel.add(filePanel, HsqlMode.FILE.name());

        // Subcard 3: MEM
        JPanel memPanel = new JPanel(new GridBagLayout());
        GridBagConstraints mgbc = new GridBagConstraints();
        mgbc.insets = new Insets(4, 4, 4, 4);
        mgbc.fill = GridBagConstraints.HORIZONTAL;
        mgbc.gridx = 0; mgbc.gridy = 0; mgbc.weightx = 0.18;
        memPanel.add(new JBLabel("Database Name:"), mgbc);
        mgbc.gridx = 1; mgbc.gridy = 0; mgbc.weightx = 0.82;
        hsqlMemNameField = new JBTextField("testdb");
        memPanel.add(hsqlMemNameField, mgbc);
        hsqlSubCardPanel.add(memPanel, HsqlMode.MEM.name());

        p.add(hsqlSubCardPanel, BorderLayout.CENTER);

        // HSQL user/password
        JPanel authPanel = new JPanel(new GridBagLayout());
        GridBagConstraints agbc = new GridBagConstraints();
        agbc.insets = new Insets(4, 4, 4, 4);
        agbc.fill = GridBagConstraints.HORIZONTAL;

        agbc.gridx = 0; agbc.gridy = 0; agbc.weightx = 0.18;
        authPanel.add(new JBLabel("User:"), agbc);
        agbc.gridx = 1; agbc.gridy = 0; agbc.weightx = 0.52;
        hsqlUserField = new JBTextField("SA");
        authPanel.add(hsqlUserField, agbc);

        agbc.gridx = 2; agbc.gridy = 0; agbc.weightx = 0.1;
        authPanel.add(new JBLabel("Password:"), agbc);
        agbc.gridx = 3; agbc.gridy = 0; agbc.weightx = 0.2;
        hsqlPasswordField = new JPasswordField();
        authPanel.add(hsqlPasswordField, agbc);

        p.add(authPanel, BorderLayout.SOUTH);

        return p;
    }

    private JPanel createCustomUrlPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Custom JDBC URL Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // URL
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.18;
        p.add(new JBLabel("JDBC URL:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.gridwidth = 3; gbc.weightx = 0.82;
        customUrlField = new JBTextField();
        p.add(customUrlField, gbc);
        gbc.gridwidth = 1;

        // User & Password
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.18;
        p.add(new JBLabel("User:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.52;
        customUrlUserField = new JBTextField("root");
        p.add(customUrlUserField, gbc);

        gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0.1;
        p.add(new JBLabel("Password:"), gbc);
        gbc.gridx = 3; gbc.gridy = 1; gbc.weightx = 0.2;
        customUrlPasswordField = new JPasswordField();
        p.add(customUrlPasswordField, gbc);

        // Helper Note
        gbc.gridx = 1; gbc.gridy = 2; gbc.gridwidth = 3; gbc.weightx = 0.82;
        JBLabel noteLabel = new JBLabel("Enter full JDBC connection string. Credentials entered above will be passed to the driver.");
        noteLabel.setForeground(JBColor.GRAY);
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.PLAIN, 11f));
        p.add(noteLabel, gbc);

        return p;
    }

    private void setupListeners() {
        // Toggle between Standard and Custom JDBC URL modes
        standardRadio.addActionListener(e -> {
            mainCardLayout.show(mainCardPanel, "STANDARD");
            syncCredentialsToStandard();
            updatePreview();
        });

        customUrlRadio.addActionListener(e -> {
            mainCardLayout.show(mainCardPanel, "JDBC_URL");
            syncCredentialsToCustomUrl();
            if (customUrlField.getText().trim().isEmpty()) {
                ConnectionConfig temp = createTempConfig();
                customUrlField.setText(temp.buildJdbcUrl());
            }
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
                    if (mysqlUserField.getText().trim().equalsIgnoreCase("SA") || mysqlUserField.getText().trim().isEmpty()) {
                        mysqlUserField.setText("root");
                    }
                    if (mysqlDatabaseField.getText().trim().equalsIgnoreCase("testdb") || mysqlDatabaseField.getText().trim().isEmpty()) {
                        mysqlDatabaseField.setText("shop_db");
                    }
                } else if (type == DatabaseType.HSQLDB) {
                    if (hsqlServerPortField.getText().trim().equals("3306") || hsqlServerPortField.getText().trim().isEmpty()) {
                        hsqlServerPortField.setText("9001");
                    }
                    if (hsqlUserField.getText().trim().equalsIgnoreCase("root") || hsqlUserField.getText().trim().isEmpty()) {
                        hsqlUserField.setText("SA");
                    }
                    if (hsqlServerDbField.getText().trim().equalsIgnoreCase("shop_db") || hsqlServerDbField.getText().trim().isEmpty()) {
                        hsqlServerDbField.setText("testdb");
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

    private void syncCredentialsToCustomUrl() {
        DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
        if (type == DatabaseType.MYSQL) {
            customUrlUserField.setText(mysqlUserField.getText().trim());
            customUrlPasswordField.setText(new String(mysqlPasswordField.getPassword()));
        } else {
            customUrlUserField.setText(hsqlUserField.getText().trim());
            customUrlPasswordField.setText(new String(hsqlPasswordField.getPassword()));
        }
    }

    private void syncCredentialsToStandard() {
        String u = customUrlUserField.getText().trim();
        String p = new String(customUrlPasswordField.getPassword());
        mysqlUserField.setText(u);
        mysqlPasswordField.setText(p);
        hsqlUserField.setText(u);
        hsqlPasswordField.setText(p);
    }

    private void updatePreview() {
        ConnectionConfig temp = createTempConfig();
        urlPreviewLabel.setText(temp.buildJdbcUrl());
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
                driverStatusLabel.setText(java.nio.file.Files.isRegularFile(path) ? "Downloaded and ready" : "Choose a version, then Download. You can also enter a release version.");
            } catch (IllegalArgumentException error) { driverStatusLabel.setText(error.getMessage()); }
        } else driverStatusLabel.setText(source == DriverSource.LOCAL_JAR ? "Select the driver JAR matching your database server." : "Bundled drivers are ready to use.");
    }
    private void runDriverAction(boolean listOnly) {
        if (driverBusy || !testButton.isEnabled() || isDisposed()) return;
        DatabaseType type = (DatabaseType)typeCombo.getSelectedItem();
        String version = String.valueOf(driverVersionCombo.getEditor().getItem()).trim();
        driverBusy = true; downloadDriverButton.setEnabled(false); listVersionsButton.setEnabled(false);
        driverSourceCombo.setEnabled(false); driverVersionCombo.setEnabled(false); typeCombo.setEnabled(false);
        testButton.setEnabled(false); setOKActionEnabled(false);
        driverStatusLabel.setText(listOnly ? "Loading versions from Maven Central..." : "Downloading and verifying " + version + "...");
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
                if (failure != null) { driverStatusLabel.setText("Download failed; use a local JAR or retry."); Messages.showErrorDialog(failure, "JDBC Driver"); }
            });
        });
    }
    @Override protected @Nullable ValidationInfo doValidate() {
        if (driverBusy) return new ValidationInfo("Wait for the driver download to finish", driverSourceCombo);
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
            customUrlUserField.setText(config.getUser());
            customUrlPasswordField.setText(config.getPassword());
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
