package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
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
        content.setBorder(JBUI.Borders.empty(36, 32, 28, 32));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        JBLabel brand = new JBLabel("Lattice", Icons.LATTICE_LARGE, SwingConstants.LEFT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 18f));
        brand.setIconTextGap(JBUI.scale(12));
        content.add(brand, gbc);

        gbc.gridy++;
        gbc.insets = JBUI.insets(22, 0, 8, 0);
        JTextArea headline = description("Database work, inside your IDE.");
        headline.setForeground(JBColor.namedColor("Label.foreground", JBColor.foreground()));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 25f));
        headline.setColumns(34);
        content.add(headline, gbc);

        gbc.gridy++;
        gbc.insets = JBUI.insetsBottom(20);
        content.add(description("Explore schemas, edit tables, run SQL, and export results."), gbc);

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(14), JBUI.scale(4)));
        actions.setOpaque(false);
        JButton addConnection = new JButton("Add connection\u2026", AllIcons.General.Add);
        addConnection.setToolTipText("Configure a database connection");
        addConnection.getAccessibleContext().setAccessibleName("Add connection\u2026");
        addConnection.addActionListener(e -> showAddConnectionMenu(addConnection));
        actions.add(addConnection);

        openConsole = DatabaseUi.action("Open SQL console", Icons.CONSOLE,
                "Open a SQL console for a saved connection");
        updateConsoleAvailability();
        openConsole.addActionListener(e -> openSqlConsole());
        actions.add(openConsole);

        gbc.gridy++;
        gbc.insets = JBUI.insetsBottom(26);
        content.add(actions, gbc);

        gbc.gridy++;
        gbc.insets = JBUI.insetsBottom(0);
        content.add(sourceShelf(), gbc);

        gbc.gridy++;
        gbc.insets = JBUI.insets(24, 0, 0, 0);
        JBCheckBox showWelcome = new JBCheckBox("Show welcome screen on startup", settings.isShowWelcomeScreen());
        showWelcome.setOpaque(false);
        showWelcome.setToolTipText("You can reopen this screen from the explorer's Help button");
        showWelcome.addActionListener(e -> settings.setShowWelcomeScreen(showWelcome.isSelected()));
        content.add(showWelcome, gbc);

        // Keep the reading column comfortable on wide editors and let it contract on narrow ones.
        JPanel frame = new JPanel(new GridBagLayout()) {
            @Override public void doLayout() {
                content.setPreferredSize(new Dimension(Math.min(JBUI.scale(760), getWidth()),
                        content.getPreferredSize().height));
                super.doLayout();
            }
        };
        frame.setOpaque(false);
        GridBagConstraints body = new GridBagConstraints();
        body.weightx = 1;
        body.weighty = 1;
        body.fill = GridBagConstraints.VERTICAL;
        body.anchor = GridBagConstraints.CENTER;
        frame.add(content, body);

        JBScrollPane scroll = new JBScrollPane(frame);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
    }

    private static JPanel sourceShelf() {
        JPanel shelf = new JPanel(new BorderLayout(0, JBUI.scale(12)));
        shelf.setOpaque(true);
        shelf.setBackground(JBColor.namedColor("Panel.background", JBColor.background()));
        Color borderColor = JBColor.namedColor("Borders.color",
                new JBColor(new Color(0xD8DCE3), new Color(0x41454D)));
        shelf.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(borderColor),
                JBUI.Borders.empty(14, 16)));

        JBLabel heading = new JBLabel("Supported data sources");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        shelf.add(heading, BorderLayout.NORTH);

        JPanel sources = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(22), JBUI.scale(6)));
        sources.setOpaque(false);
        for (DatabaseType type : List.of(DatabaseType.MYSQL, DatabaseType.POSTGRESQL,
                DatabaseType.H2, DatabaseType.HSQLDB)) {
            JBLabel source = new JBLabel(type.getDisplayName(), Icons.dataSource(type, JBUI.scale(24)), SwingConstants.LEFT);
            source.setIconTextGap(JBUI.scale(8));
            source.getAccessibleContext().setAccessibleName(type.getDisplayName());
            sources.add(source);
        }
        shelf.add(sources, BorderLayout.CENTER);
        return shelf;
    }

    private static JTextArea description(String text) {
        JTextArea copy = new JTextArea(text);
        copy.setFont(UIManager.getFont("Label.font"));
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
        DatabaseUi.connectionMenu(this::showAddConnectionDialog).show(invoker, 0, invoker.getHeight());
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
            JPopupMenu menu = DatabaseUi.popupMenu();
            for (ConnectionConfig config : configs) {
                JMenuItem item = new JMenuItem(config.getName(), Icons.DATABASE);
                item.addActionListener(e -> DatabaseEditorManager.getInstance(project).openConsole(config, null, null));
                menu.add(item);
            }
            menu.show(openConsole, 0, openConsole.getHeight());
        }
    }
}
