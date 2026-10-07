package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.segfault03.ideadb.dialog.ConnectionDialog;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.state.DatabaseSettingsState;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class WelcomePanel extends JPanel implements AutoCloseable {
    private final Project project;
    private final DatabaseSettingsState settings;
    private JButton openConsole;
    private com.intellij.util.messages.MessageBusConnection connection;
    private boolean closed;

    public WelcomePanel(Project project) {
        this(project, DatabaseSettingsState.getInstance());
    }

    WelcomePanel(Project project, DatabaseSettingsState settings) {
        super(new BorderLayout());
        this.project = project;
        this.settings = settings;
        initUI();
        if (project != null) {
            connection = com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect();
            connection.subscribe(com.segfault03.ideadb.state.DatabaseSettingsListener.TOPIC,
                    () -> SwingUtilities.invokeLater(() -> { if (!closed) updateConsoleAvailability(); }));
        }
    }

    private void initUI() {
        setBackground(JBColor.namedColor("Panel.background", JBColor.background()));
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);
        content.setBorder(com.intellij.util.ui.JBUI.Borders.empty(32, 24));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        JBLabel title = new JBLabel("Welcome to Lattice", Icons.LATTICE_LARGE, SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setIconTextGap(com.intellij.util.ui.JBUI.scale(12));
        content.add(title, gbc);
        gbc.gridy++;
        gbc.insets = com.intellij.util.ui.JBUI.insets(12, 0, 20, 0);
        content.add(description("Explore MySQL and HSQLDB from your IDE."), gbc);

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 4));
        actions.setOpaque(false);
        JButton addConnection = new JButton("Add connection…", AllIcons.General.Add);
        addConnection.setToolTipText("Configure a MySQL or HSQLDB connection");
        addConnection.addActionListener(e -> showAddConnectionMenu(addConnection));
        actions.add(addConnection);
        actions.add(Box.createHorizontalStrut(com.intellij.util.ui.JBUI.scale(12)));
        openConsole = DatabaseUi.action("Open SQL console", Icons.CONSOLE, "Open a SQL console for a saved connection");
        updateConsoleAvailability();
        openConsole.addActionListener(e -> openSqlConsole());
        actions.add(openConsole);
        gbc.gridy++;
        gbc.insets = com.intellij.util.ui.JBUI.insetsBottom(20);
        content.add(actions, gbc);

        gbc.gridy++;
        gbc.insets = com.intellij.util.ui.JBUI.insetsBottom(8);
        content.add(DatabaseUi.section("Get started"), gbc);
        gbc.gridy++;
        content.add(step(Icons.DATABASE, "1. Connect to a database", "Use a server, a local file, or an in-memory HSQLDB database."), gbc);
        gbc.gridy++;
        content.add(step(Icons.TABLE, "2. Browse and edit data", "Double-click a table in the explorer. Commit saves your edits."), gbc);
        gbc.gridy++;
        content.add(step(Icons.CONSOLE, "3. Run SQL", "Open a SQL console from a connection, database, or table."), gbc);

        gbc.gridy++;
        gbc.insets = com.intellij.util.ui.JBUI.insets(12, 0, 8, 0);
        content.add(DatabaseUi.section("Try it locally"), gbc);
        gbc.gridy++;
        gbc.insets = com.intellij.util.ui.JBUI.insetsBottom(20);
        content.add(description("Choose HSQLDB → In-Memory for a database without a server."), gbc);
        gbc.gridy++;
        gbc.insets = com.intellij.util.ui.JBUI.insetsBottom(0);
        JBCheckBox showWelcome = new JBCheckBox("Show welcome screen on startup", settings.isShowWelcomeScreen());
        showWelcome.setOpaque(false);
        showWelcome.setToolTipText("You can reopen this screen from the explorer's Help button");
        showWelcome.addActionListener(e -> settings.setShowWelcomeScreen(showWelcome.isSelected()));
        content.add(showWelcome, gbc);
        gbc.gridy++;
        gbc.weighty = 1;
        content.add(Box.createVerticalGlue(), gbc);

        // Limit reading width while letting the editor fit narrower windows.
        JPanel frame = new JPanel(new GridBagLayout()) {
            @Override public void doLayout() {
                content.setPreferredSize(new Dimension(Math.min(com.intellij.util.ui.JBUI.scale(660), getWidth()), content.getPreferredSize().height));
                super.doLayout();
            }
        };
        frame.setOpaque(false);
        GridBagConstraints body = new GridBagConstraints();
        body.weightx = 1;
        body.weighty = 1;
        body.fill = GridBagConstraints.VERTICAL;
        body.anchor = GridBagConstraints.NORTH;
        frame.add(content, body);
        JBScrollPane scroll = new JBScrollPane(frame);
        scroll.setBorder(com.intellij.util.ui.JBUI.Borders.empty());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
    }

    private static JPanel step(Icon icon, String title, String text) {
        JPanel row = new JPanel(new BorderLayout(com.intellij.util.ui.JBUI.scale(12), 0));
        row.setOpaque(false);
        row.setBorder(com.intellij.util.ui.JBUI.Borders.empty(10, 0));
        JBLabel glyph = new JBLabel(icon);
        glyph.setVerticalAlignment(SwingConstants.TOP);
        row.add(glyph, BorderLayout.WEST);
        JPanel copy = new JPanel(new BorderLayout(0, com.intellij.util.ui.JBUI.scale(4)));
        copy.setOpaque(false);
        JBLabel heading = new JBLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        copy.add(heading, BorderLayout.NORTH);
        copy.add(description(text), BorderLayout.CENTER);
        row.add(copy, BorderLayout.CENTER);
        return row;
    }

    private static JTextArea description(String text) {
        JTextArea copy = new JTextArea(text);
        copy.setFont(javax.swing.UIManager.getFont("Label.font"));
        copy.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x616872, 0xA8ADB7)));
        copy.setEditable(false);
        copy.setFocusable(false);
        copy.setOpaque(false);
        copy.setLineWrap(true);
        copy.setWrapStyleWord(true);
        copy.setColumns(32);
        return copy;
    }

    private void updateConsoleAvailability() {
        openConsole.setEnabled(!settings.getConnections().isEmpty());
        openConsole.setToolTipText(openConsole.isEnabled() ? "Open a SQL console for a saved connection" : "Add a connection to open a SQL console");
    }

    @Override public void close() {
        closed = true;
        if (connection != null) connection.disconnect();
    }

    private void showAddConnectionMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem mysqlItem = new JMenuItem("MySQL…");
        mysqlItem.addActionListener(e -> showAddConnectionDialog(DatabaseType.MYSQL));
        menu.add(mysqlItem);

        JMenuItem hsqlItem = new JMenuItem("HSQLDB…");
        hsqlItem.addActionListener(e -> showAddConnectionDialog(DatabaseType.HSQLDB));
        menu.add(hsqlItem);

        menu.show(invoker, 0, invoker.getHeight());
    }

    private void showAddConnectionDialog(DatabaseType defaultType) {
        ConnectionConfig newCfg = new ConnectionConfig(defaultType, "New " + defaultType.getDisplayName());
        newCfg.setDatabaseName("");
        newCfg.setUser("");
        ConnectionDialog dlg = new ConnectionDialog(project, newCfg, true);
        if (dlg.showAndGet()) {
            ConnectionConfig result = dlg.getResultConfig();
            settings.addConnection(result);
            updateConsoleAvailability();
        }
    }

    private void openSqlConsole() {
        List<ConnectionConfig> configs = settings.getConnections();
        if (configs.isEmpty()) {
            updateConsoleAvailability();
        } else if (configs.size() == 1) {
            DatabaseEditorManager.getInstance(project).openConsole(configs.get(0), null, null);
        } else {
            JPopupMenu menu = new JPopupMenu();
            for (ConnectionConfig config : configs) {
                JMenuItem item = new JMenuItem(config.getName(), Icons.DATABASE);
                item.addActionListener(e -> DatabaseEditorManager.getInstance(project).openConsole(config, null, null));
                menu.add(item);
            }
            menu.show(openConsole, 0, openConsole.getHeight());
        }
    }

}
