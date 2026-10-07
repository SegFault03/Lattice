package com.segfault03.ideadb.dialog;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.segfault03.ideadb.model.ColumnDefinition;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.DdlService;
import com.segfault03.ideadb.service.MetadataService;
import org.jetbrains.annotations.Nullable;

import com.segfault03.ideadb.ui.DatabaseInputs;

import javax.swing.*;
import java.awt.*;
import java.sql.Connection;
import java.util.List;

public class AlterTableDialog extends DialogWrapper {
    private final Project project;
    private final ConnectionConfig config;
    private final String databaseName;
    private final TableMetadata tableMetadata;

    private boolean busy;
    private volatile Connection activeConnection;
    private java.util.concurrent.Future<?> operationTask;
    private JPanel centerPanel;
    private final java.util.Map<Component,Boolean> enabledStates = new java.util.IdentityHashMap<>();

    // Add column tab
    private JBTextField addColNameField;
    private JComboBox<String> addColTypeCombo;
    private JBTextField addColSizeField;
    private JBCheckBox addColNullableCheck;
    private JBTextField addColDefaultField;

    // Rename column tab
    private JComboBox<String> renameColCombo;
    private JBTextField renameColNewNameField;

    // Modify column tab
    private JComboBox<String> modifyColCombo;
    private JComboBox<String> modifyColTypeCombo;
    private JBTextField modifyColSizeField;
    private JBCheckBox modifyColNullableCheck;
    private JBTextField modifyColDefaultField;

    // Drop column tab
    private JComboBox<String> dropColCombo;

    // Rename table tab
    private JBTextField renameTableField;

    private static final String[] DATA_TYPES = {
            "VARCHAR", "INT", "BIGINT", "TEXT", "BOOLEAN", "DECIMAL", "DOUBLE", "DATE", "TIMESTAMP", "BLOB"
    };

    public AlterTableDialog(@Nullable Project project, ConnectionConfig config, String databaseName, TableMetadata tableMetadata) {
        super(project, true);
        this.project = project;
        this.config = config;
        this.databaseName = databaseName;
        this.tableMetadata = tableMetadata;

        setTitle("Alter table: " + tableMetadata.getName());
        setResizable(true);
        setCancelButtonText("Close");
        init();
        if (tableMetadata.getColumns().isEmpty()) runAlter(conn -> {}, tableMetadata.getName(), null);
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getCancelAction()};
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "Lattice.AlterTableDialog.v1";
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        centerPanel = root;
        root.setPreferredSize(new Dimension(740, 420));
        root.setMinimumSize(new Dimension(680, 380));

        String[] colNames = tableMetadata.getColumns().stream().map(ColumnMetadata::getName).toArray(String[]::new);

