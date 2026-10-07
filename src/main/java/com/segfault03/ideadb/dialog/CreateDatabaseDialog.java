package com.segfault03.ideadb.dialog;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import org.jetbrains.annotations.Nullable;

import com.segfault03.ideadb.ui.DatabaseInputs;

import javax.swing.*;
import java.awt.*;

public class CreateDatabaseDialog extends DialogWrapper {
    private final ConnectionConfig config;
    private JBTextField nameField;

    public CreateDatabaseDialog(@Nullable Project project, ConnectionConfig config) {
        super(project, true);
        this.config = config;
        setTitle(config.getType() == DatabaseType.MYSQL ? "Create Database" : "Create Schema");
        init();
    }

    public String getDatabaseName() {
        return nameField.getText().trim();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setPreferredSize(new Dimension(380, 90));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.3;
        panel.add(new JBLabel(config.getType() == DatabaseType.MYSQL ? "Database Name:" : "Schema Name:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.7;
        nameField = DatabaseInputs.textField();
        panel.add(nameField, gbc);

        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo("Database name cannot be empty", nameField);
        }
        if (!name.matches("^[a-zA-Z0-9_]+$")) {
            return new ValidationInfo("Name must contain only alphanumeric characters and underscores", nameField);
        }
        return null;
    }
}
