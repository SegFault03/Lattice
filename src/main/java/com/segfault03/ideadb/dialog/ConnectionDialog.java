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
import com.segfault03.ideadb.model.InstalledDriver;
import com.segfault03.ideadb.service.DriverCatalog;
import com.segfault03.ideadb.service.DriverStore;
import com.intellij.openapi.ui.ValidationInfo;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import org.jetbrains.annotations.Nullable;

import com.segfault03.ideadb.ui.DatabaseInputs;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ConnectionDialog extends DialogWrapper {
    private final @Nullable Project project;
    private final ConnectionConfig config;
    private final boolean showUrlPreview;
    private final boolean newConnection;
    private record DriverSelection(DriverSource source, InstalledDriver available, String downloadVersion, String localJar) {}
    private final java.util.Map<DatabaseType, DriverSelection> driverSelections = new java.util.EnumMap<>(DatabaseType.class);
    private final java.util.Map<DatabaseType, List<InstalledDriver>> driverInventory = new java.util.EnumMap<>(DatabaseType.class);
    private final java.util.Map<DatabaseType, InstalledDriver> projectDefaults = new java.util.EnumMap<>(DatabaseType.class);
    private final Set<DatabaseType> manuallySelectedDrivers = java.util.EnumSet.noneOf(DatabaseType.class);
    private DatabaseType displayedDriverType;
    private boolean updatingDrivers;
    private boolean discoveryBusy;
    private java.util.concurrent.Future<?> discoveryTask;
    private JPanel rootPanel;
    private JPanel driverDetails;
    private final ActionLink driverToggle = new ActionLink("Driver options ▸");
    private final JBLabel driverSummary = new JBLabel();
    private boolean uiInitialized;
    private JComboBox<DriverSource> driverSourceCombo;
    private JComboBox<InstalledDriver> bundledDriverCombo;
    private JComboBox<String> driverVersionCombo;
    private Set<String> downloadedDriverVersions = Set.of();
    private Set<String> discoveredDriverVersions = Set.of();
    private TextFieldWithBrowseButton driverJarField;
    private JPanel driverCards;
    private final CardLayout driverLayout = new CardLayout();
    private final JButton downloadDriverButton = new JButton("Download");
    private final JButton listVersionsButton = new JButton("More versions");
    private final JProgressBar driverProgressBar = new JProgressBar(0, 100);
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

    // Preview and action components
    private JBLabel urlPreviewLabel;
    private final ActionLink testButton = new ActionLink("Test connection");
    private final JBLabel testStatusLabel = new JBLabel("");

    public ConnectionDialog(@Nullable Project project, ConnectionConfig config) {
        this(project, config, false);
    }

    public ConnectionDialog(@Nullable Project project, ConnectionConfig config, boolean newConnection) {
        super(project, true);
        this.project = project;
        this.config = config.copy();
        this.newConnection = newConnection;
        if (!newConnection || config.getDriverSource() != DriverSource.BUNDLED || !config.getDriverVersion().isBlank()) {
            InstalledDriver saved = config.getDriverVersion().isBlank() ? DriverStore.packaged(config.getType())
                    : new InstalledDriver(config.getType(), config.getDriverVersion(), InstalledDriver.Kind.DISCOVERED,
                    config.getDriverJarPath().isBlank() ? null : java.nio.file.Path.of(config.getDriverJarPath()));
            driverSelections.put(config.getType(), new DriverSelection(config.getDriverSource(), saved,
                    config.getDriverVersion(), config.getDriverJarPath()));
        }
        this.showUrlPreview = !newConnection;
        testButton.setAutoHideOnDisable(false);
        setTitle(newConnection ? "New connection" : "Edit connection");
        setOKButtonText(newConnection ? "Add connection" : "Save");
        init();
        loadValues();
        refreshConnectionState();
        uiInitialized = true;
        updateTestAvailability();
        // A named available driver is a deliberate choice, so reveal it instead of hiding it behind the summary.
        setDriverExpanded(config.getDriverSource() != DriverSource.BUNDLED || !config.getDriverVersion().isBlank());
        refreshFormSize();
        discoverDrivers();
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

        if (showUrlPreview) {
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
        }

        JPanel driverSection = new JPanel(new BorderLayout(0, JBUI.scale(3)));
        driverSection.add(DatabaseUi.section("Driver"), BorderLayout.NORTH);
        JPanel driverBody = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        JPanel summaryRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        driverSummary.setForeground(UIManager.getColor("Label.foreground"));
        driverSummary.getAccessibleContext().setAccessibleName("Selected JDBC driver");
        summaryRow.add(driverSummary, BorderLayout.CENTER);
        summaryRow.add(driverToggle, BorderLayout.EAST);
        driverBody.add(summaryRow, BorderLayout.NORTH);
        driverToggle.addActionListener(e -> setDriverExpanded(!driverDetails.isVisible()));
        ConnectionFormPanel driverForm = new ConnectionFormPanel();
        driverSourceCombo = ConnectionFormPanel.width(DatabaseInputs.comboBox(DriverSource.values()), 220);
        driverForm.addRow(0, "Source:", driverSourceCombo, false);
        driverCards = new VisibleCardPanel(driverLayout);
        // Drivers already available locally, including retained downloads and Maven-cached releases.
        bundledDriverCombo = ConnectionFormPanel.width(DatabaseInputs.comboBox(), 220);
        ListCellRenderer<? super InstalledDriver> availableRenderer = bundledDriverCombo.getRenderer();
        bundledDriverCombo.setRenderer((list, driver, index, selected, focus) -> {
            Component rendered = availableRenderer.getListCellRendererComponent(list, driver, index, selected, focus);
            if (driver != null && !driver.packaged() && !discoveryBusy
                    && !driverInventory.getOrDefault(driver.type(), List.of()).contains(driver)
                    && rendered instanceof JLabel label) {
                label.setText(driver.version() + " · UNAVAILABLE");
                label.setEnabled(false);
            }
            return rendered;
        });
        bundledDriverCombo.getAccessibleContext().setAccessibleName("Available driver");
        bundledDriverCombo.setToolTipText("Packaged, downloaded, and Maven-discovered drivers already available here.");
        driverCards.add(bundledDriverCombo, DriverSource.BUNDLED.name());
        JPanel versionPanel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        driverVersionCombo = DatabaseInputs.comboBox();
        driverVersionCombo.setEditable(true);
        driverVersionCombo.getAccessibleContext().setAccessibleName("Driver version");
        // Styling editable combos resets their renderer, so apply our downloaded marker afterward.
        ConnectionFormPanel.width(driverVersionCombo, 220);
        ListCellRenderer<? super String> versionRenderer = driverVersionCombo.getRenderer();
        driverVersionCombo.setRenderer((list, value, index, selected, focus) -> {
            Component rendered = versionRenderer.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof String version && rendered instanceof JLabel label
                    && (downloadedDriverVersions.contains(version) || discoveredDriverVersions.contains(version))) {
                String source = downloadedDriverVersions.contains(version) ? "DOWNLOADED" : "DISCOVERED";
                label.setText(version + " · " + source);
                label.setForeground(JBColor.namedColor("Label.disabledForeground", new JBColor(0x8C8C8C, 0x777777)));
                label.setEnabled(false);
            }
            return rendered;
        });
        // The action buttons below can make this card wider than the fields. Avoid BorderLayout.NORTH
        // stretching the version editor to the button row's width; keep it aligned with Source.
        JPanel versionFieldRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        versionFieldRow.add(driverVersionCombo);
        versionPanel.add(versionFieldRow, BorderLayout.NORTH);
        JPanel versionActionsRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        versionActionsRow.setOpaque(false);
        versionActionsRow.add(listVersionsButton);
        versionActionsRow.add(Box.createHorizontalStrut(JBUI.scale(8)));
        versionActionsRow.add(downloadDriverButton);
        versionPanel.add(versionActionsRow, BorderLayout.SOUTH);
        driverProgressBar.setStringPainted(false);
        driverProgressBar.setPreferredSize(new Dimension(JBUI.scale(220), JBUI.scale(4)));
        driverProgressBar.getAccessibleContext().setAccessibleName("Driver download progress");
        driverProgressBar.setVisible(false);
        JPanel progressRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        progressRow.setOpaque(false);
        progressRow.add(driverProgressBar);
        versionPanel.add(progressRow, BorderLayout.CENTER);
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
        int driverRow = showUrlPreview ? 3 : 2;
        addPart(driverRow, driverSection);
        driverSourceCombo.addActionListener(e -> {
            showDriverCard((DriverSource) driverSourceCombo.getSelectedItem());
            driverSelectionChanged();
        });
        bundledDriverCombo.addActionListener(e -> driverSelectionChanged());
        driverVersionCombo.addActionListener(e -> driverSelectionChanged());
        downloadDriverButton.addActionListener(e -> runDriverAction(false));
        listVersionsButton.addActionListener(e -> runDriverAction(true));
        refillDriverVersions();
        setupListeners();
        GridBagConstraints space = new GridBagConstraints();
        space.gridy = driverRow + 1;
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
        JPanel south = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        south.setBorder(JBUI.Borders.empty(0, 8, 0, 8));
        testStatusLabel.setFont(testStatusLabel.getFont().deriveFont(Font.PLAIN));
        testStatusLabel.setBorder(JBUI.Borders.empty(6, 0, 0, 0));
        testStatusLabel.setVisible(false);
        south.add(testStatusLabel, BorderLayout.NORTH);
        testButton.addActionListener(e -> doTestConnection());
        JPanel actions = new JPanel(new BorderLayout(JBUI.scale(12), JBUI.scale(6)));
        actions.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(10, 0, 0, 0),
                BorderFactory.createCompoundBorder(
                        JBUI.Borders.customLineTop(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR),
                        JBUI.Borders.empty(12, 0, 0, 0))));
        actions.add(testButton, BorderLayout.WEST);
        JComponent defaultButtons = super.createSouthPanel();
        if (defaultButtons != null) actions.add(defaultButtons, BorderLayout.EAST);
        south.add(actions, BorderLayout.CENTER);
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
            refreshConnectionState();
            refreshFormSize();
        });

        customUrlRadio.addActionListener(e -> {
            mainCardLayout.show(mainCardPanel, "JDBC_URL");
            refreshConnectionState();
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
                rememberDriverSelection();
                refillDriverVersions();
                refreshConnectionState();
                refreshFormSize();
            }
        });

        // HSQL Mode Switcher
        hsqlModeCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                HsqlMode mode = (HsqlMode) hsqlModeCombo.getSelectedItem();
                hsqlSubCardLayout.show(hsqlSubCardPanel, mode.name());
                refreshConnectionState();
                refreshFormSize();
            }
        });

        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshConnectionState(); }
            public void removeUpdate(DocumentEvent e) { refreshConnectionState(); }
            public void changedUpdate(DocumentEvent e) { refreshConnectionState(); }
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

        // Auto-detect type when typing a custom JDBC URL.
        customUrlField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { checkUrlPrefix(); refreshConnectionState(); }
            public void removeUpdate(DocumentEvent e) { checkUrlPrefix(); refreshConnectionState(); }
            public void changedUpdate(DocumentEvent e) { checkUrlPrefix(); refreshConnectionState(); }

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
        DocumentListener driverSelectionListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { driverSelectionChanged(); }
            public void removeUpdate(DocumentEvent e) { driverSelectionChanged(); }
            public void changedUpdate(DocumentEvent e) { driverSelectionChanged(); }
        };
        driverJarField.getTextField().getDocument().addDocumentListener(driverSelectionListener);
        if (driverVersionCombo.getEditor().getEditorComponent() instanceof JTextField versionField)
            versionField.getDocument().addDocumentListener(driverSelectionListener);
        nameField.getDocument().addDocumentListener(dl);
    }

    private void refreshConnectionState() {
        updateTestAvailability();
        if (urlPreviewLabel == null) return;
        if (customUrlRadio.isSelected() && customUrlField.getText().trim().isEmpty()) {
            urlPreviewLabel.setText("Enter a JDBC URL");
            urlPreviewLabel.setToolTipText(null);
            return;
        }
        String resolvedUrl = createTempConfig().buildJdbcUrl();
        urlPreviewLabel.setText(resolvedUrl);
        urlPreviewLabel.setToolTipText(resolvedUrl);
    }

    private void updateTestAvailability() {
        if (driverJarField == null || customUrlField == null) return;
        String problem = ConnectionReadiness.problem(createTempConfig(), customUrlRadio.isSelected());
        testButton.setEnabled(!discoveryBusy && !driverBusy && !connectionBusy && problem == null);
        testButton.setToolTipText(discoveryBusy ? "Looking for locally available drivers" : driverBusy ? "Wait for the driver download to finish"
                : connectionBusy ? "A connection test is running"
                : problem == null ? "Test these connection settings" : problem);
    }

    private void setDriverStatus(String message) {
        message = java.util.Objects.requireNonNullElse(message, " ");
        DatabaseUi.status(driverStatusLabel, "<html><body width='300'>" + escapeHtml(message) + "</body></html>",
                driverBusy || discoveryBusy ? DatabaseUi.Tone.BUSY : DatabaseUi.Tone.NORMAL);
        driverStatusLabel.setToolTipText(message);
        driverStatusLabel.getAccessibleContext().setAccessibleDescription(message);
        refreshFormSize();
    }

    private void setDriverControlsEnabled(boolean enabled) {
        driverSourceCombo.setEnabled(enabled);
        bundledDriverCombo.setEnabled(enabled);
        driverVersionCombo.setEnabled(enabled);
        typeCombo.setEnabled(enabled);
        standardRadio.setEnabled(enabled);
        customUrlRadio.setEnabled(enabled);
        customUrlField.setEnabled(enabled);
        setComponentTreeEnabled(driverJarField, enabled);
        downloadDriverButton.setEnabled(enabled);
        listVersionsButton.setEnabled(enabled);
    }

    private static void setComponentTreeEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) setComponentTreeEnabled(child, enabled);
    }

    private void updateDriverProgress(long bytesReceived, long totalBytes) {
        if (isDisposed() || !driverBusy) return;
        if (totalBytes > 0) {
            driverProgressBar.setIndeterminate(false);
            driverProgressBar.setValue((int) Math.min(100, bytesReceived * 100 / totalBytes));
            driverProgressBar.setToolTipText(bytesReceived + " of " + totalBytes + " bytes");
        } else {
            driverProgressBar.setIndeterminate(true);
            driverProgressBar.setToolTipText("Downloading driver");
        }
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
        DriverSource driverSource = (DriverSource) driverSourceCombo.getSelectedItem();
        c.setDriverSource(driverSource);
        c.setDriverVersion(driverSource == DriverSource.BUNDLED ? selectedBundledVersion()
                : driverSource == DriverSource.DOWNLOAD ? selectedDriverVersion() : "");
        c.setDriverJarPath(driverSource == DriverSource.BUNDLED ? selectedBundledJarPath()
                : driverSource == DriverSource.LOCAL_JAR ? driverJarField.getText().trim() : "");

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

    private void driverSelectionChanged() {
        if (updatingDrivers) return;
        if (uiInitialized && displayedDriverType != null) manuallySelectedDrivers.add(displayedDriverType);
        rememberDriverSelection();
        updateDriverStatus();
    }

    private void rememberDriverSelection() {
        if (!uiInitialized || updatingDrivers || displayedDriverType == null
                || discoveryBusy && !manuallySelectedDrivers.contains(displayedDriverType)) return;
        driverSelections.put(displayedDriverType, new DriverSelection((DriverSource) driverSourceCombo.getSelectedItem(),
                (InstalledDriver) bundledDriverCombo.getSelectedItem(), selectedDriverVersion(), driverJarField.getText()));
    }

    private void discoverDrivers() {
        discoveryBusy = true;
        setOKActionEnabled(false);
        updateDriverStatus();
        discoveryTask = com.segfault03.ideadb.service.DatabaseTaskService.getInstance().submit(() -> {
            var inventory = new java.util.EnumMap<DatabaseType, List<InstalledDriver>>(DatabaseType.class);
            var defaults = new java.util.EnumMap<DatabaseType, InstalledDriver>(DatabaseType.class);
            String error = null;
            try {
                var repository = com.segfault03.ideadb.service.MavenRepositoryLocator.localRepository(project);
                for (DatabaseType type : DatabaseType.values()) {
                    if (Thread.currentThread().isInterrupted()) return;
                    inventory.put(type, DriverStore.installedInRepository(type, repository));
                    if (newConnection) DriverStore.projectDefaultInRepository(type, project, repository)
                            .ifPresent(driver -> defaults.put(type, driver));
                }
            } catch (Exception failure) { error = "Local driver discovery failed; use the bundled driver or a local JAR."; }
            String failure = error;
            SwingUtilities.invokeLater(() -> {
                if (isDisposed()) return;
                driverInventory.putAll(inventory);
                projectDefaults.putAll(defaults);
                discoveryBusy = false;
                refillDriverVersions();
                setOKActionEnabled(!driverBusy && !connectionBusy);
                if (failure != null) setDriverStatus(failure);
            });
        });
    }

    private void refillDriverVersions() {
        DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
        updatingDrivers = true;
        try {
            displayedDriverType = type;
            var drivers = driverInventory.getOrDefault(type, List.of(DriverStore.packaged(type)));
            downloadedDriverVersions = drivers.stream().filter(driver -> driver.kind() == InstalledDriver.Kind.DOWNLOADED)
                    .map(InstalledDriver::version).collect(java.util.stream.Collectors.toUnmodifiableSet());
            discoveredDriverVersions = drivers.stream().filter(driver -> driver.kind() == InstalledDriver.Kind.DISCOVERED)
                    .map(InstalledDriver::version).collect(java.util.stream.Collectors.toUnmodifiableSet());
            var selection = driverSelections.get(type);
            InstalledDriver preferred = selection == null ? projectDefaults.get(type) : selection.available();
            bundledDriverCombo.removeAllItems();
            drivers.forEach(bundledDriverCombo::addItem);
            if (preferred != null) {
                String path = preferred.jar() == null ? "" : preferred.jar().toString();
                selectBundledDriver(preferred.version(), path);
                // An unavailable saved selection must stay visible and fail validation instead of silently falling back.
                if (!preferred.packaged() && !(bundledDriverCombo.getSelectedItem() instanceof InstalledDriver selected
                        && selected.version().equals(preferred.version()))) {
                    bundledDriverCombo.addItem(preferred);
                    bundledDriverCombo.setSelectedItem(preferred);
                }
            }
            driverVersionCombo.removeAllItems();
            LinkedHashSet<String> versions = new LinkedHashSet<>(DriverCatalog.suggestedVersions(type));
            versions.addAll(downloadedDriverVersions);
            versions.addAll(discoveredDriverVersions);
            versions.forEach(driverVersionCombo::addItem);
            driverSourceCombo.setSelectedItem(selection == null ? DriverSource.BUNDLED : selection.source());
            driverJarField.setText(selection == null ? "" : selection.localJar());
            if (selection != null && !selection.downloadVersion().isBlank())
                driverVersionCombo.setSelectedItem(selection.downloadVersion());
            showDriverCard((DriverSource) driverSourceCombo.getSelectedItem());
        } finally { updatingDrivers = false; }
        updateDriverStatus();
    }
    private void selectBundledDriver(String version, String jarPath) {
        if (jarPath != null && !jarPath.isBlank()) {
            for (int index = 0; index < bundledDriverCombo.getItemCount(); index++) {
                InstalledDriver driver = bundledDriverCombo.getItemAt(index);
                if (driver.version().equals(version) && driver.jar() != null && driver.jar().toString().equals(jarPath)) {
                    bundledDriverCombo.setSelectedIndex(index);
                    return;
                }
            }
        }
        for (int index = 0; index < bundledDriverCombo.getItemCount(); index++) {
            if (bundledDriverCombo.getItemAt(index).version().equals(version)) {
                bundledDriverCombo.setSelectedIndex(index);
                return;
            }
        }
    }
    private void updateDriverStatus() {
        if (updatingDrivers || driverBusy || driverSourceCombo == null) return;
        DatabaseType type = (DatabaseType) typeCombo.getSelectedItem();
        DriverSource source = (DriverSource)driverSourceCombo.getSelectedItem();
        if (source == null) return;
        downloadDriverButton.setEnabled(!discoveryBusy && !driverBusy && !connectionBusy);
        listVersionsButton.setEnabled(!discoveryBusy && !driverBusy && !connectionBusy);
        String selectedDriver = switch (source) {
            case BUNDLED -> selectedBundledLabel(type);
            case DOWNLOAD -> selectedDriverVersion();
            case LOCAL_JAR -> selectedLocalDriverLabel(type);
        };
        String origin = switch (source) {
            case BUNDLED -> bundledDriverCombo.getSelectedItem() instanceof InstalledDriver driver
                    ? !driver.packaged() && !discoveryBusy && !driverInventory.getOrDefault(type, List.of()).contains(driver)
                    ? "UNAVAILABLE" : driver.kind().name() : "BUNDLED";
            case DOWNLOAD -> downloadedDriverVersions.contains(selectedDriver) ? "DOWNLOADED"
                    : discoveredDriverVersions.contains(selectedDriver) ? "DISCOVERED" : "DOWNLOAD";
            case LOCAL_JAR -> "LOCAL JAR";
        };
        driverSummary.setText((selectedDriver.isBlank() ? source == DriverSource.LOCAL_JAR ? "Choose a JAR" : "Choose a version" : selectedDriver) + " · " + origin);
        driverSummary.setToolTipText(formatDriverLabel(type, selectedDriver) + " · " + origin);
        driverSummary.getAccessibleContext().setAccessibleDescription(driverSummary.getToolTipText());
        if (source == DriverSource.BUNDLED) {
            var selected = (InstalledDriver) bundledDriverCombo.getSelectedItem();
            String packaged = DriverStore.packagedVersion(type);
            if ("UNAVAILABLE".equals(origin))
                setDriverStatus("The selected driver is no longer available. Choose another available driver or download it again.");
            else if (selected != null && selected.kind() == InstalledDriver.Kind.DOWNLOADED)
                setDriverStatus(selected.version() + " is stored on this machine and ready to use.");
            else if (selected != null && selected.kind() == InstalledDriver.Kind.DISCOVERED)
                setDriverStatus(selected.version() + " was found in the Maven local repository and is ready to use.");
            else setDriverStatus((packaged.isEmpty() ? type.getDisplayName() + " driver" : type.getDisplayName() + " " + packaged)
                    + " is packaged with the plugin and ready to use.");
        } else if (source == DriverSource.DOWNLOAD) {
            String version = selectedDriverVersion();
            boolean alreadyDownloaded = downloadedDriverVersions.contains(version);
            boolean alreadyDiscovered = discoveredDriverVersions.contains(version);
            downloadDriverButton.setEnabled(!discoveryBusy && !driverBusy && !connectionBusy && !alreadyDownloaded && !alreadyDiscovered);
            if (alreadyDownloaded) setDriverStatus("Downloaded and ready");
            else if (alreadyDiscovered) setDriverStatus("Discovered in the Maven local repository; select it under Available drivers.");
            else try {
                var path = DriverCatalog.downloadedJar(type, version);
                setDriverStatus(java.nio.file.Files.isRegularFile(path) ? "Downloaded and ready" : "Choose a version, then Download. You can also enter a release version.");
            } catch (IllegalArgumentException error) { setDriverStatus(error.getMessage()); }
        } else setDriverStatus("Select the driver JAR matching your database server.");
        if (discoveryBusy) setDriverStatus("Looking for locally available drivers…");
        updateTestAvailability();
    }
    private String selectedDriverVersion() {
        Object selected = driverVersionCombo.getEditor().getItem();
        return selected == null ? "" : selected.toString().trim();
    }
    /** Blank means the packaged driver; a version names a retained download. */
    private String selectedBundledVersion() {
        var selected = (InstalledDriver) bundledDriverCombo.getSelectedItem();
        return selected != null && !selected.packaged() ? selected.version() : "";
    }
    private String selectedBundledJarPath() {
        var selected = (InstalledDriver) bundledDriverCombo.getSelectedItem();
        return selected == null || selected.packaged() || selected.jar() == null ? "" : selected.jar().toString();
    }
    private String selectedBundledLabel(DatabaseType type) {
        var selected = (InstalledDriver) bundledDriverCombo.getSelectedItem();
        String version = selected == null ? "" : selected.version();
        return version.isBlank() ? DriverStore.packagedVersion(type) : version;
    }
    private String formatDriverLabel(DatabaseType type, String version) {
        String name = type == DatabaseType.MYSQL ? "MySQL Connector/J" : "HSQLDB";
        return version == null || version.isBlank() ? name : name + " " + version;
    }
    private String selectedLocalDriverLabel(DatabaseType type) {
        String pathText = driverJarField.getText().trim();
        if (pathText.isEmpty()) return "";
        try {
            java.nio.file.Path path = java.nio.file.Path.of(pathText);
            java.nio.file.Path fileName = path.getFileName();
            if (fileName == null) return "";
            String name = fileName.toString();
            String version = DriverCatalog.versionOf(type, name);
            if (version.isBlank() && java.nio.file.Files.isRegularFile(path)) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(path.toFile())) {
                    var manifest = jar.getManifest();
                    var attributes = manifest == null ? null : manifest.getMainAttributes();
                    if (attributes != null) {
                        for (String attribute : List.of("Implementation-Version", "Bundle-Version", "Specification-Version")) {
                            String value = attributes.getValue(attribute);
                            if (value != null && !value.isBlank()) {
                                version = value.trim();
                                break;
                            }
                        }
                    }
                } catch (java.io.IOException ignored) { }
            }
            return version.isBlank() ? name : version;
        } catch (java.nio.file.InvalidPathException | SecurityException ignored) {
            return "";
        }
    }
    private void runDriverAction(boolean listOnly) {
        if (discoveryBusy || driverBusy || connectionBusy || isDisposed()) return;
        DatabaseType type = (DatabaseType)typeCombo.getSelectedItem();
        String version = selectedDriverVersion();
        if (!listOnly && downloadedDriverVersions.contains(version)) {
            updateDriverStatus();
            return;
        }
        rememberDriverSelection();
        driverBusy = true;
        setDriverControlsEnabled(false);
        driverProgressBar.setValue(0);
        driverProgressBar.setIndeterminate(!listOnly);
        driverProgressBar.setVisible(!listOnly);
        testButton.setEnabled(false); setOKActionEnabled(false);
        refreshFormSize();
        setDriverStatus(listOnly ? "Loading versions from Maven Central..." : "Downloading and verifying " + version + "...");
        driverTask = com.segfault03.ideadb.service.DatabaseTaskService.getInstance().submit(() -> {
            String error = null; java.util.List<String> versions = null;
            List<InstalledDriver> refreshed = null;
            try {
                if (listOnly) versions = DriverCatalog.availableVersions(type);
                else DriverCatalog.download(type, version, (bytesReceived, totalBytes) ->
                        SwingUtilities.invokeLater(() -> updateDriverProgress(bytesReceived, totalBytes)));
                refreshed = DriverStore.installed(type, project);
            }
            catch (Exception failure) { error = failure.getMessage(); }
            String failure = error; java.util.List<String> available = versions;
            List<InstalledDriver> inventory = refreshed;
            SwingUtilities.invokeLater(() -> {
                if (isDisposed()) return;
                driverBusy = false;
                driverProgressBar.setVisible(false);
                driverProgressBar.setIndeterminate(false);
                driverProgressBar.setValue(0);
                setDriverControlsEnabled(true);
                setOKActionEnabled(true);
                if (inventory != null) driverInventory.put(type, inventory);
                refillDriverVersions();
                if (available != null) {
                    updatingDrivers = true;
                    try {
                        LinkedHashSet<String> mergedVersions = new LinkedHashSet<>(available);
                        mergedVersions.addAll(downloadedDriverVersions);
                        mergedVersions.addAll(discoveredDriverVersions);
                        driverVersionCombo.removeAllItems();
                        mergedVersions.forEach(driverVersionCombo::addItem);
                        driverVersionCombo.setSelectedItem(version);
                    } finally { updatingDrivers = false; }
                    rememberDriverSelection();
                    updateDriverStatus();
                }
                refreshFormSize();
                if (failure != null) { setDriverStatus(listOnly ? "Could not load versions; enter a version or retry." : "Download failed; use a local JAR or retry.");
                    driverStatusLabel.setIcon(com.intellij.icons.AllIcons.General.Error); Messages.showErrorDialog(failure, "JDBC Driver"); }
            });
        });
    }
    @Override protected @Nullable ValidationInfo doValidate() {
        if (discoveryBusy) return new ValidationInfo("Wait for local driver discovery to finish", driverSourceCombo);
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
            } else if (candidate.getDriverSource() == DriverSource.BUNDLED && !candidate.getDriverVersion().isBlank()) {
                var selected = (InstalledDriver) bundledDriverCombo.getSelectedItem();
                if (selected == null || selected.packaged()) return new ValidationInfo("Select an available driver", bundledDriverCombo);
                if (selected.jar() == null) return new ValidationInfo("The selected driver JAR is unavailable", bundledDriverCombo);
                DriverCatalog.validateJar(candidate.getType(), selected.jar());
            } else if (candidate.getDriverSource() == DriverSource.LOCAL_JAR) {
                DriverCatalog.validateJar(candidate.getType(), java.nio.file.Path.of(candidate.getDriverJarPath()));
            }
        } catch (Exception error) { return new ValidationInfo(error.getMessage(), driverSourceCombo); }
        return null;
    }
    @Override protected void dispose() {
        if (discoveryTask != null) discoveryTask.cancel(true);
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
        setOKActionEnabled(false);
        setDriverControlsEnabled(false);
        updateDriverStatus();

        connectionTask = com.segfault03.ideadb.service.DatabaseTaskService.getInstance().submit(() -> {
                if(isDisposed()) return;
                ConnectionTestResult result = DatabaseConnectionManager.getInstance().testConnection(temp);

                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    connectionBusy = false;
                    setOKActionEnabled(true);
                    setDriverControlsEnabled(true);
                    updateDriverStatus();
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
                            refreshConnectionState();
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

        if (!config.getDriverVersion().isBlank()) {
            // A saved available driver wins over the packaged default when editing a connection.
            if (config.getDriverSource() == DriverSource.BUNDLED)
                selectBundledDriver(config.getDriverVersion(), config.getDriverJarPath());
            else if (config.getDriverSource() == DriverSource.DOWNLOAD)
                driverVersionCombo.setSelectedItem(config.getDriverVersion());
        }
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