        // 1. Add column Panel
        JPanel addPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.3;
        addPanel.add(new JBLabel("Column name:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.7;
        addColNameField = DatabaseInputs.textField();
        addPanel.add(addColNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.3;
        addPanel.add(new JBLabel("Type:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.7;
        addColTypeCombo = DatabaseInputs.comboBox(DATA_TYPES);
        addPanel.add(addColTypeCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.3;
        addPanel.add(new JBLabel("Size / length:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.7;
        addColSizeField = DatabaseInputs.textField("255");
        addPanel.add(addColSizeField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.3;
        addPanel.add(new JBLabel("Nullable:"), gbc);
        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 0.7;
        addColNullableCheck = new JBCheckBox("Allow NULL values", true);
        addPanel.add(addColNullableCheck, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.3;
        addPanel.add(new JBLabel("Default value:"), gbc);
        gbc.gridx = 1; gbc.gridy = 4; gbc.weightx = 0.7;
        addColDefaultField = DatabaseInputs.textField();
        addColDefaultField.setToolTipText("Use TEXT: for a literal, SQL: for an expression; blank removes the default.");
        addPanel.add(addColDefaultField, gbc);

        JButton executeAddBtn = new JButton("Add column");
        executeAddBtn.addActionListener(e -> doAddColumn());
        gbc.gridx = 1; gbc.gridy = 5;
        addPanel.add(executeAddBtn, gbc);

        // 2. Rename column Panel
        JPanel renameColPanel = new JPanel(new GridBagLayout());
        GridBagConstraints rcgbc = new GridBagConstraints();
        rcgbc.insets = new Insets(8, 8, 8, 8);
        rcgbc.fill = GridBagConstraints.HORIZONTAL;

        rcgbc.gridx = 0; rcgbc.gridy = 0; rcgbc.weightx = 0.3;
        renameColPanel.add(new JBLabel("Column:"), rcgbc);
        rcgbc.gridx = 1; rcgbc.gridy = 0; rcgbc.weightx = 0.7;
        renameColCombo = DatabaseInputs.comboBox(colNames);
        renameColPanel.add(renameColCombo, rcgbc);

        rcgbc.gridx = 0; rcgbc.gridy = 1; rcgbc.weightx = 0.3;
        renameColPanel.add(new JBLabel("New Column name:"), rcgbc);
        rcgbc.gridx = 1; rcgbc.gridy = 1; rcgbc.weightx = 0.7;
        renameColNewNameField = DatabaseInputs.textField(colNames.length > 0 ? colNames[0] : "");
        renameColPanel.add(renameColNewNameField, rcgbc);

        renameColCombo.addActionListener(e -> {
            String sel = (String) renameColCombo.getSelectedItem();
            if (sel != null) {
                renameColNewNameField.setText(sel);
            }
        });

        JButton executeRenameColBtn = new JButton("Rename column");
        executeRenameColBtn.addActionListener(e -> doRenameColumn());
        rcgbc.gridx = 1; rcgbc.gridy = 2;
        renameColPanel.add(executeRenameColBtn, rcgbc);

        // 3. Modify column Panel
        JPanel modifyColPanel = new JPanel(new GridBagLayout());
        GridBagConstraints mcgbc = new GridBagConstraints();
        mcgbc.insets = new Insets(6, 6, 6, 6);
        mcgbc.fill = GridBagConstraints.HORIZONTAL;

        mcgbc.gridx = 0; mcgbc.gridy = 0; mcgbc.weightx = 0.3;
        modifyColPanel.add(new JBLabel("Column:"), mcgbc);
        mcgbc.gridx = 1; mcgbc.gridy = 0; mcgbc.weightx = 0.7;
        modifyColCombo = DatabaseInputs.comboBox(colNames);
        modifyColPanel.add(modifyColCombo, mcgbc);

        mcgbc.gridx = 0; mcgbc.gridy = 1; mcgbc.weightx = 0.3;
        modifyColPanel.add(new JBLabel("Type:"), mcgbc);
        mcgbc.gridx = 1; mcgbc.gridy = 1; mcgbc.weightx = 0.7;
        modifyColTypeCombo = DatabaseInputs.comboBox(DATA_TYPES);
        modifyColTypeCombo.setEditable(true);
        modifyColPanel.add(modifyColTypeCombo, mcgbc);

        mcgbc.gridx = 0; mcgbc.gridy = 2; mcgbc.weightx = 0.3;
        modifyColPanel.add(new JBLabel("Size / length:"), mcgbc);
        mcgbc.gridx = 1; mcgbc.gridy = 2; mcgbc.weightx = 0.7;
        modifyColSizeField = DatabaseInputs.textField("255");
        modifyColPanel.add(modifyColSizeField, mcgbc);

        mcgbc.gridx = 0; mcgbc.gridy = 3; mcgbc.weightx = 0.3;
        modifyColPanel.add(new JBLabel("Nullable:"), mcgbc);
        mcgbc.gridx = 1; mcgbc.gridy = 3; mcgbc.weightx = 0.7;
        modifyColNullableCheck = new JBCheckBox("Allow NULL values", true);
        modifyColPanel.add(modifyColNullableCheck, mcgbc);

        mcgbc.gridx = 0; mcgbc.gridy = 4; mcgbc.weightx = 0.3;
        modifyColPanel.add(new JBLabel("Default value:"), mcgbc);
        mcgbc.gridx = 1; mcgbc.gridy = 4; mcgbc.weightx = 0.7;
        modifyColDefaultField = DatabaseInputs.textField();
        modifyColDefaultField.setToolTipText("Use TEXT: for a literal, SQL: for an expression; blank removes the default.");
        modifyColPanel.add(modifyColDefaultField, mcgbc);

        modifyColCombo.addActionListener(e -> {
            String sel = (String) modifyColCombo.getSelectedItem();
            if (sel != null) {
                populateModifyFields(sel);
            }
        });
        if (colNames.length > 0) {
            populateModifyFields(colNames[0]);
        }

        JButton executeModifyColBtn = new JButton("Apply column changes");
        executeModifyColBtn.addActionListener(e -> doModifyColumn());
        mcgbc.gridx = 1; mcgbc.gridy = 5;
        modifyColPanel.add(executeModifyColBtn, mcgbc);

        // 4. Drop column Panel
        JPanel dropPanel = new JPanel(new GridBagLayout());
        GridBagConstraints dgbc = new GridBagConstraints();
        dgbc.insets = new Insets(8, 8, 8, 8);
        dgbc.fill = GridBagConstraints.HORIZONTAL;

        dgbc.gridx = 0; dgbc.gridy = 0; dgbc.weightx = 0.3;
        dropPanel.add(new JBLabel("Column to drop:"), dgbc);
        dgbc.gridx = 1; dgbc.gridy = 0; dgbc.weightx = 0.7;
        dropColCombo = DatabaseInputs.comboBox(colNames);
        dropPanel.add(dropColCombo, dgbc);

        JButton executeDropBtn = new JButton("Drop column");
        executeDropBtn.setIcon(com.intellij.icons.AllIcons.General.Warning);
        executeDropBtn.addActionListener(e -> doDropColumn());
        dgbc.gridx = 1; dgbc.gridy = 1;
        dropPanel.add(executeDropBtn, dgbc);
        dgbc.gridy = 2;
        JBLabel warning = new JBLabel("<html>Dropping a column permanently deletes its values.<br>You will be asked to confirm.</html>");
        warning.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        dropPanel.add(warning, dgbc);

        // 5. Rename table Panel
        JPanel renamePanel = new JPanel(new GridBagLayout());
        GridBagConstraints rgbc = new GridBagConstraints();
        rgbc.insets = new Insets(8, 8, 8, 8);
        rgbc.fill = GridBagConstraints.HORIZONTAL;

        rgbc.gridx = 0; rgbc.gridy = 0; rgbc.weightx = 0.3;
        renamePanel.add(new JBLabel("New Table name:"), rgbc);
        rgbc.gridx = 1; rgbc.gridy = 0; rgbc.weightx = 0.7;
        renameTableField = DatabaseInputs.textField(tableMetadata.getName());
        renamePanel.add(renameTableField, rgbc);

        JButton executeRenameBtn = new JButton("Rename table");
        executeRenameBtn.addActionListener(e -> doRenameTable());
        rgbc.gridx = 1; rgbc.gridy = 1;
        renamePanel.add(executeRenameBtn, rgbc);

        // Content Cards
        CardLayout cardLayout = new CardLayout();
        JPanel contentCards = new JPanel(cardLayout);
        contentCards.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        contentCards.add(addPanel, "ADD");
        contentCards.add(renameColPanel, "RENAME_COL");
        contentCards.add(modifyColPanel, "MODIFY_COL");
        contentCards.add(dropPanel, "DROP_COL");
        contentCards.add(renamePanel, "RENAME_TABLE");

        // Custom Horizontal Tab Bar with Scrollbar
        JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tabBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JBScrollPane tabScrollPane = new JBScrollPane(tabBar);
        tabScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tabScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        tabScrollPane.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new JBColor(new Color(210, 210, 210), new Color(60, 63, 65))));
        tabScrollPane.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 6));

        String[] tabLabels = {"Add column", "Rename column", "Modify column", "Drop column", "Rename table"};
        String[] tabKeys = {"ADD", "RENAME_COL", "MODIFY_COL", "DROP_COL", "RENAME_TABLE"};
        JButton[] tabButtons = new JButton[tabLabels.length];

        JBColor activeFg = new JBColor(new Color(53, 116, 240), new Color(92, 155, 255));
        JBColor normalFg = new JBColor(new Color(70, 70, 70), new Color(185, 185, 185));
        JBColor activeBorder = new JBColor(new Color(53, 116, 240), new Color(92, 155, 255));

        for (int i = 0; i < tabLabels.length; i++) {
            final int tabIdx = i;
            final String key = tabKeys[i];
            JButton btn = new JButton(tabLabels[i]);
            btn.setFocusable(false);
            btn.setContentAreaFilled(false);
            btn.setOpaque(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setFont(btn.getFont().deriveFont(i == 0 ? Font.BOLD : Font.PLAIN, 12.5f));
            btn.setForeground(i == 0 ? activeFg : normalFg);
            btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, i == 0 ? 3 : 0, 0, activeBorder),
                    BorderFactory.createEmptyBorder(6, 12, i == 0 ? 4 : 7, 12)
            ));

            btn.addActionListener(e -> {
                cardLayout.show(contentCards, key);
                for (int j = 0; j < tabButtons.length; j++) {
                    boolean isSel = (j == tabIdx);
                    tabButtons[j].setFont(tabButtons[j].getFont().deriveFont(isSel ? Font.BOLD : Font.PLAIN, 12.5f));
                    tabButtons[j].setForeground(isSel ? activeFg : normalFg);
                    tabButtons[j].setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 0, isSel ? 3 : 0, 0, activeBorder),
                            BorderFactory.createEmptyBorder(6, 12, isSel ? 4 : 7, 12)
                    ));
                }
            });

            tabButtons[i] = btn;
            tabBar.add(btn);
        }

        root.add(tabScrollPane, BorderLayout.NORTH);
        root.add(contentCards, BorderLayout.CENTER);
        return root;
    }

