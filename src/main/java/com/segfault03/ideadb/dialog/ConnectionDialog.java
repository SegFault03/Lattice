package com.segfault03.ideadb.dialog;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.JBColor;
import com.intellij.icons.AllIcons;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBScrollPane;
import com.segfault03.ideadb.ui.DatabaseUi;
import com.segfault03.ideadb.ui.VisibleCardPanel;
import java.awt.datatransfer.StringSelection;
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

import com.segfault03.ideadb.ui.DatabaseInputs;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;

public class ConnectionDialog extends DialogWrapper {
    private final ConnectionConfig config;
    private JPanel rootPanel;
    private JPanel driverDetails;
    private final ActionLink driverToggle = new ActionLink("Driver options ▸");
    private final JBLabel driverSummary = new JBLabel();
    private boolean uiInitialized;
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
    private boolean connectionBusy;

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
    private final ActionLink testButton = new ActionLink("Test connection");
    private final JBLabel testStatusLabel = new JBLabel("");

    public ConnectionDialog(@Nullable Project project, ConnectionConfig config) {
        this(project, config, false);
    }

    public ConnectionDialog(@Nullable Project project, ConnectionConfig config, boolean newConnection) {
        super(project, true);
        this.config = config.copy();
        testButton.setAutoHideOnDisable(false);
        setTitle(newConnection ? "New connection" : "Edit connection");
        setOKButtonText(newConnection ? "Add connection" : "Save");
        init();
        loadValues();
        updatePreview();
        uiInitialized = true;
        updateTestAvailability();
        setDriverExpanded(config.getDriverSource() != DriverSource.BUNDLED);
        refreshFormSize();
    }

