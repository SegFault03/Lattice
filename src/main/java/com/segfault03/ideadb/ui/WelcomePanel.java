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

public class WelcomePanel extends JPanel {
    private final Project project;

    public WelcomePanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        initUI();
    }

    private void initUI() {
        JPanel content = new JPanel(new GridBagLayout());
        content.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(24, 20, 8, 20);
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;

        // Title with Icon
        JBLabel title = new JBLabel("Lattice", Icons.LATTICE_LARGE, SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        content.add(title, gbc);

        // Subtitle
        gbc.gridy++;
        gbc.insets = new Insets(4, 20, 20, 20);
        JBLabel subtitle = new JBLabel("Fast, lightweight in-app database management for MySQL & HSQLDB", SwingConstants.CENTER);
        subtitle.setFont(subtitle.getFont().deriveFont(13f));
        subtitle.setForeground(new JBColor(new Color(110, 110, 110), new Color(160, 160, 160)));
        content.add(subtitle, gbc);

        // Action Buttons
        gbc.gridy++;
        gbc.insets = new Insets(0, 20, 24, 20);
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        buttonRow.setOpaque(false);

        JButton addConnBtn = new JButton("Add Connection...", AllIcons.General.Add);
        addConnBtn.setFont(addConnBtn.getFont().deriveFont(Font.BOLD, 12f));
        addConnBtn.addActionListener(e -> showAddConnectionMenu(addConnBtn));
        buttonRow.add(addConnBtn);

        JButton openConsoleBtn = new JButton("Open SQL Console", Icons.CONSOLE);
        openConsoleBtn.setFont(openConsoleBtn.getFont().deriveFont(12f));
        openConsoleBtn.addActionListener(e -> openSqlConsole());
        buttonRow.add(openConsoleBtn);

        content.add(buttonRow, gbc);

        // Quick Tips Card
        gbc.gridy++;
        gbc.insets = new Insets(0, 20, 20, 20);
        JPanel tipsCard = new JPanel();
        tipsCard.setLayout(new BoxLayout(tipsCard, BoxLayout.Y_AXIS));
        tipsCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new JBColor(new Color(210, 215, 220), new Color(75, 78, 82)), 1, true),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)
        ));
        tipsCard.setBackground(new JBColor(new Color(250, 252, 255), new Color(43, 45, 48)));

        JBLabel tipsTitle = new JBLabel("Quick Tips:");
        tipsTitle.setFont(tipsTitle.getFont().deriveFont(Font.BOLD, 13f));
        tipsTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        tipsCard.add(tipsTitle);
        tipsCard.add(Box.createVerticalStrut(10));

        tipsCard.add(createTipLabel("Double-click", "any table in Database Explorer to view and edit data in a main editor tab."));
        tipsCard.add(Box.createVerticalStrut(6));
        tipsCard.add(createTipLabel("Right-click", "nodes to create tables, alter columns, truncate, or run queries."));
        tipsCard.add(Box.createVerticalStrut(6));
        tipsCard.add(createTipLabel("Ctrl+Shift+D", "to quickly toggle the Lattice database tool window."));
        tipsCard.add(Box.createVerticalStrut(6));
        tipsCard.add(createTipLabel("Zero Setup", "supports In-Memory HSQLDB for instant testing without server installs."));

        content.add(tipsCard, gbc);

        // "Do not show on startup" Checkbox
        gbc.gridy++;
        gbc.insets = new Insets(0, 20, 24, 20);
        JBCheckBox doNotShowCheck = new JBCheckBox("Do not show Welcome screen on startup", !DatabaseSettingsState.getInstance().isShowWelcomeScreen());
        doNotShowCheck.setFont(doNotShowCheck.getFont().deriveFont(11f));
        doNotShowCheck.setOpaque(false);
        doNotShowCheck.addActionListener(e -> {
            boolean doNotShow = doNotShowCheck.isSelected();
            DatabaseSettingsState.getInstance().setShowWelcomeScreen(!doNotShow);
        });
        content.add(doNotShowCheck, gbc);

        add(new JBScrollPane(content), BorderLayout.CENTER);
    }

    private void showAddConnectionMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem mysqlItem = new JMenuItem("MySQL Database...");
        mysqlItem.addActionListener(e -> showAddConnectionDialog(DatabaseType.MYSQL));
        menu.add(mysqlItem);

        JMenuItem hsqlItem = new JMenuItem("HSQLDB Database...");
        hsqlItem.addActionListener(e -> showAddConnectionDialog(DatabaseType.HSQLDB));
        menu.add(hsqlItem);

        menu.show(invoker, 0, invoker.getHeight());
    }

    private void showAddConnectionDialog(DatabaseType defaultType) {
        ConnectionConfig newCfg = new ConnectionConfig(defaultType, "New " + defaultType.getDisplayName());
        newCfg.setDatabaseName("");
        newCfg.setUser("");
        ConnectionDialog dlg = new ConnectionDialog(project, newCfg);
        if (dlg.showAndGet()) {
            ConnectionConfig result = dlg.getResultConfig();
            DatabaseSettingsState.getInstance().addConnection(result);
        }
    }

    private void openSqlConsole() {
        List<ConnectionConfig> configs = DatabaseSettingsState.getInstance().getConnections();
        if (!configs.isEmpty()) {
            DatabaseEditorManager.getInstance(project).openConsole(configs.get(0), null, null);
        } else {
            showAddConnectionDialog(DatabaseType.HSQLDB);
        }
    }

    private static JBLabel createTipLabel(String boldPrefix, String text) {
        JBLabel label = new JBLabel("<html>&#8226;&nbsp; <b>" + boldPrefix + "</b> " + text + "</html>");
        label.setFont(label.getFont().deriveFont(12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}