    private void populateModifyFields(String colName) {
        if (colName == null) return;
        ColumnMetadata cm = tableMetadata.getColumn(colName);
        if (cm != null) {
            String typeName = cm.getTypeName();
            if (typeName != null) {
                modifyColTypeCombo.setSelectedItem(typeName);
            }
            modifyColSizeField.setText(cm.getColumnSize() > 0 ? String.valueOf(cm.getColumnSize()) : "255");
            modifyColNullableCheck.setSelected(cm.isNullable());
            modifyColDefaultField.setText(cm.getDefaultValue() != null ? cm.getDefaultValue() : "");
        }
    }

    private void refreshDropdowns() {
        String[] colNames = tableMetadata.getColumns().stream().map(ColumnMetadata::getName).toArray(String[]::new);
        if (renameColCombo != null) {
            renameColCombo.setModel(new DefaultComboBoxModel<>(colNames));
            if (colNames.length > 0) {
                renameColCombo.setSelectedIndex(0);
                renameColNewNameField.setText(colNames[0]);
            }
        }
        if (modifyColCombo != null) {
            modifyColCombo.setModel(new DefaultComboBoxModel<>(colNames));
            if (colNames.length > 0) {
                modifyColCombo.setSelectedIndex(0);
                populateModifyFields(colNames[0]);
            }
        }
        if (dropColCombo != null) {
            dropColCombo.setModel(new DefaultComboBoxModel<>(colNames));
        }
    }

