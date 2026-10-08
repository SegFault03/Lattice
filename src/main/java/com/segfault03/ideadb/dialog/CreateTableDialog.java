package com.segfault03.ideadb.dialog;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.segfault03.ideadb.model.ColumnDefinition;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.DdlService;
import org.jetbrains.annotations.Nullable;

import com.segfault03.ideadb.ui.DatabaseInputs;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class CreateTableDialog extends DialogWrapper {
    private final ConnectionConfig config;
    private final String databaseName;

    private JBTextField tableNameField;
    private JBTable columnsTable;
    private ColumnsTableModel tableModel;
    private JBTextArea sqlPreviewArea;

    private static final String[] DATA_TYPES = {
            "INT", "BIGINT", "VARCHAR", "TEXT", "BOOLEAN", "DECIMAL", "DOUBLE", "DATE", "TIMESTAMP", "BLOB"
    };

    public CreateTableDialog(@Nullable Project project, ConnectionConfig config, String databaseName) {
        super(project, true);
        this.config = config;
        this.databaseName = databaseName;
        setTitle("Create table in " + databaseName);
        setOKButtonText("Create table");
        setResizable(true);
        init();
        updatePreview();
    }

    public String getTableName() {
        return tableNameField.getText().trim();
    }

    public List<ColumnDefinition> getColumns() {
        return tableModel.getColumns();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setPreferredSize(JBUI.size(800, 520));
        root.setMinimumSize(JBUI.size(720, 400));

        // Top: Table Name
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.2;
        topPanel.add(new JBLabel("Table name:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.8;
        tableNameField = DatabaseInputs.textField("new_table");
        topPanel.add(tableNameField, gbc);

        root.add(topPanel, BorderLayout.NORTH);

        // Center: Columns Table + Toolbar
        JPanel centerPanel = new JPanel(new BorderLayout(4, 4));
        centerPanel.setBorder(BorderFactory.createTitledBorder("Columns Definition"));

        List<ColumnDefinition> defaultCols = new ArrayList<>();
        defaultCols.add(new ColumnDefinition("id", "INT", 11, false, true, true, ""));
        defaultCols.add(new ColumnDefinition("name", "VARCHAR", 255, false, false, false, ""));
        defaultCols.add(new ColumnDefinition("created_at", "TIMESTAMP", 0, true, false, false, ""));

        tableModel = new ColumnsTableModel(defaultCols);
        columnsTable = new JBTable(tableModel);
        columnsTable.setRowHeight(com.intellij.util.ui.JBUI.scale(28));
        DatabaseInputs.styleTableEditors(columnsTable);

        // Setup combo box editor for Type column
        JComboBox<String> typeEditor = DatabaseInputs.comboBox(DATA_TYPES);
        columnsTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(typeEditor));
        sizeColumnHeaders();

        JBScrollPane scrollPane = new JBScrollPane(columnsTable);
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        // Action buttons
        JPanel tableButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton addBtn = new JButton("Add column");
        JButton remBtn = new JButton("Remove Column");
        JButton upBtn = new JButton("Move Up");
        JButton downBtn = new JButton("Move Down");

        addBtn.addActionListener(e -> {
            tableModel.addColumn(new ColumnDefinition("col_" + (tableModel.getRowCount() + 1), "VARCHAR", 255, true, false, false, ""));
            updatePreview();
        });

        remBtn.addActionListener(e -> {
            int selected = columnsTable.getSelectedRow();
            if (selected >= 0 && tableModel.getRowCount() > 1) {
                tableModel.removeColumn(selected);
                updatePreview();
            }
        });

        upBtn.addActionListener(e -> {
            int selected = columnsTable.getSelectedRow();
            if (selected > 0) {
                tableModel.moveUp(selected);
                columnsTable.setRowSelectionInterval(selected - 1, selected - 1);
                updatePreview();
            }
        });

        downBtn.addActionListener(e -> {
            int selected = columnsTable.getSelectedRow();
            if (selected >= 0 && selected < tableModel.getRowCount() - 1) {
                tableModel.moveDown(selected);
                columnsTable.setRowSelectionInterval(selected + 1, selected + 1);
                updatePreview();
            }
        });

        tableButtons.add(addBtn);
        tableButtons.add(remBtn);
        tableButtons.add(upBtn);
        tableButtons.add(downBtn);
        centerPanel.add(tableButtons, BorderLayout.SOUTH);

        root.add(centerPanel, BorderLayout.CENTER);

        // South: SQL Preview
        JPanel previewPanel = new JPanel(new BorderLayout(4, 4));
        previewPanel.setBorder(BorderFactory.createTitledBorder("SQL DDL Preview"));
        sqlPreviewArea = new JBTextArea(8, 40);
        sqlPreviewArea.setEditable(false);
        sqlPreviewArea.getAccessibleContext().setAccessibleName("Create table SQL preview");
        sqlPreviewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        previewPanel.add(new JBScrollPane(sqlPreviewArea), BorderLayout.CENTER);

        root.add(previewPanel, BorderLayout.SOUTH);

        tableNameField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updatePreview(); }
            public void removeUpdate(DocumentEvent e) { updatePreview(); }
            public void changedUpdate(DocumentEvent e) { updatePreview(); }
        });

        tableModel.addTableModelListener((TableModelEvent e) -> updatePreview());

        return root;
    }

    private void updatePreview() {
        if (sqlPreviewArea == null || tableNameField == null || tableModel == null) return;
        String tName = tableNameField.getText().trim();
        if (tName.isEmpty()) tName = "unnamed_table";
        String ddl = DdlService.getInstance().buildCreateTableSql(config, databaseName, tName, tableModel.getColumns());
        sqlPreviewArea.setText(ddl);
        sqlPreviewArea.setCaretPosition(0);
    }

    private void sizeColumnHeaders() {
        int[] preferred = {150, 110, 50, 80, 42, 86, 160};
        for (int i = 0; i < columnsTable.getColumnCount(); i++) {
            var column = columnsTable.getColumnModel().getColumn(i);
            var renderer = column.getHeaderRenderer();
            if (renderer == null) renderer = columnsTable.getTableHeader().getDefaultRenderer();
            Component header = renderer.getTableCellRendererComponent(columnsTable, column.getHeaderValue(), false, false, -1, i);
            int minimum = header.getPreferredSize().width + JBUI.scale(16);
            column.setMinWidth(minimum);
            column.setPreferredWidth(Math.max(minimum, JBUI.scale(preferred[i])));
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String tName = tableNameField.getText().trim();
        if (tName.isEmpty()) {
            return new ValidationInfo("Enter a table name", tableNameField);
        }
        if (tableModel.getColumns().isEmpty()) {
            return new ValidationInfo("Add at least one column");
        }
        for (ColumnDefinition col : tableModel.getColumns()) {
            if (col.getName().trim().isEmpty()) {
                return new ValidationInfo("Enter a name for every column");
            }
        }
        return null;
    }

    private static class ColumnsTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"Column Name", "Type", "Size", "Nullable", "PK", "Auto Inc", "Default"};
        private final List<ColumnDefinition> list;

        public ColumnsTableModel(List<ColumnDefinition> list) {
            this.list = list;
        }

        public List<ColumnDefinition> getColumns() { return list; }

        public void addColumn(ColumnDefinition col) {
            list.add(col);
            fireTableRowsInserted(list.size() - 1, list.size() - 1);
        }

        public void removeColumn(int index) {
            list.remove(index);
            fireTableRowsDeleted(index, index);
        }

        public void moveUp(int index) {
            ColumnDefinition temp = list.remove(index);
            list.add(index - 1, temp);
            fireTableRowsUpdated(index - 1, index);
        }

        public void moveDown(int index) {
            ColumnDefinition temp = list.remove(index);
            list.add(index + 1, temp);
            fireTableRowsUpdated(index, index + 1);
        }

        @Override
        public int getRowCount() { return list.size(); }
        @Override
        public int getColumnCount() { return COLUMNS.length; }
        @Override
        public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case 2: return Integer.class;
                case 3:
                case 4:
                case 5: return Boolean.class;
                default: return String.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ColumnDefinition col = list.get(rowIndex);
            switch (columnIndex) {
                case 0: return col.getName();
                case 1: return col.getType();
                case 2: return col.getSize();
                case 3: return col.isNullable();
                case 4: return col.isPrimaryKey();
                case 5: return col.isAutoIncrement();
                case 6: return col.getDefaultValue();
                default: return "";
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            ColumnDefinition col = list.get(rowIndex);
            switch (columnIndex) {
                case 0: col.setName(aValue != null ? aValue.toString().trim() : ""); break;
                case 1: col.setType(aValue != null ? aValue.toString() : "VARCHAR"); break;
                case 2:
                    try {
                        col.setSize(aValue instanceof Integer ? (Integer) aValue : Integer.parseInt(aValue.toString()));
                    } catch (Exception ignored) { col.setSize(0); }
                    break;
                case 3: col.setNullable(Boolean.TRUE.equals(aValue)); break;
                case 4: col.setPrimaryKey(Boolean.TRUE.equals(aValue)); break;
                case 5: col.setAutoIncrement(Boolean.TRUE.equals(aValue)); break;
                case 6: col.setDefaultValue(aValue != null ? aValue.toString() : ""); break;
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