    public ConnectionConfig getResultConfig() {
        saveValues();
        return config;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        rootPanel = new JPanel(new GridBagLayout()) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.width = Math.max(JBUI.scale(520), size.width);
                return size;
            }
        };
        rootPanel.setBorder(JBUI.Borders.empty(0, 8));
        ConnectionFormPanel topPanel = new ConnectionFormPanel();
        topPanel.addSection(0, "Data source");
        nameField = ConnectionFormPanel.width(DatabaseInputs.textField(), 360);
        topPanel.addRow(1, "Name:", nameField, false);
        typeCombo = ConnectionFormPanel.width(DatabaseInputs.comboBox(DatabaseType.values()), 220);
        topPanel.addRow(2, "Database type:", typeCombo, false);
        topPanel.addSection(3, "Connection");
        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        standardRadio = new JRadioButton("Standard", true);
        customUrlRadio = new JRadioButton("JDBC URL");
        ButtonGroup group = new ButtonGroup();
        group.add(standardRadio);
        group.add(customUrlRadio);
        radioPanel.add(standardRadio);
        radioPanel.add(Box.createHorizontalStrut(JBUI.scale(12)));
        radioPanel.add(customUrlRadio);
        topPanel.addRow(4, "Connect via:", radioPanel, false);
        addPart(0, topPanel);

        mainCardLayout = new CardLayout();
        mainCardPanel = new VisibleCardPanel(mainCardLayout);
        dbTypeCardLayout = new CardLayout();
        dbTypeCardPanel = new VisibleCardPanel(dbTypeCardLayout);
        dbTypeCardPanel.add(createMysqlPanel(), DatabaseType.MYSQL.name());
        dbTypeCardPanel.add(createHsqlPanel(), DatabaseType.HSQLDB.name());
        mainCardPanel.add(dbTypeCardPanel, "STANDARD");
        mainCardPanel.add(createCustomUrlPanel(), "JDBC_URL");
        addPart(1, mainCardPanel);

        JPanel urlSection = new JPanel(new BorderLayout());
        urlSection.add(DatabaseUi.section("JDBC URL"), BorderLayout.NORTH);
        JPanel previewPanel = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        urlPreviewLabel = new JBLabel();
        urlPreviewLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, urlPreviewLabel.getFont().getSize()));
        urlPreviewLabel.setPreferredSize(JBUI.size(320, 28));
        JButton copyUrl = DatabaseUi.action("", AllIcons.Actions.Copy, "Copy JDBC URL");
        copyUrl.addActionListener(e -> {
            if (urlPreviewLabel.getToolTipText() != null)
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(urlPreviewLabel.getToolTipText()), null);
        });
        previewPanel.add(urlPreviewLabel, BorderLayout.CENTER);
        previewPanel.add(copyUrl, BorderLayout.EAST);
        urlSection.add(previewPanel, BorderLayout.CENTER);
        addPart(2, urlSection);

        JPanel driverSection = new JPanel(new BorderLayout());
        driverSection.add(DatabaseUi.section("Driver"), BorderLayout.NORTH);
        JPanel driverBody = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        JPanel summaryRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        driverSummary.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x63666C, 0xB5B8BD)));
        summaryRow.add(driverSummary, BorderLayout.CENTER);
        summaryRow.add(driverToggle, BorderLayout.EAST);
        driverBody.add(summaryRow, BorderLayout.NORTH);
        driverToggle.addActionListener(e -> setDriverExpanded(!driverDetails.isVisible()));
        ConnectionFormPanel driverForm = new ConnectionFormPanel();
        driverSourceCombo = ConnectionFormPanel.width(DatabaseInputs.comboBox(DriverSource.values()), 220);
        driverForm.addRow(0, "Source:", driverSourceCombo, false);
        driverCards = new VisibleCardPanel(driverLayout);
        driverCards.add(new JPanel(), DriverSource.BUNDLED.name());
        JPanel versionPanel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        driverVersionCombo = ConnectionFormPanel.width(DatabaseInputs.comboBox(), 220);
        driverVersionCombo.setEditable(true);
        versionPanel.add(driverVersionCombo, BorderLayout.NORTH);
        versionPanel.add(DatabaseUi.group(listVersionsButton, downloadDriverButton), BorderLayout.SOUTH);
        driverCards.add(versionPanel, DriverSource.DOWNLOAD.name());
        driverJarField = DatabaseInputs.browseField();
        ConnectionFormPanel.width(driverJarField, 360);
        driverJarField.setToolTipText("Choose the driver matching the server or HSQLDB file format.");
        driverJarField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor("jar").withTitle("Select JDBC Driver JAR"));
        driverCards.add(driverJarField, DriverSource.LOCAL_JAR.name());
        driverForm.addRow(1, "", driverCards, false);
        driverStatusLabel.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x63666C, 0xB5B8BD)));
        driverForm.addRow(2, "", driverStatusLabel, false);
        driverDetails = driverForm;
        driverBody.add(driverDetails, BorderLayout.CENTER);
        driverDetails.setVisible(false);
        driverSection.add(driverBody, BorderLayout.CENTER);
        addPart(3, driverSection);
        driverSourceCombo.addActionListener(e -> {
            showDriverCard((DriverSource) driverSourceCombo.getSelectedItem());
            updateDriverStatus();
        });
        driverVersionCombo.addActionListener(e -> updateDriverStatus());
        downloadDriverButton.addActionListener(e -> runDriverAction(false));
        listVersionsButton.addActionListener(e -> runDriverAction(true));
        refillDriverVersions();
        setupListeners();
        GridBagConstraints space = new GridBagConstraints();
        space.gridy = 4;
        space.weighty = 1;
        space.fill = GridBagConstraints.VERTICAL;
        rootPanel.add(Box.createVerticalGlue(), space);
        JBScrollPane scroll = new JBScrollPane(rootPanel) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.height = Math.min(size.height, JBUI.scale(580));
                return size;
            }
        };
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private void addPart(int row, JComponent part) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = 0;
        cell.gridy = row;
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.anchor = GridBagConstraints.NORTHWEST;
        rootPanel.add(part, cell);
    }

    private void setDriverExpanded(boolean expanded) {
        driverDetails.setVisible(expanded);
        driverToggle.setText(expanded ? "Driver options ▾" : "Driver options ▸");
        refreshFormSize();
        if (expanded) SwingUtilities.invokeLater(() -> {
            if (!isDisposed() && driverDetails.isVisible())
                driverDetails.scrollRectToVisible(new Rectangle(0, 0, driverDetails.getWidth(), driverDetails.getHeight()));
        });
    }

    private void refreshFormSize() {
        if (!uiInitialized) return;
        rootPanel.revalidate();
        rootPanel.repaint();
        Window window = getWindow();
        if (window != null) {
            window.setMinimumSize(null);
            Dimension preferred = window.getPreferredSize();
            window.setMinimumSize(new Dimension(JBUI.scale(560), preferred.height));
            if (!window.isShowing()) window.pack();
            else
                window.setSize(Math.max(window.getWidth(), JBUI.scale(560)), preferred.height);
        }
    }

    @Override
    protected JComponent createSouthPanel() {
        JPanel south = new JPanel(new BorderLayout(JBUI.scale(12), JBUI.scale(6)));
        south.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineTop(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR),
                JBUI.Borders.empty(12, 8, 0, 8)));
        testStatusLabel.setFont(testStatusLabel.getFont().deriveFont(Font.PLAIN));
        testStatusLabel.setVisible(false);
        south.add(testStatusLabel, BorderLayout.NORTH);
        testButton.addActionListener(e -> doTestConnection());
        south.add(testButton, BorderLayout.WEST);
        JComponent defaultButtons = super.createSouthPanel();
        if (defaultButtons != null) south.add(defaultButtons, BorderLayout.EAST);
        return south;
    }

    private JPanel createMysqlPanel() {
        ConnectionFormPanel form = new ConnectionFormPanel();
        mysqlHostField = ConnectionFormPanel.width(DatabaseInputs.textField("localhost"), 220);
        mysqlPortField = ConnectionFormPanel.width(DatabaseInputs.textField("3306"), 76);
        form.addRow(0, "Host:", mysqlHostField, false);
        form.addRow(1, "Port:", mysqlPortField, false);
        mysqlDatabaseField = ConnectionFormPanel.width(DatabaseInputs.textField(), 220);
        form.addRow(2, "Database:", mysqlDatabaseField, false);
        mysqlUserField = ConnectionFormPanel.width(DatabaseInputs.textField(), 220);
        form.addSection(3, "Authentication");
        form.addRow(4, "User:", mysqlUserField, false);
        mysqlPasswordField = ConnectionFormPanel.width(DatabaseInputs.passwordField(), 220);
        form.addRow(5, "Password:", mysqlPasswordField, false);
        return form;
    }

    private void showDriverCard(DriverSource source) {
        if (source == null || driverCards == null) return;
        driverLayout.show(driverCards, source.name());
        driverCards.setVisible(source != DriverSource.BUNDLED);
        driverCards.revalidate();
        driverCards.repaint();
        if (driverCards.getParent() != null) driverCards.getParent().revalidate();
        refreshFormSize();
    }

    private JPanel createHsqlPanel() {
        ConnectionFormPanel form = new ConnectionFormPanel();
        hsqlModeCombo = ConnectionFormPanel.width(DatabaseInputs.comboBox(HsqlMode.values()), 220);
        hsqlModeCombo.setSelectedItem(HsqlMode.SERVER);
        form.addRow(0, "Mode:", hsqlModeCombo, false);

        hsqlSubCardLayout = new CardLayout();
        hsqlSubCardPanel = new VisibleCardPanel(hsqlSubCardLayout);
        ConnectionFormPanel serverForm = new ConnectionFormPanel();
        hsqlServerHostField = ConnectionFormPanel.width(DatabaseInputs.textField("localhost"), 220);
        hsqlServerPortField = ConnectionFormPanel.width(DatabaseInputs.textField("9001"), 76);
        serverForm.addRow(0, "Host:", hsqlServerHostField, false);
        serverForm.addRow(1, "Port:", hsqlServerPortField, false);
        hsqlServerDbField = ConnectionFormPanel.width(DatabaseInputs.textField(), 220);
        serverForm.addRow(2, "Database:", hsqlServerDbField, false);
        hsqlSubCardPanel.add(serverForm, HsqlMode.SERVER.name());

        ConnectionFormPanel fileForm = new ConnectionFormPanel();
        hsqlFileField = DatabaseInputs.browseField();
        ConnectionFormPanel.width(hsqlFileField, 220);
        hsqlFileField.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Select Database File"));
        fileForm.addRow(0, "Database path:", hsqlFileField, false);
        hsqlSubCardPanel.add(fileForm, HsqlMode.FILE.name());

        ConnectionFormPanel memoryForm = new ConnectionFormPanel();
        hsqlMemNameField = ConnectionFormPanel.width(DatabaseInputs.textField(), 220);
        memoryForm.addRow(0, "Database name:", hsqlMemNameField, false);
        hsqlSubCardPanel.add(memoryForm, HsqlMode.MEM.name());
        form.addFullWidthRow(1, hsqlSubCardPanel);

        hsqlUserField = ConnectionFormPanel.width(DatabaseInputs.textField(), 220);
        form.addSection(2, "Authentication");
        form.addRow(3, "User:", hsqlUserField, false);
        hsqlPasswordField = ConnectionFormPanel.width(DatabaseInputs.passwordField(), 220);
        form.addRow(4, "Password:", hsqlPasswordField, false);
        return form;
    }

    private JPanel createCustomUrlPanel() {
        ConnectionFormPanel form = new ConnectionFormPanel();
        customUrlField = ConnectionFormPanel.width(DatabaseInputs.textField(), 360);
        form.addRow(0, "JDBC URL:", customUrlField, false);
        customUrlUserField = ConnectionFormPanel.width(DatabaseInputs.textField(), 220);
        form.addSection(1, "Authentication");
        form.addRow(2, "User:", customUrlUserField, false);
        customUrlPasswordField = ConnectionFormPanel.width(DatabaseInputs.passwordField(), 220);
        form.addRow(3, "Password:", customUrlPasswordField, false);
        JBLabel noteLabel = new JBLabel("<html><body width='340'>Enter the full JDBC connection string. Credentials entered above will be passed to the driver.</body></html>");
        noteLabel.setForeground(JBColor.GRAY);
        form.addRow(4, "", noteLabel, false);
        return form;
    }

    private void setupListeners() {
        // Toggle between Standard and Custom JDBC URL modes
        standardRadio.addActionListener(e -> {
            mainCardLayout.show(mainCardPanel, "STANDARD");
            updatePreview();
            refreshFormSize();
        });

        customUrlRadio.addActionListener(e -> {
            mainCardLayout.show(mainCardPanel, "JDBC_URL");
            updatePreview();
            refreshFormSize();
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
                refreshFormSize();
            }
        });

        // HSQL Mode Switcher
        hsqlModeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                HsqlMode mode = (HsqlMode) hsqlModeCombo.getSelectedItem();
                hsqlSubCardLayout.show(hsqlSubCardPanel, mode.name());
                updatePreview();
                refreshFormSize();
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
        driverJarField.getTextField().getDocument().addDocumentListener(dl);
        if (driverVersionCombo.getEditor().getEditorComponent() instanceof JTextField versionField)
            versionField.getDocument().addDocumentListener(dl);
        nameField.getDocument().addDocumentListener(dl);
    }

    private void updatePreview() {
        updateTestAvailability();
        if (customUrlRadio.isSelected() && customUrlField.getText().trim().isEmpty()) {
            urlPreviewLabel.setText("Enter a JDBC URL");
            urlPreviewLabel.setToolTipText(null);
            return;
        }
        ConnectionConfig temp = createTempConfig();
        String resolvedUrl = temp.buildJdbcUrl();
        urlPreviewLabel.setText(resolvedUrl);
        urlPreviewLabel.setToolTipText(resolvedUrl);
    }

    private void updateTestAvailability() {
        if (driverJarField == null || customUrlField == null) return;
        String problem = ConnectionReadiness.problem(createTempConfig(), customUrlRadio.isSelected());
        testButton.setEnabled(!driverBusy && !connectionBusy && problem == null);
        testButton.setToolTipText(driverBusy ? "Wait for the driver download to finish"
                : connectionBusy ? "A connection test is running"
                : problem == null ? "Test these connection settings" : problem);
    }

    private void setDriverStatus(String message) {
        message = java.util.Objects.requireNonNullElse(message, " ");
        driverStatusLabel.setIcon(null);
        driverStatusLabel.setText("<html><body width='300'>" + escapeHtml(message) + "</body></html>");
        driverStatusLabel.setToolTipText(message);
        refreshFormSize();
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
                    c.setPort(0);
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
                        c.setPort(0);
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
        DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
        DriverSource source = (DriverSource)driverSourceCombo.getSelectedItem();
        String bundledVersion = type == DatabaseType.MYSQL ? "26.7.0" : "2.7.4";
        driverSummary.setText(type.getDisplayName() + (source == DriverSource.BUNDLED ? " " + bundledVersion : "") + " · " + source);
        driverSummary.setToolTipText(driverSummary.getText());
        if (source == DriverSource.DOWNLOAD) {
            try {
                var path = DriverCatalog.downloadedJar((DatabaseType)typeCombo.getSelectedItem(), String.valueOf(driverVersionCombo.getEditor().getItem()).trim());
                setDriverStatus(java.nio.file.Files.isRegularFile(path) ? "Downloaded and ready" : "Choose a version, then Download. You can also enter a release version.");
            } catch (IllegalArgumentException error) { setDriverStatus(error.getMessage()); }
        } else setDriverStatus(source == DriverSource.LOCAL_JAR ? "Select the driver JAR matching your database server." : type.getDisplayName() + " " + bundledVersion + " included and ready to use.");
        updateTestAvailability();
    }
    private void runDriverAction(boolean listOnly) {
        if (driverBusy || connectionBusy || isDisposed()) return;
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
                setOKActionEnabled(true);
                if (available != null) {
                    driverVersionCombo.removeAllItems(); available.forEach(driverVersionCombo::addItem); driverVersionCombo.setSelectedItem(version);
                }
                updateDriverStatus();
                if (failure != null) { setDriverStatus(listOnly ? "Could not load versions; enter a version or retry." : "Download failed; use a local JAR or retry.");
                    driverStatusLabel.setIcon(com.intellij.icons.AllIcons.General.Error); Messages.showErrorDialog(failure, "JDBC Driver"); }
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
        if (driverBusy || connectionBusy || !testButton.isEnabled() || isDisposed()) return;
        if (customUrlRadio.isSelected() && customUrlField.getText().trim().isEmpty()) {
            Messages.showErrorDialog("Enter a JDBC URL before testing the connection.", "Invalid Connection");
            return;
        }
        ConnectionConfig temp = createTempConfig();
        DatabaseType originalType = temp.getType();
        ConnectionConfig requested = temp.copy();
        DatabaseUi.status(testStatusLabel, "Testing connection…", DatabaseUi.Tone.BUSY);
        testStatusLabel.setVisible(true);

        connectionBusy = true;
        updateTestAvailability();
        downloadDriverButton.setEnabled(false); listVersionsButton.setEnabled(false);

        connectionTask = com.segfault03.ideadb.service.DatabaseTaskService.getInstance().submit(() -> {
                if(isDisposed()) return;
                ConnectionTestResult result = DatabaseConnectionManager.getInstance().testConnection(temp);

                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    connectionBusy = false;
                    updateTestAvailability();
                    downloadDriverButton.setEnabled(true); listVersionsButton.setEnabled(true);
                    if (!sameRequest(requested, createTempConfig())) {
                        DatabaseUi.status(testStatusLabel, "Settings changed · Test again", DatabaseUi.Tone.WARNING); return;
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
                        DatabaseUi.status(testStatusLabel, "Connected · " + result.getDatabaseProductName() + " " + result.getDatabaseProductVersion(), DatabaseUi.Tone.SUCCESS);
                        testStatusLabel.setToolTipText("Driver: " + result.getDriverName() + " " + result.getDriverVersion() + "; " + result.getResponseTimeMs() + " ms");

                    } else {
                        DatabaseUi.status(testStatusLabel, "Connection failed · Check the settings", DatabaseUi.Tone.ERROR);
                        testStatusLabel.setToolTipText(result.getSummaryMessage());
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
        showDriverCard(config.getDriverSource());
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