    @FunctionalInterface private interface AlterOperation { void execute(Connection connection) throws Exception; }
    private void setBusy(boolean value) {
        busy=value; setOKActionEnabled(!value);
        if (value) disableControls(centerPanel);
        else { enabledStates.forEach(Component::setEnabled); enabledStates.clear(); }
    }
    private void disableControls(Component component) {
        enabledStates.put(component,component.isEnabled()); component.setEnabled(false);
        if (component instanceof Container container) for(Component child:container.getComponents()) disableControls(child);
    }
    private void runAlter(AlterOperation operation, String metadataName, String success) {
        if (busy || isDisposed()) return;
        setBusy(true);
        operationTask = com.segfault03.ideadb.service.DatabaseTaskService.getInstance().submit(() -> {
            if (isDisposed()) return;
            try (Connection conn = DatabaseConnectionManager.getInstance().openConnection(config)) {
                activeConnection=conn;
                if (isDisposed()) return;
                operation.execute(conn);
                List<ColumnMetadata> columns = MetadataService.getInstance().getColumns(conn,config,databaseName,metadataName);
                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    tableMetadata.setColumns(columns); setBusy(false); refreshDropdowns();
                    if (success != null) { Messages.showInfoMessage(project,success,"Table updated"); close(OK_EXIT_CODE); }
                });
            } catch(Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (isDisposed()) return;
                    setBusy(false); Messages.showErrorDialog(project,error.getMessage(),"Database Operation Failed");
                });
            } finally { activeConnection=null; }
        });
    }
    @Override protected void dispose() {
        if (operationTask != null) operationTask.cancel(true);
        Connection connection=activeConnection;
        if (connection != null) com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { connection.close(); } catch(java.sql.SQLException ignored) { }
        });
        super.dispose();
    }

    private void doAddColumn() {
        if (busy || isDisposed()) return;
        String colName = addColNameField.getText().trim();
        if (colName.isEmpty()) {
            Messages.showErrorDialog(project, "Column name cannot be empty", "Validation Error");
            return;
        }

        int size = 0;
        try {
            size = Integer.parseInt(addColSizeField.getText().trim());
        } catch (Exception ignored) {
        }

        ColumnDefinition col = new ColumnDefinition(colName, (String) addColTypeCombo.getSelectedItem(), size,
                addColNullableCheck.isSelected(), false, false, addColDefaultField.getText().trim());

        runAlter(conn -> DdlService.getInstance().alterTableAddColumn(conn, config, databaseName, tableMetadata.getName(), col), tableMetadata.getName(), "Column '" + colName + "' added successfully.");
    }

    private void doRenameColumn() {
        if (busy || isDisposed()) return;
        String oldCol = (String) renameColCombo.getSelectedItem();
        String newCol = renameColNewNameField.getText().trim();
        if (oldCol == null || newCol.isEmpty()) {
            Messages.showErrorDialog(project, "Please enter a valid new column name.", "Validation Error");
            return;
        }
        if (oldCol.equals(newCol)) {
            Messages.showWarningDialog(project, "New column name is identical to the current column name.", "No Change");
            return;
        }

        runAlter(conn -> DdlService.getInstance().alterTableRenameColumn(conn, config, databaseName, tableMetadata.getName(), oldCol, newCol), tableMetadata.getName(), "Column '" + oldCol + "' renamed to '" + newCol + "' successfully.");
    }

    private void doModifyColumn() {
        if (busy || isDisposed()) return;
        String colName = (String) modifyColCombo.getSelectedItem();
        if (colName == null) return;

        int size = 0;
        try {
            size = Integer.parseInt(modifyColSizeField.getText().trim());
        } catch (Exception ignored) {
        }

        ColumnDefinition col = new ColumnDefinition(colName, (String) modifyColTypeCombo.getSelectedItem(), size,
                modifyColNullableCheck.isSelected(), false, false, modifyColDefaultField.getText().trim());
        ColumnMetadata original = tableMetadata.getColumn(colName);
        if (original != null) {
            col.setAutoIncrement(original.isAutoIncrement());
            col.setDecimalDigits(original.getDecimalDigits());
        }

        runAlter(conn -> DdlService.getInstance().alterTableModifyColumn(conn, config, databaseName, tableMetadata.getName(), col), tableMetadata.getName(), "Column '" + colName + "' definition updated successfully.");
    }

    private void doDropColumn() {
        if (busy || isDisposed()) return;
        String colName = (String) dropColCombo.getSelectedItem();
        if (colName == null) return;

        if (!com.segfault03.ideadb.ui.DatabaseUi.confirmDestructive(project, "Drop column",
                "Drop column '" + colName + "' from '" + tableMetadata.getName() + "'?\nThe column and all its values will be permanently deleted.", "Drop column")) return;

        runAlter(conn -> DdlService.getInstance().alterTableDropColumn(conn, config, databaseName, tableMetadata.getName(), colName), tableMetadata.getName(), "Column '" + colName + "' dropped successfully.");
    }

    private void doRenameTable() {
        if (busy || isDisposed()) return;
        String newName = renameTableField.getText().trim();
        if (newName.isEmpty() || newName.equals(tableMetadata.getName())) {
            Messages.showErrorDialog(project, "Please enter a new table name", "Validation Error");
            return;
        }

        runAlter(conn -> {
            TableMetadata renamed=DdlService.getInstance().alterTableRename(conn,config,databaseName,tableMetadata.getName(),newName);
            SwingUtilities.invokeLater(() -> {
                if(project!=null && !project.isDisposed()) project.getMessageBus().syncPublisher(com.segfault03.ideadb.editor.TableRenameListener.TOPIC).tableRenamed(config,databaseName,tableMetadata.getName(),renamed);
            });
        }, newName, "Table renamed to '" + newName + "' successfully.");
    }

}
