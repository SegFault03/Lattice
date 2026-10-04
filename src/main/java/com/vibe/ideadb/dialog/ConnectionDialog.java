package com.vibe.ideadb.dialog;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.ConnectionTestResult;
import com.vibe.ideadb.model.DatabaseType;
import com.vibe.ideadb.model.HsqlMode;
import com.vibe.ideadb.service.DatabaseConnectionManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;

public class ConnectionDialog extends DialogWrapper {
    private final ConnectionConfig config;

    private JBTextField nameField;
    private JComboBox<DatabaseType> typeCombo;
    private JPanel dynamicPanel;
    private CardLayout cardLayout;

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

    // Custom URL & Preview
    private JBCheckBox customUrlCheck;
    private JBTextField customUrlField;
    private JBLabel urlPreviewLabel;
    private JButton testButton;
    private JBLabel testStatusLabel;

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
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setPreferredSize(new Dimension(550, 480));

        JPanel headerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.2;
        headerPanel.add(new JBLabel("Name:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.8;
        nameField = new JBTextField();
        headerPanel.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.2;
        headerPanel.add(new JBLabel("Database Type:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.8;
        typeCombo = new JComboBox<>(DatabaseType.values());
        headerPanel.add(typeCombo, gbc);

        root.add(headerPanel, BorderLayout.NORTH);

        // Center card layout for type
        cardLayout = new CardLayout();
        dynamicPanel = new JPanel(cardLayout);

        dynamicPanel.add(createMysqlPanel(), DatabaseType.MYSQL.name());
        dynamicPanel.add(createHsqlPanel(), DatabaseType.HSQLDB.name());

        root.add(dynamicPanel, BorderLayout.CENTER);

        // South: URL Preview & Test Connection
        JPanel southPanel = new JPanel(new BorderLayout(5, 5));
        southPanel.setBorder(BorderFactory.createTitledBorder("Connection String & Test"));

        JPanel previewPanel = new JPanel(new GridBagLayout());
        GridBagConstraints pgbc = new GridBagConstraints();
        pgbc.insets = new Insets(2, 4, 2, 4);
        pgbc.fill = GridBagConstraints.HORIZONTAL;

        pgbc.gridx = 0; pgbc.gridy = 0;
        customUrlCheck = new JBCheckBox("Custom JDBC URL:");
        previewPanel.add(customUrlCheck, pgbc);

        pgbc.gridx = 1; pgbc.gridy = 0; pgbc.weightx = 1.0;
        customUrlField = new JBTextField();
        customUrlField.setEnabled(false);
        previewPanel.add(customUrlField, pgbc);

        pgbc.gridx = 0; pgbc.gridy = 1; pgbc.weightx = 0.0;
        previewPanel.add(new JBLabel("Resolved URL:"), pgbc);

        pgbc.gridx = 1; pgbc.gridy = 1; pgbc.weightx = 1.0;
        urlPreviewLabel = new JBLabel();
        urlPreviewLabel.setForeground(Color.GRAY);
        previewPanel.add(urlPreviewLabel, pgbc);

        southPanel.add(previewPanel, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        testButton = new JButton("Test Connection");
        testStatusLabel = new JBLabel("");
        actionPanel.add(testButton);
        actionPanel.add(testStatusLabel);

        southPanel.add(actionPanel, BorderLayout.SOUTH);
        root.add(southPanel, BorderLayout.SOUTH);

        setupListeners();
        return root;
    }

    private JPanel createMysqlPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("MySQL Connection Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.2;
        p.add(new JBLabel("Host:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.5;
        mysqlHostField = new JBTextField("localhost");
        p.add(mysqlHostField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.1;
        p.add(new JBLabel("Port:"), gbc);
        gbc.gridx = 3; gbc.gridy = 0; gbc.weightx = 0.2;
        mysqlPortField = new JBTextField("3306");
        p.add(mysqlPortField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.2;
        p.add(new JBLabel("Database:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 3; gbc.weightx = 0.8;
        mysqlDatabaseField = new JBTextField("");
        p.add(mysqlDatabaseField, gbc);
        gbc.gridwidth = 1;

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.2;
        p.add(new JBLabel("User:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.5;
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
        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.setBorder(BorderFactory.createTitledBorder("HSQLDB Connection Settings"));

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.2;
        top.add(new JBLabel("Mode:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.8;
        hsqlModeCombo = new JComboBox<>(HsqlMode.values());
        top.add(hsqlModeCombo, gbc);

        p.add(top, BorderLayout.NORTH);

        hsqlSubCardLayout = new CardLayout();
        hsqlSubCardPanel = new JPanel(hsqlSubCardLayout);

        // Subcard 1: MEM
        JPanel memPanel = new JPanel(new GridBagLayout());
        GridBagConstraints mgbc = new GridBagConstraints();
        mgbc.insets = new Insets(4, 4, 4, 4);
        mgbc.fill = GridBagConstraints.HORIZONTAL;
        mgbc.gridx = 0; mgbc.gridy = 0; mgbc.weightx = 0.2;
        memPanel.add(new JBLabel("Database Name:"), mgbc);
        mgbc.gridx = 1; mgbc.gridy = 0; mgbc.weightx = 0.8;
        hsqlMemNameField = new JBTextField("testdb");
        memPanel.add(hsqlMemNameField, mgbc);
        hsqlSubCardPanel.add(memPanel, HsqlMode.MEM.name());

        // Subcard 2: FILE
        JPanel filePanel = new JPanel(new GridBagLayout());
        GridBagConstraints fgbc = new GridBagConstraints();
        fgbc.insets = new Insets(4, 4, 4, 4);
        fgbc.fill = GridBagConstraints.HORIZONTAL;
        fgbc.gridx = 0; fgbc.gridy = 0; fgbc.weightx = 0.2;
        filePanel.add(new JBLabel("File / Path:"), fgbc);
        fgbc.gridx = 1; fgbc.gridy = 0; fgbc.weightx = 0.8;
        hsqlFileField = new TextFieldWithBrowseButton();
        hsqlFileField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Select Database File"));
        filePanel.add(hsqlFileField, fgbc);
        hsqlSubCardPanel.add(filePanel, HsqlMode.FILE.name());

        // Subcard 3: SERVER
        JPanel srvPanel = new JPanel(new GridBagLayout());
        GridBagConstraints sgbc = new GridBagConstraints();
        sgbc.insets = new Insets(4, 4, 4, 4);
        sgbc.fill = GridBagConstraints.HORIZONTAL;
        sgbc.gridx = 0; sgbc.gridy = 0; sgbc.weightx = 0.2;
        srvPanel.add(new JBLabel("Host:"), sgbc);
        sgbc.gridx = 1; sgbc.gridy = 0; sgbc.weightx = 0.5;
        hsqlServerHostField = new JBTextField("localhost");
        srvPanel.add(hsqlServerHostField, sgbc);

        sgbc.gridx = 2; sgbc.gridy = 0; sgbc.weightx = 0.1;
        srvPanel.add(new JBLabel("Port:"), sgbc);
        sgbc.gridx = 3; sgbc.gridy = 0; sgbc.weightx = 0.2;
        hsqlServerPortField = new JBTextField("9001");
        srvPanel.add(hsqlServerPortField, sgbc);

        sgbc.gridx = 0; sgbc.gridy = 1; sgbc.weightx = 0.2;
        srvPanel.add(new JBLabel("Database:"), sgbc);
        sgbc.gridx = 1; sgbc.gridy = 1; sgbc.gridwidth = 3; sgbc.weightx = 0.8;
        hsqlServerDbField = new JBTextField("testdb");
        srvPanel.add(hsqlServerDbField, sgbc);
        hsqlSubCardPanel.add(srvPanel, HsqlMode.SERVER.name());

        p.add(hsqlSubCardPanel, BorderLayout.CENTER);

        // HSQL user/password
        JPanel authPanel = new JPanel(new GridBagLayout());
        GridBagConstraints agbc = new GridBagConstraints();
        agbc.insets = new Insets(4, 4, 4, 4);
        agbc.fill = GridBagConstraints.HORIZONTAL;

        agbc.gridx = 0; agbc.gridy = 0; agbc.weightx = 0.2;
        authPanel.add(new JBLabel("User:"), agbc);
        agbc.gridx = 1; agbc.gridy = 0; agbc.weightx = 0.3;
        hsqlUserField = new JBTextField("SA");
        authPanel.add(hsqlUserField, agbc);

        agbc.gridx = 2; agbc.gridy = 0; agbc.weightx = 0.2;
        authPanel.add(new JBLabel("Password:"), agbc);
        agbc.gridx = 3; agbc.gridy = 0; agbc.weightx = 0.3;
        hsqlPasswordField = new JPasswordField();
        authPanel.add(hsqlPasswordField, agbc);

        p.add(authPanel, BorderLayout.SOUTH);

        return p;
    }

    private void setupListeners() {
        typeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
                cardLayout.show(dynamicPanel, type.name());
                updatePreview();
            }
        });

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

        customUrlCheck.addActionListener(e -> {
            customUrlField.setEnabled(customUrlCheck.isSelected());
            updatePreview();
        });
        customUrlField.getDocument().addDocumentListener(dl);

        testButton.addActionListener(e -> doTestConnection());
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

        if (customUrlCheck.isSelected()) {
            c.setCustomUrl(customUrlField.getText().trim());
        } else {
            c.setCustomUrl("");
        }

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
        return c;
    }

    private void doTestConnection() {
        testStatusLabel.setText("Connecting...");
        testStatusLabel.setForeground(Color.GRAY);
        testButton.setEnabled(false);

        SwingUtilities.invokeLater(() -> {
            new Thread(() -> {
                ConnectionConfig temp = createTempConfig();
                ConnectionTestResult result = DatabaseConnectionManager.getInstance().testConnection(temp);

                SwingUtilities.invokeLater(() -> {
                    testButton.setEnabled(true);
                    if (result.isSuccess()) {
                        testStatusLabel.setText("Connected! " + result.getDatabaseProductName() + " (" + result.getResponseTimeMs() + "ms)");
                        testStatusLabel.setForeground(new JBColor(new Color(40, 160, 80), new Color(98, 181, 67)));
                        Messages.showInfoMessage(result.getSummaryMessage(), "Connection Successful");
                    } else {
                        testStatusLabel.setText("Failed!");
                        testStatusLabel.setForeground(new JBColor(new Color(200, 40, 40), new Color(255, 107, 107)));
                        Messages.showErrorDialog(result.getSummaryMessage(), "Connection Failed");
                    }
                });
            }).start();
        });
    }

    private void loadValues() {
        nameField.setText(config.getName());
        typeCombo.setSelectedItem(config.getType());
        cardLayout.show(dynamicPanel, config.getType().name());

        if (config.getType() == DatabaseType.MYSQL) {
            mysqlHostField.setText(config.getHost());
            mysqlPortField.setText(String.valueOf(config.getPort()));
            mysqlDatabaseField.setText(config.getDatabaseName());
            mysqlUserField.setText(config.getUser());
            mysqlPasswordField.setText(config.getPassword());
        } else {
            hsqlModeCombo.setSelectedItem(config.getHsqlMode());
            hsqlSubCardLayout.show(hsqlSubCardPanel, config.getHsqlMode().name());
            if (config.getHsqlMode() == HsqlMode.FILE) {
                hsqlFileField.setText(config.getDatabaseName());
            } else if (config.getHsqlMode() == HsqlMode.MEM) {
                hsqlMemNameField.setText(config.getDatabaseName());
            } else {
                hsqlServerHostField.setText(config.getHost());
                hsqlServerPortField.setText(String.valueOf(config.getPort()));
                hsqlServerDbField.setText(config.getDatabaseName());
            }
            hsqlUserField.setText(config.getUser());
            hsqlPasswordField.setText(config.getPassword());
        }

        if (config.getCustomUrl() != null && !config.getCustomUrl().isEmpty()) {
            customUrlCheck.setSelected(true);
            customUrlField.setText(config.getCustomUrl());
            customUrlField.setEnabled(true);
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
    }
}
