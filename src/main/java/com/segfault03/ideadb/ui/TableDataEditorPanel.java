package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.QueryResult;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.model.RowIdentity;
import com.segfault03.ideadb.model.RowDefaults;
import com.segfault03.ideadb.model.CellValueConverter;
import com.segfault03.ideadb.service.DataService;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.DdlService;
import com.segfault03.ideadb.service.ExportService;
import com.segfault03.ideadb.service.MetadataService;
import com.segfault03.ideadb.state.TableDraftState;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public class TableDataEditorPanel extends JPanel implements AutoCloseable {
    private final com.segfault03.ideadb.service.DatabaseTaskScope tasks=com.segfault03.ideadb.service.DatabaseTaskService.getInstance().newScope();
    private final Project project;
    private final ConnectionConfig config;
    private final String databaseName;
    private final TableMetadata tableMetadata;

    private JBTextField whereField;
    private JBTextField orderField;
    private JComboBox<String> pageSizeCombo;
    private JComboBox<String> autoRefreshCombo;
    private javax.swing.Timer autoRefreshTimer;

    private JButton prevPageBtn;
    private JButton nextPageBtn;
    private JBLabel pageLabel;
    private JBLabel statusLabel;
    private JButton saveBtn;
    private JButton revertBtn;

    private DatabaseTable dataTable;
    private EditableTableModel tableModel;
    private WrappingLabel validationDetails;

    private int currentPage = 1;
    private int pageSize = 100;
    private long totalRowCount = -1;
    private String appliedWhere = "";
    private enum ExportScope { PAGE, SELECTED, ALL_PERSISTED }
    private boolean mutationRunning;
    private long loadGeneration;
    private volatile boolean disposed;
    private boolean wasModified;

    public TableDataEditorPanel(Project project, ConnectionConfig config, String databaseName, TableMetadata tableMetadata) {
        super(new WidthAwareBorderLayout());
        this.project = project;
        this.config = config;
        this.databaseName = databaseName;
        this.tableMetadata = tableMetadata;

        initUI();
        TableDraftState.Draft draft = project == null ? null : TableDraftState.getInstance(project).get(draftKey());
        if (draft == null) loadData();
        else {
            tableModel.restoreDraft(draft);
            statusLabel.setText("Restored pending edits. Commit or Revert before reloading.");
            tasks.submit(() -> {
                try (var read = tasks.openRead(config)) {
                    Connection conn=read.connection();
                    List<ColumnMetadata> columns = MetadataService.getInstance().getColumns(conn, config, databaseName, tableMetadata.getName());
                    SwingUtilities.invokeLater(() -> {
                        if (disposed) return;
                        tableMetadata.setColumns(columns);
                        tableModel.refreshColumnMetadata();
                        updatePendingChangesState();
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> { if (!disposed) statusLabel.setText("Draft preserved; metadata unavailable: " + e.getMessage()); });
                }
            });
        }
    }

    private void makeCompactButton(AbstractButton button) {
        button.setMargin(JBUI.insets(1, 5));
        button.setFocusable(false);
    }

    private void initUI() {
        JPanel heading = new JPanel(new WidthAwareBorderLayout());

        JButton refreshBtn = DatabaseUi.action("", AllIcons.Actions.Refresh, "Refresh table data");
        refreshBtn.addActionListener(e -> loadData());
        autoRefreshCombo = DatabaseInputs.comboBox(new String[]{"Auto: Off", "10s", "15s", "20s", "30s", "60s"});
        autoRefreshCombo.setToolTipText("Periodic auto-refresh interval");
        autoRefreshCombo.addActionListener(e -> onAutoRefreshChanged());

        whereField = DatabaseInputs.textField(20);
        whereField.setToolTipText("SQL WHERE condition; press Enter to apply");
        whereField.addActionListener(e -> loadData(1, pageSize));
        orderField = DatabaseInputs.textField(14);
        orderField.setToolTipText("SQL ORDER BY clause; blank uses the primary key when available");
        orderField.addActionListener(e -> loadData(1, pageSize));
        JButton filterBtn = DatabaseUi.action("Apply", AllIcons.Actions.Execute, "Apply filter and sort");
        filterBtn.addActionListener(e -> loadData(1, pageSize));
        JBLabel whereLabel = new JBLabel("WHERE");
        JBLabel orderLabel = new JBLabel("ORDER BY");
        int filterLabelWidth = Math.max(whereLabel.getPreferredSize().width, orderLabel.getPreferredSize().width);
        whereLabel.setPreferredSize(new Dimension(filterLabelWidth, whereLabel.getPreferredSize().height));
        orderLabel.setPreferredSize(new Dimension(filterLabelWidth, orderLabel.getPreferredSize().height));
        JPanel filters = new TableFilterPanel(DatabaseUi.labeledInput(whereLabel, whereField),
                DatabaseUi.labeledInput(orderLabel, orderField), filterBtn);
        filters.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR),
                JBUI.Borders.empty(2, 12, 6, 12)));

        pageSizeCombo = DatabaseInputs.comboBox(new String[]{"50", "100", "250", "500", "1000"});
        pageSizeCombo.setSelectedItem("100");
        pageSizeCombo.setToolTipText("Rows per page");
        pageSizeCombo.addActionListener(e -> loadData(1, Integer.parseInt((String) pageSizeCombo.getSelectedItem())));
        prevPageBtn = DatabaseUi.action("", AllIcons.Actions.Back, "Previous page");
        prevPageBtn.setEnabled(false);
        prevPageBtn.addActionListener(e -> { if (currentPage > 1) loadData(currentPage - 1, pageSize); });
        pageLabel = new JBLabel("Page 1");
        nextPageBtn = DatabaseUi.action("", AllIcons.Actions.Forward, "Next page");
        nextPageBtn.addActionListener(e -> loadData(currentPage + 1, pageSize));

        JButton addRowBtn = DatabaseUi.action("", AllIcons.General.Add, "Add a new row");
        addRowBtn.addActionListener(e -> {
            if (tableModel != null && !mutationRunning) {
                tableModel.addNewRow();
                updatePendingChangesState();
            }
        });

        JButton delRowBtn = DatabaseUi.action("", AllIcons.General.Remove, "Delete selected rows");
        delRowBtn.setToolTipText("Delete selected rows immediately after confirmation");
        delRowBtn.addActionListener(e -> deleteSelectedRows());
        JMenuItem nullBtn=new JMenuItem("Set to NULL");
        nullBtn.setToolTipText("Set the selected column to SQL NULL for selected rows");
        nullBtn.addActionListener(event -> {
            if(mutationRunning || disposed || !finishCellEditing()) return;
            int selectedColumn=dataTable.getSelectedColumn(); if(selectedColumn<0) return;
            int column=dataTable.convertColumnIndexToModel(selectedColumn);
            for(int selected:dataTable.getSelectedRows()) {
                int row=dataTable.convertRowIndexToModel(selected);
                if(tableModel.isCellEditable(row,column)) tableModel.setValueAt(null,row,column);
            }
        });
        JMenuItem defaultBtn=new JMenuItem("Use database default");
        defaultBtn.setToolTipText("Use the database default for a selected new-row cell");
        defaultBtn.addActionListener(event -> {
            if(mutationRunning || disposed || !finishCellEditing()) return;
            int selectedColumn=dataTable.getSelectedColumn(); if(selectedColumn<0) return;
            int column=dataTable.convertColumnIndexToModel(selectedColumn);
            ColumnMetadata metadata=tableModel.getColumnMeta(column);
            if(metadata==null || metadata.getDefaultValue()==null && !metadata.isAutoIncrement()) return;
            for(int selected:dataTable.getSelectedRows()) {
                int row=dataTable.convertRowIndexToModel(selected);
                if(tableModel.isRowNew(row) && tableModel.isCellEditable(row,column)) tableModel.setValueAt(RowDefaults.initialValue(metadata),row,column);
            }
        });
        saveBtn = DatabaseUi.action("Commit", AllIcons.Actions.Checked, "Commit pending changes to the database");
        saveBtn.setBorder(JBUI.Borders.empty(4, 7));
        saveBtn.setIconTextGap(JBUI.scale(3));
        saveBtn.setEnabled(false);
        saveBtn.addActionListener(e -> commitChanges());
        revertBtn = DatabaseUi.action("Revert", AllIcons.Actions.Rollback, "Revert pending changes");
        revertBtn.setBorder(JBUI.Borders.empty(4, 7));
        revertBtn.setIconTextGap(JBUI.scale(3));
        revertBtn.setEnabled(false);
        revertBtn.addActionListener(e -> {
            if (mutationRunning) return;
            if (dataTable.isEditing()) dataTable.getCellEditor().cancelCellEditing();
            tableModel.setData(tableModel.columns, tableModel.types, tableModel.originalRows);
            updatePendingChangesState();
            loadData();
        });

        JButton exportBtn = DatabaseUi.action("Export", AllIcons.ToolbarDecorator.Export, "Export data");
        exportBtn.setBorder(JBUI.Borders.empty(4, 3));
        exportBtn.setIconTextGap(JBUI.scale(3));
        exportBtn.setToolTipText("Choose current page, selected rows, or all persisted rows for export");
        exportBtn.addActionListener(e -> showExportMenu((Component) e.getSource()));

        JMenuItem truncateBtn = new JMenuItem("Truncate table…", AllIcons.General.Remove);
        truncateBtn.setToolTipText("Truncate table (permanently delete all rows)");
        truncateBtn.addActionListener(e -> truncateCurrentTable());

        JButton consoleBtn = DatabaseUi.action("", Icons.CONSOLE, "Open SQL console");
        consoleBtn.setToolTipText("Open interactive query console for " + (databaseName != null ? databaseName : "this database"));
        consoleBtn.addActionListener(e -> openSqlConsole());

        JButton optionsBtn = DatabaseUi.action("", AllIcons.Actions.MoreHorizontal, "Table options");
        JPopupMenu options = new JPopupMenu();
        JMenuItem countItem = new JMenuItem("Count rows");
        countItem.addActionListener(e -> countRows());
        options.add(countItem);
        options.addSeparator();
        options.add(truncateBtn);
        optionsBtn.addActionListener(e -> {
            Component invoker = (Component) e.getSource();
            options.show(invoker, 0, invoker.getHeight());
        });
        JComponent toolbar = DatabaseActionToolbar.create(this, "Table actions",
                refreshBtn, null, addRowBtn, delRowBtn, null, saveBtn, revertBtn, null,
                exportBtn, consoleBtn, optionsBtn, null, autoRefreshCombo);
        heading.add(toolbar, BorderLayout.NORTH);
        heading.add(filters, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);

        tableModel = new EditableTableModel();
        dataTable = new DatabaseTable(tableModel);
        dataTable.setDefaultRenderer(Object.class, new CellHighlightRenderer());
        JPopupMenu cellMenu = new JPopupMenu();
        cellMenu.add(nullBtn);
        cellMenu.add(defaultBtn);
        dataTable.setComponentPopupMenu(cellMenu);
        dataTable.addMouseListener(new MouseAdapter() {
            private void selectPopupCell(MouseEvent event) {
                if (!event.isPopupTrigger() || !finishCellEditing()) return;
                int row = dataTable.rowAtPoint(event.getPoint());
                int column = dataTable.columnAtPoint(event.getPoint());
                if (row >= 0 && column >= 0) {
                    if (!dataTable.isRowSelected(row)) dataTable.setRowSelectionInterval(row, row);
                    dataTable.setColumnSelectionInterval(column, column);
                } else {
                    dataTable.clearSelection();
                }
            }
            @Override public void mousePressed(MouseEvent event) { selectPopupCell(event); }
            @Override public void mouseReleased(MouseEvent event) { selectPopupCell(event); }
        });
        cellMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                int row = dataTable.getSelectedRow();
                int col = dataTable.getSelectedColumn();
                boolean editable = row >= 0 && col >= 0 && !mutationRunning && !disposed;
                int modelRow = row < 0 ? -1 : dataTable.convertRowIndexToModel(row);
                int modelCol = col < 0 ? -1 : dataTable.convertColumnIndexToModel(col);
                ColumnMetadata column = col < 0 ? null : tableModel.getColumnMeta(modelCol);
                nullBtn.setEnabled(editable && tableModel.isCellEditable(modelRow, modelCol) && column != null && column.isNullable());
                defaultBtn.setEnabled(editable && tableModel.isRowNew(modelRow) && tableModel.isCellEditable(modelRow, modelCol)
                        && column != null && (column.getDefaultValue() != null || column.isAutoIncrement()));
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {}
        });
        JBScrollPane scroll = dataTable.createScrollPane();
        add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new WidthAwareBorderLayout(0, JBUI.scale(4)));
        footer.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineTop(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR), JBUI.Borders.empty(4, 12)));
        statusLabel = new WrappingLabel("Ready");
        validationDetails = new WrappingLabel("");
        validationDetails.getAccessibleContext().setAccessibleName("Cell validation details");
        validationDetails.setVisible(false);
        JPanel navigation = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        navigation.setOpaque(false);
        navigation.add(DatabaseUi.group(new JBLabel("Rows"), pageSizeCombo));
        navigation.add(DatabaseUi.group(prevPageBtn, pageLabel, nextPageBtn));
        footer.add(statusLabel, BorderLayout.NORTH);
        JPanel detailsAndNavigation = new JPanel(new WidthAwareBorderLayout(0, JBUI.scale(4)));
        detailsAndNavigation.setOpaque(false);
        detailsAndNavigation.add(validationDetails, BorderLayout.NORTH);
        detailsAndNavigation.add(navigation, BorderLayout.CENTER);
        footer.add(detailsAndNavigation, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        dataTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateValidationDetails(tableModel.getValidationErrors());
        });
        dataTable.getColumnModel().getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateValidationDetails(tableModel.getValidationErrors());
        });
    }

    private void onAutoRefreshChanged() {
        if (autoRefreshTimer != null) {
            autoRefreshTimer.stop();
            autoRefreshTimer = null;
        }
        if (disposed || !isDisplayable()) return;
        String sel = (String) autoRefreshCombo.getSelectedItem();
        if (sel == null || sel.contains("Off")) {
            return;
        }
        int seconds = 0;
        if (sel.contains("10")) seconds = 10;
        else if (sel.contains("15")) seconds = 15;
        else if (sel.contains("20")) seconds = 20;
        else if (sel.contains("30")) seconds = 30;
        else if (sel.contains("60")) seconds = 60;

        if (seconds > 0) {
            autoRefreshTimer = new javax.swing.Timer(seconds * 1000, e -> {
                if (tableModel.hasPendingChanges()) {
                    DatabaseUi.status(statusLabel, "Auto-refresh paused · Commit or revert pending edits", DatabaseUi.Tone.WARNING);
                } else {
                    loadData(currentPage,pageSize,false);
                }
            });
            autoRefreshTimer.setRepeats(true);
            autoRefreshTimer.start();
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (autoRefreshCombo != null) onAutoRefreshChanged();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        if (autoRefreshTimer != null) {
            autoRefreshTimer.stop();
            autoRefreshTimer = null;
        }
    }

    public void loadData() { loadData(currentPage,pageSize,true); }

    private void loadData(int requestedPage,int requestedSize) { loadData(requestedPage,requestedSize,false); }
    private void loadData(int requestedPage, int requestedSize, boolean refreshMetadata) {
        if (mutationRunning || disposed || !finishCellEditing()) return;
        if (tableModel.hasPendingChanges()) {
            statusLabel.setText("Pending edits preserved. Commit or Revert before reloading.");
            return;
        }
        long generation = ++loadGeneration;
        String where = whereField.getText().trim();
        String order = orderField.getText().trim();
        List<ColumnMetadata> knownColumns=new ArrayList<>(tableMetadata.getColumns());
        long knownCount=!refreshMetadata && where.equals(appliedWhere) ? totalRowCount : -1;
        DatabaseUi.status(statusLabel, "Loading data…", DatabaseUi.Tone.BUSY);
        tasks.submit(() -> {
            try (var read = tasks.openRead(config)) {
                Connection conn=read.connection();

                // Keep table columns metadata up to date
                List<ColumnMetadata> cols = refreshMetadata || knownColumns.isEmpty() ? MetadataService.getInstance().getColumns(conn,config,databaseName,tableMetadata.getName()) : knownColumns;
                int offset = Math.multiplyExact(requestedPage - 1, requestedSize);
                List<String> keys=cols.stream().filter(ColumnMetadata::isPrimaryKey).map(ColumnMetadata::getName).toList();
                QueryResult fetched = DataService.getInstance().fetchData(conn,config,databaseName,tableMetadata.getName(),where,order,requestedSize+1,offset,keys);
                if(fetched.hasError()) throw new java.sql.SQLException(fetched.getError());
                if(fetched.isTruncated()) throw new java.sql.SQLException("Page exceeds the display limit; reduce the row limit.");
                boolean hasNext=fetched.getRows().size()>requestedSize;
                QueryResult result=QueryResult.forResultSet(fetched.getColumnNames(),fetched.getColumnTypes(),fetched.getRows().subList(0,Math.min(requestedSize,fetched.getRows().size())),fetched.getExecutionTimeMs());

                SwingUtilities.invokeLater(() -> {
                    if (disposed || generation != loadGeneration || mutationRunning || dataTable.isEditing() || tableModel.hasPendingChanges()) return;
                    tableMetadata.setColumns(cols);
                    totalRowCount = knownCount;
                    appliedWhere = where;
                    currentPage = requestedPage;
                    pageSize = requestedSize;
                    tableModel.setData(result.getColumnNames(), result.getColumnTypes(), result.getRows());
                    dataTable.sizeColumnsToContent();
                    updatePendingChangesState();

                    long maxPage = totalRowCount >= 0 ? Math.max(1,(totalRowCount + pageSize - 1) / pageSize) : -1;
                    prevPageBtn.setEnabled(currentPage > 1);
                    nextPageBtn.setEnabled(hasNext);
                    pageLabel.setText("Page " + currentPage + (maxPage > 0 ? " of " + maxPage : ""));

                    DatabaseUi.status(statusLabel, String.format("%s · %d ms · Total: %s",
                            QueryResult.formatRowCount(result.getRows().size()), result.getExecutionTimeMs(),
                            totalRowCount >= 0 ? String.valueOf(totalRowCount) : "not counted") + (keys.isEmpty() && order.isEmpty() ? " · No primary key: set ORDER BY for stable pages" : ""), keys.isEmpty() && order.isEmpty() ? DatabaseUi.Tone.WARNING : DatabaseUi.Tone.NORMAL);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed || generation != loadGeneration || mutationRunning) return;
                    DatabaseUi.status(statusLabel, "Could not load data · See error details", DatabaseUi.Tone.ERROR);
                    statusLabel.setToolTipText(ex.getMessage());
                    Messages.showErrorDialog(project, "Error: " + ex.getMessage(), "Data Fetch Error");
                });
            }
        });
    }

    private void countRows() {
        if(disposed || mutationRunning) return;
        long generation=loadGeneration; String where=appliedWhere;
        DatabaseUi.status(statusLabel, "Counting saved rows…", DatabaseUi.Tone.BUSY);
        tasks.submit(() -> {
            try(var read=tasks.openRead(config)) {
                long count=DataService.getInstance().countRows(read.connection(),config,databaseName,tableMetadata.getName(),where);
                SwingUtilities.invokeLater(() -> {
                    if(disposed || mutationRunning || generation!=loadGeneration) return;
                    totalRowCount=count;
                    long pages=Math.max(1,(count+pageSize-1)/pageSize);
                    pageLabel.setText("Page " + currentPage + " of " + pages);
                    DatabaseUi.status(statusLabel, count == 1 ? "1 saved row matches the filter" : count + " saved rows match the filter", DatabaseUi.Tone.NORMAL);
                });
            } catch(Exception error) {
                SwingUtilities.invokeLater(() -> { if(!disposed && generation==loadGeneration) DatabaseUi.status(statusLabel, "Row count failed: " + error.getMessage(), DatabaseUi.Tone.ERROR); });
            }
        });
    }

    private void updatePendingChangesState() {
        boolean hasPending = tableModel.hasPendingChanges();
        if (wasModified != hasPending) {
            firePropertyChange("pendingChanges", wasModified, hasPending);
            wasModified = hasPending;
        }
        if (project != null) {
            if (hasPending) TableDraftState.getInstance(project).put(draftKey(), tableModel.captureDraft());
            else TableDraftState.getInstance(project).remove(draftKey());
        }
        Map<CellCoord, String> errors = tableModel.getValidationErrors();
        boolean hasErrors = !errors.isEmpty();
        revertBtn.setEnabled(hasPending && !mutationRunning);
        DatabaseUi.setActionEmphasis(
                revertBtn,
                revertBtn.isEnabled() ? DatabaseUi.DESTRUCTIVE_ACTION_COLOR : null
        );

        if (hasErrors) {
            saveBtn.setEnabled(false);
            String firstError = errors.values().iterator().next();
            DatabaseUi.status(statusLabel, (errors.size() == 1 ? "1 invalid cell" : errors.size() + " invalid cells") + " · Fix before committing", DatabaseUi.Tone.ERROR);
            statusLabel.setToolTipText(firstError);
            saveBtn.setToolTipText("Fix invalid cells before committing");
            saveBtn.setText("Commit");
        } else {
            saveBtn.setEnabled(hasPending && !mutationRunning);
            saveBtn.setToolTipText("Commit pending changes to the database");
            String previousStatus = statusLabel.getText();
            if (previousStatus.contains("invalid cell") || previousStatus.contains("Fix before committing")) {
                DatabaseUi.status(statusLabel, " ", DatabaseUi.Tone.NORMAL);
                statusLabel.setToolTipText(null);
            } else {
                DatabaseUi.status(statusLabel, previousStatus, DatabaseUi.Tone.NORMAL);
            }
            if (hasPending) {
                saveBtn.setText("Commit");
                int count = tableModel.getPendingChangesCount();
                DatabaseUi.status(statusLabel, count + (count == 1 ? " pending change" : " pending changes") + " · Commit to save", DatabaseUi.Tone.WARNING);
            } else {
                saveBtn.setText("Commit");
            }
        }
        DatabaseUi.setActionEmphasis(
                saveBtn,
                saveBtn.isEnabled() ? DatabaseUi.POSITIVE_ACTION_COLOR : null
        );
        updateValidationDetails(errors);
    }

    private void updateValidationDetails(Map<CellCoord, String> errors) {
        if (validationDetails == null) return;
        if (errors.isEmpty()) {
            validationDetails.setText("");
            validationDetails.setVisible(false);
            return;
        }
        CellCoord selected = dataTable.getSelectedRow() < 0 || dataTable.getSelectedColumn() < 0 ? null
                : new CellCoord(dataTable.convertRowIndexToModel(dataTable.getSelectedRow()),
                        dataTable.convertColumnIndexToModel(dataTable.getSelectedColumn()));
        CellCoord coord = selected != null && errors.containsKey(selected) ? selected : errors.keySet().stream()
                .min(Comparator.comparingInt((CellCoord cell) -> cell.row).thenComparingInt(cell -> cell.col)).orElseThrow();
        ColumnMetadata metadata = tableModel.getColumnMeta(coord.col);
        String type = metadata == null ? "unknown type" : metadata.getFormattedType();
        String detail = "Row " + (dataTable.convertRowIndexToView(coord.row) + 1) + " · "
                + tableModel.getRawColumnName(coord.col) + " (" + type + ") · " + errors.get(coord);
        // Keep exceptionally long invalid values from filling the editor with text.
        validationDetails.setText(detail.length() > 240 ? detail.substring(0, 237) + "…" : detail);
        validationDetails.setToolTipText(detail);
        validationDetails.setVisible(true);
    }

    private void truncateCurrentTable() {
        if (mutationRunning || disposed || !finishCellEditing()) return;
        if (tableModel.hasPendingChanges()) {
            statusLabel.setText("Commit or Revert pending edits before truncating.");
            return;
        }
        if (!DatabaseUi.confirmDestructive(project, "Truncate table",
                "Truncate table '" + tableMetadata.getName() + "'?\nAll rows will be permanently deleted. The table structure will be kept.",
                "Truncate table")) return;

        setMutationRunning(true);
        DatabaseUi.status(statusLabel, "Truncating table…", DatabaseUi.Tone.BUSY);
        tasks.submitMutation(() -> {
            try {
                DataService.getInstance().withMutationConnection(DatabaseConnectionManager.getInstance().openConnection(config), conn -> {
                    DdlService.getInstance().truncateTable(conn, config, databaseName, tableMetadata.getName());
                    return null;
                });
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    setMutationRunning(false);
                    Messages.showInfoMessage(project, "Table '" + tableMetadata.getName() + "' truncated successfully.", "Table Truncated");
                    loadData();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    setMutationRunning(false);
                    DatabaseUi.status(statusLabel, "Truncate failed · See error details", DatabaseUi.Tone.ERROR);
                    statusLabel.setToolTipText(ex.getMessage());
                    Messages.showErrorDialog(project, "Failed to truncate table: " + ex.getMessage(), "Truncate Error");
                });
            }
        });
    }

    private void openSqlConsole() {
        String sampleSql = "SELECT * FROM " + DdlService.formatTable(config, databaseName, tableMetadata.getName()) + " LIMIT 100;\n";
        DatabaseEditorManager.getInstance(project).openConsole(config, databaseName, sampleSql);
    }

    private void showCreateTableDialog() {
        DatabaseUi.status(statusLabel, "Loading CREATE TABLE statement…", DatabaseUi.Tone.BUSY);
        tasks.submit(() -> {
            try (var read=tasks.openRead(config)) {
                Connection conn=read.connection();
                String ddl = DdlService.getInstance().getCreateTableStatement(conn, config, databaseName, tableMetadata);
                SwingUtilities.invokeLater(() -> {
                    if(disposed) return;
                    DatabaseUi.status(statusLabel, "CREATE TABLE statement loaded", DatabaseUi.Tone.NORMAL);
                    Window owner = SwingUtilities.getWindowAncestor(this);
                    JDialog dialog = (owner instanceof Frame)
                            ? new JDialog((Frame) owner, "CREATE TABLE DDL - " + tableMetadata.getName(), true)
                            : new JDialog(JOptionPane.getRootFrame(), "CREATE TABLE DDL - " + tableMetadata.getName(), true);
                    dialog.setLayout(new BorderLayout(8, 8));
                    dialog.setSize(640, 420);
                    dialog.setLocationRelativeTo(this);

                    JTextArea textArea = new JTextArea(ddl);
                    textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    textArea.setEditable(false);
                    textArea.setCaretPosition(0);
                    textArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

                    JBScrollPane scrollPane = new JBScrollPane(textArea);
                    dialog.add(scrollPane, BorderLayout.CENTER);

                    JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
                    JButton copyBtn = new JButton("Copy statement", AllIcons.Actions.Copy);
                    makeCompactButton(copyBtn);
                    copyBtn.addActionListener(e -> {
                        StringSelection sel = new StringSelection(ddl);
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                        copyBtn.setText("Copied");
                        javax.swing.Timer t = new javax.swing.Timer(1500, evt -> copyBtn.setText("Copy statement"));
                        t.setRepeats(false);
                        t.start();
                    });
                    btnPanel.add(copyBtn);

                    JButton consoleItem = new JButton("Open in SQL console", Icons.CONSOLE);
                    makeCompactButton(consoleItem);
                    consoleItem.addActionListener(e -> {
                        dialog.dispose();
                        DatabaseEditorManager.getInstance(project).openConsole(config, databaseName, ddl + "\n");
                    });
                    btnPanel.add(consoleItem);

                    JButton closeBtn = new JButton("Close");
                    makeCompactButton(closeBtn);
                    closeBtn.addActionListener(e -> dialog.dispose());
                    btnPanel.add(closeBtn);

                    dialog.add(btnPanel, BorderLayout.SOUTH);
                    dialog.setVisible(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if(disposed) return;
                    DatabaseUi.status(statusLabel, "Could not load CREATE TABLE statement", DatabaseUi.Tone.ERROR);
                    statusLabel.setToolTipText(ex.getMessage());
                    Messages.showErrorDialog(project, "Failed to fetch CREATE statement: " + ex.getMessage(), "DDL Error");
                });
            }
        });
    }

    private void deleteSelectedRows() {
        if (mutationRunning || !finishCellEditing()) return;
        int[] rows = dataTable.getSelectedRows();
        if (rows.length == 0) return;
        List<String> pkNames = tableMetadata.getPrimaryKeyColumnNames();
        List<Integer> modelRows = new ArrayList<>();
        List<Map<String, Object>> keys = new ArrayList<>();
        try {
            for (int viewRow : rows) {
                int row = dataTable.convertRowIndexToModel(viewRow);
                modelRows.add(row);
                if (!tableModel.isRowNew(row)) keys.add(tableModel.getRowOriginalPkValues(row, pkNames));
            }
        } catch (IllegalStateException e) {
            Messages.showWarningDialog(project, e.getMessage(), "Cannot Delete"); return;
        }
        if (keys.isEmpty()) {
            tableModel.removeRows(modelRows);
            updatePendingChangesState();
            return;
        }

        if (!DatabaseUi.confirmDestructive(project, "Delete rows",
                "Delete " + (keys.size() == 1 ? "1 saved row" : keys.size() + " saved rows") + " from '" + tableMetadata.getName() + "'?\nThis applies immediately and cannot be reverted in this editor.",
                "Delete rows")) return;

        TableDraftState draftStore = project == null ? null : TableDraftState.getInstance(project);
        TableDraftState.Draft remainingDraft = tableModel.captureDraft().withoutRows(modelRows);
        setMutationRunning(true);
        tasks.submitMutation(() -> {
            try {
                DataService.getInstance().withMutationConnection(DatabaseConnectionManager.getInstance().openConnection(config), conn -> {
                    DataService.getInstance().deleteRows(conn, config, databaseName, tableMetadata.getName(), keys);
                    return null;
                });
                SwingUtilities.invokeLater(() -> {
                    if (draftStore != null) {
                        if (remainingDraft.hasChanges()) draftStore.put(draftKey(), remainingDraft);
                        else draftStore.remove(draftKey());
                    }
                    if (disposed) return;
                    tableModel.removeRows(modelRows);
                    setMutationRunning(false);
                    DatabaseUi.status(statusLabel, "Deleted " + keys.size() + " saved rows", DatabaseUi.Tone.SUCCESS);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    setMutationRunning(false);
                    DatabaseUi.status(statusLabel, "Delete failed · Check database before retrying", DatabaseUi.Tone.ERROR);
                    Messages.showErrorDialog(project, "Delete failed: " + ex.getMessage() + "\nIf the connection failed, verify database state before retrying.", "Delete Error");
                });
            }
        });
    }

    private void commitChanges() {
        if (mutationRunning || !finishCellEditing()) return;
        if (!tableModel.hasPendingChanges()) return;

        Map<CellCoord, String> errors = tableModel.getValidationErrors();
        if (!errors.isEmpty()) {
            String firstError = errors.values().iterator().next();
            Messages.showWarningDialog(project, "Fix the invalid cells before committing.\n\n" + firstError, "Validation Error");
            return;
        }

        List<String> pkNames = tableMetadata.getPrimaryKeyColumnNames();

        List<Map<String, Object>> inserts = new ArrayList<>();
        List<DataService.RowUpdate> updates = new ArrayList<>();
        try {
                for (Map<String, Object> insertRow : tableModel.getNewRows()) {
                    Map<String, Object> filteredMap = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : insertRow.entrySet()) {
                        String colName = entry.getKey();
                        Object val = entry.getValue();
                        if (val==RowDefaults.Value.USE_DEFAULT) continue;
                        ColumnMetadata cm = tableMetadata.getColumn(colName);
                        if (cm != null && cm.isAutoIncrement()) {
                            if (val == null || "(Auto)".equalsIgnoreCase(String.valueOf(val))) {
                                continue; // Let database auto-generate
                            }
                        }
                        filteredMap.put(colName, convertInput(cm, val));
                    }
                    inserts.add(Collections.unmodifiableMap(filteredMap));
                }

                // 2. Process updates
                if (!tableModel.getModifiedCells().isEmpty() && pkNames.isEmpty()) {
                    throw new IllegalStateException("Cannot update records: Table has no primary key defined.");
                }

                Map<Integer, Map<String, Object>> changedRows = new LinkedHashMap<>();
                for (CellCoord coord : tableModel.getModifiedCells().keySet()) {
                    Object newVal = tableModel.getModifiedCells().get(coord);
                    String colName = tableModel.getRawColumnName(coord.col);
                    ColumnMetadata cm = tableMetadata.getColumn(colName);
                    Object typedVal = convertInput(cm, newVal);
                    changedRows.computeIfAbsent(coord.row, row -> new LinkedHashMap<>()).put(colName, typedVal);
                }
                for (Map.Entry<Integer, Map<String, Object>> row : changedRows.entrySet()) {
                    updates.add(new DataService.RowUpdate(row.getValue(), tableModel.getRowOriginalPkValues(row.getKey(), pkNames)));
                }
        } catch (Exception e) {
            Messages.showErrorDialog(project, e.getMessage(), "Cannot Commit"); return;
        }
        TableDraftState draftStore = project == null ? null : TableDraftState.getInstance(project);
        setMutationRunning(true);
        tasks.submitMutation(() -> {
            try {
                DataService.getInstance().withMutationConnection(DatabaseConnectionManager.getInstance().openConnection(config), conn -> {
                    DataService.getInstance().commitChanges(conn, config, databaseName, tableMetadata.getName(), inserts, updates);
                    return null;
                });
                SwingUtilities.invokeLater(() -> {
                    if (draftStore != null) draftStore.remove(draftKey());
                    if (disposed) return;
                    tableModel.modifiedCells.clear();
                    tableModel.newRows.clear();
                    setMutationRunning(false);
                    Messages.showInfoMessage(project, "Changes committed.", "Changes Saved");
                    loadData();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    setMutationRunning(false);
                    DatabaseUi.status(statusLabel, "Commit failed · Pending edits kept", DatabaseUi.Tone.ERROR);
                    Messages.showErrorDialog(project, "Commit failed: " + ex.getMessage() + "\nIf the connection failed, verify database state before retrying.", "Commit Error");
                });
            }
        });
    }

    private boolean finishCellEditing() {
        return !dataTable.isEditing() || dataTable.getCellEditor().stopCellEditing();
    }

    private String draftKey() { return "table:" + config.getId() + ":" + databaseName + ":" + tableMetadata.getName(); }
    public boolean hasPendingChanges() { return tableModel.hasPendingChanges() || dataTable.isEditing(); }
    @Override public void close() {
        finishCellEditing();
        updatePendingChangesState();
        disposed = true; loadGeneration++;
        tasks.cancelPending();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(tasks::close);
        if (autoRefreshTimer != null) autoRefreshTimer.stop();
    }

    private void setMutationRunning(boolean running) {
        if (running) loadGeneration++;
        mutationRunning = running;
        dataTable.setEnabled(!running);
        updatePendingChangesState();
    }

    private void showExportMenu(Component invoker) {
        JPopupMenu menu = new JPopupMenu();

        addExportFormats(menu,"Current page (includes pending edits)",ExportScope.PAGE);
        addExportFormats(menu,"Selected rows (includes pending edits)",ExportScope.SELECTED);
        addExportFormats(menu,"All persisted rows (current filter)",ExportScope.ALL_PERSISTED);

        menu.addSeparator();

        JMenuItem viewCreateItem = new JMenuItem("View CREATE TABLE…", AllIcons.Actions.ShowAsTree);
        viewCreateItem.addActionListener(e -> showCreateTableDialog());
        menu.add(viewCreateItem);

        menu.show(invoker, 0, invoker.getHeight());
    }

    private void addExportFormats(JPopupMenu menu,String label,ExportScope scope) {
        JMenu submenu=new JMenu(label);
        for(String format:new String[]{"csv","json","sql"}) {
            JMenuItem item=new JMenuItem(format.toUpperCase(java.util.Locale.ROOT) + "…");
            item.addActionListener(event -> exportData(format,scope)); submenu.add(item);
        }
        menu.add(submenu);
    }

    private void exportCreateTable() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor("Export CREATE TABLE DDL", "Save table DDL as .sql", "sql");
        VirtualFileWrapper targetWrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save((com.intellij.openapi.vfs.VirtualFile) null, tableMetadata.getName() + "_create.sql");
        if (targetWrapper == null) return;

        File targetFile = targetWrapper.getFile();
        tasks.submit(() -> {
            try (var read=tasks.openRead(config)) {
                Connection conn=read.connection();
                String ddl = DdlService.getInstance().getCreateTableStatement(conn, config, databaseName, tableMetadata);
                ExportService.getInstance().exportCreateTable(ddl, targetFile);
                SwingUtilities.invokeLater(() -> { if(!disposed) Messages.showInfoMessage(project,"Exported CREATE DDL to " + targetFile.getName(),"Export Complete"); });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> { if(!disposed) Messages.showErrorDialog(project,"Export failed: " + ex.getMessage(),"Export Error"); });
            }
        });
    }

    private void exportData(String format,ExportScope scope) {
        if(disposed || !finishCellEditing()) return;
        List<List<Object>> rows = new ArrayList<>();
        if(scope==ExportScope.SELECTED) {
            for(int selected:dataTable.getSelectedRows()) rows.add(new ArrayList<>(tableModel.rows.get(dataTable.convertRowIndexToModel(selected))));
            if(rows.isEmpty()) { statusLabel.setText("Select rows to export."); return; }
        } else if(scope==ExportScope.PAGE) for(List<Object> row:tableModel.rows) rows.add(new ArrayList<>(row));
        String filter=appliedWhere;
        FileSaverDescriptor descriptor = new FileSaverDescriptor("Export " + (scope==ExportScope.ALL_PERSISTED ? "All Persisted Rows" : "Displayed Rows Including Pending Edits"), "Save as ." + format, format);
        VirtualFileWrapper targetWrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save((com.intellij.openapi.vfs.VirtualFile) null, tableMetadata.getName() + "." + format);
        if (targetWrapper == null) return;

        File targetFile = targetWrapper.getFile();
        QueryResult result = QueryResult.forResultSet(new ArrayList<>(tableModel.columns), new ArrayList<>(tableModel.types), rows, 0);
        tasks.submit(() -> {
            try {
                long count=result.getRows().size();
                if(scope==ExportScope.ALL_PERSISTED) {
                    try(var read=tasks.openRead(config)) {
                        Connection connection=read.connection();
                        count=ExportService.getInstance().exportPersisted(connection,config,databaseName,tableMetadata.getName(),filter,format,targetFile);
                    }
                } else if ("csv".equalsIgnoreCase(format)) ExportService.getInstance().exportToCsv(result,targetFile);
                else if ("json".equalsIgnoreCase(format)) ExportService.getInstance().exportToJson(result,targetFile);
                else ExportService.getInstance().exportToSqlInsert(config,databaseName,tableMetadata.getName(),result,targetFile);
                long exported=count;
                SwingUtilities.invokeLater(() -> { if (!disposed) Messages.showInfoMessage(project,"Exported " + exported + " row(s) to " + targetFile.getName(),"Export Complete"); });
            } catch(Exception error) {
                SwingUtilities.invokeLater(() -> { if (!disposed) Messages.showErrorDialog(project,"Export failed: " + error.getMessage(),"Export Error"); });
            }
        });
    }

    public static String validateCellValue(ColumnMetadata column,Object value) { return CellValueConverter.validate(DatabaseType.MYSQL,column,value); }
    private String validateInput(ColumnMetadata column,Object value) { return CellValueConverter.validate(config.getType(),column,value); }
    public static Object parseTypedValue(ColumnMetadata column,Object value) { return CellValueConverter.convert(DatabaseType.MYSQL,column,value); }
    private Object convertInput(ColumnMetadata column,Object value) { return CellValueConverter.convert(config.getType(),column,value); }

    // Inner classes
    private static class CellCoord {
        final int row;
        final int col;
        CellCoord(int r, int c) { this.row = r; this.col = c; }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CellCoord that = (CellCoord) o;
            return row == that.row && col == that.col;
        }
        @Override
        public int hashCode() { return Objects.hash(row, col); }
    }

    private class EditableTableModel extends AbstractTableModel {
        private List<String> columns = new ArrayList<>();
        private List<String> types = new ArrayList<>();
        private List<ColumnMetadata> columnMetaList = new ArrayList<>();
        private List<List<Object>> originalRows = new ArrayList<>();
        private List<List<Object>> rows = new ArrayList<>();

        private final Map<CellCoord, Object> modifiedCells = new HashMap<>();
        private final List<Map<String, Object>> newRows = new ArrayList<>();
        private final Map<CellCoord, String> validationErrors = new HashMap<>();

        public void setData(List<String> cols, List<String> typs, List<List<Object>> r) {
            this.columns = new ArrayList<>(cols);
            this.types = new ArrayList<>(typs);
            this.columnMetaList = new ArrayList<>();
            for (int i = 0; i < cols.size(); i++) {
                String c = cols.get(i);
                ColumnMetadata cm = tableMetadata.getColumn(c);
                if (cm == null) {
                    String t = (i < typs.size()) ? typs.get(i) : "VARCHAR";
                    cm = new ColumnMetadata(c, t, java.sql.Types.VARCHAR, 0, 0, true, false, false, null);
                }
                columnMetaList.add(cm);
            }

            this.originalRows = new ArrayList<>();
            this.rows = new ArrayList<>();
            for (List<Object> row : r) {
                this.originalRows.add(new ArrayList<>(row));
                this.rows.add(new ArrayList<>(row));
            }
            this.modifiedCells.clear();
            this.newRows.clear();
            this.validationErrors.clear();
            fireTableStructureChanged();
        }

        TableDraftState.Draft captureDraft() { return TableDraftState.Draft.capture(columns, types, originalRows, rows); }
        void refreshColumnMetadata() {
            columnMetaList.clear();
            for (String column : columns) columnMetaList.add(tableMetadata.getColumn(column));
            validationErrors.clear();
            for (int row = 0; row < rows.size(); row++) {
                for (int col = 0; col < columns.size(); col++) {
                    if (!isRowNew(row) && !isCellModified(row,col)) continue;
                    ColumnMetadata metadata = getColumnMeta(col);
                    if (isRowNew(row) && metadata != null && metadata.isAutoIncrement() && "(Auto)".equals(rows.get(row).get(col))) {
                        rows.get(row).set(col, RowDefaults.Value.USE_DEFAULT);
                        newRows.get(row - originalRows.size()).put(columns.get(col), RowDefaults.Value.USE_DEFAULT);
                    }
                    String error = validateInput(getColumnMeta(col), rows.get(row).get(col));
                    if (error != null) validationErrors.put(new CellCoord(row,col),error);
                }
            }
            fireTableStructureChanged();
        }
        void restoreDraft(TableDraftState.Draft draft) {
            setData(draft.columns, draft.types, draft.originalValues());
            rows = new ArrayList<>();
            for (List<Object> row : draft.values()) rows.add(new ArrayList<>(row));
            for (int row = 0; row < rows.size(); row++) {
                if (isRowNew(row)) {
                    Map<String,Object> values = new LinkedHashMap<>();
                    for (int col = 0; col < columns.size(); col++) {
                        ColumnMetadata metadata = getColumnMeta(col);
                        // Older drafts stored the automatic value as display text.
                        if (metadata != null && metadata.isAutoIncrement() && "(Auto)".equals(rows.get(row).get(col)))
                            rows.get(row).set(col, RowDefaults.Value.USE_DEFAULT);
                        values.put(columns.get(col), rows.get(row).get(col));
                    }
                    newRows.add(values);
                } else {
                    for (int col = 0; col < columns.size(); col++) {
                        Object original = originalRows.get(row).get(col);
                        Object restored = rows.get(row).get(col);
                        if (sameDatabaseValue(getColumnMeta(col), original, restored)) {
                            rows.get(row).set(col, original);
                        } else {
                            modifiedCells.put(new CellCoord(row,col), restored);
                        }
                    }
                }
            }
            refreshColumnMetadata();
            updatePendingChangesState();
        }

        public ColumnMetadata getColumnMeta(int col) {
            if (col >= 0 && col < columnMetaList.size()) {
                return columnMetaList.get(col);
            }
            return null;
        }

        public void addNewRow() {
            List<Object> blank = new ArrayList<>();
            Map<String, Object> newRowMap = new HashMap<>();
            int newRowModelIndex = rows.size();
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i);
                ColumnMetadata cm = (i < columnMetaList.size()) ? columnMetaList.get(i) : null;
                Object value=RowDefaults.initialValue(cm);
                blank.add(value); newRowMap.put(col,value);
                String error=validateInput(cm,value);
                if(error!=null) validationErrors.put(new CellCoord(newRowModelIndex,i),error);
            }
            rows.add(blank);
            newRows.add(newRowMap);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        public boolean hasPendingChanges() {
            return !modifiedCells.isEmpty() || !newRows.isEmpty();
        }

        public int getPendingChangesCount() {
            return modifiedCells.size() + newRows.size();
        }

        public Map<CellCoord, Object> getModifiedCells() { return modifiedCells; }
        public List<Map<String, Object>> getNewRows() { return newRows; }
        public Map<CellCoord, String> getValidationErrors() { return validationErrors; }

        public String getValidationError(CellCoord coord) {
            return validationErrors.get(coord);
        }

        public Map<String, Object> getRowPkValues(int row, List<String> pkNames) {
            Map<String, Object> map = new HashMap<>();
            for (String pk : pkNames) {
                int colIdx = getColumnIndex(pk);
                if (colIdx >= 0) {
                    map.put(pk, rows.get(row).get(colIdx));
                }
            }
            return map;
        }

        public Map<String, Object> getRowOriginalPkValues(int row, List<String> pkNames) {
            return RowIdentity.originalKeys(columns, originalRows.get(row), pkNames);
        }

        public void removeRows(List<Integer> selected) {
            List<Integer> sorted = selected.stream().distinct().sorted().toList();
            Map<CellCoord, Object> remaining = new HashMap<>();
            modifiedCells.forEach((coord, value) -> {
                if (!sorted.contains(coord.row)) {
                    int shift = (int) sorted.stream().filter(row -> row < coord.row).count();
                    remaining.put(new CellCoord(coord.row - shift, coord.col), value);
                }
            });
            for (int i = sorted.size() - 1; i >= 0; i--) {
                int row = sorted.get(i);
                if (row >= originalRows.size()) newRows.remove(row - originalRows.size());
                else originalRows.remove(row);
                rows.remove(row);
            }
            modifiedCells.clear(); modifiedCells.putAll(remaining);
            validationErrors.clear();
            for (int row = 0; row < rows.size(); row++) {
                for (int col = 0; col < columns.size(); col++) {
                    if (!isRowNew(row) && !isCellModified(row,col)) continue;
                    String error = validateInput(getColumnMeta(col), rows.get(row).get(col));
                    if (error != null) validationErrors.put(new CellCoord(row, col), error);
                }
            }
            fireTableDataChanged();
        }

        public int getColumnIndex(String name) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).equalsIgnoreCase(name)) return i;
            }
            return -1;
        }

        public String getRawColumnName(int col) {
            if (col >= 0 && col < columns.size()) {
                return columns.get(col);
            }
            return "";
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.size(); }

        @Override public String getColumnName(int col) {
            String colName = columns.get(col);
            ColumnMetadata cm = (col < columnMetaList.size()) ? columnMetaList.get(col) : tableMetadata.getColumn(colName);
            if (cm != null) {
                if (cm.isPrimaryKey() && cm.isAutoIncrement()) {
                    return colName + " [PK, Auto]";
                } else if (cm.isPrimaryKey()) {
                    return colName + " [PK]";
                } else if (cm.isAutoIncrement()) {
                    return colName + " [Auto]";
                }
            }
            return colName;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return !mutationRunning && !disposed && !tableMetadata.isView();
        }

        @Override public Object getValueAt(int row, int col) {
            if (row < rows.size() && col < rows.get(row).size()) {
                return rows.get(row).get(col);
            }
            return null;
        }

        public boolean isCellModified(int row, int col) {
            return modifiedCells.containsKey(new CellCoord(row, col));
        }

        public boolean isRowNew(int row) {
            return row >= originalRows.size();
        }

        public Object getOriginalValue(int row, int col) {
            if (row < originalRows.size() && col < originalRows.get(row).size()) {
                return originalRows.get(row).get(col);
            }
            return null;
        }

        @Override public void setValueAt(Object val, int row, int col) {
            if (row < rows.size() && col < rows.get(row).size()) {
                ColumnMetadata cm = getColumnMeta(col);
                if (!isCellEditable(row, col)) return;

                CellCoord coord = new CellCoord(row, col);
                boolean existingRow = row < originalRows.size();
                Object originalValue = existingRow ? originalRows.get(row).get(col) : null;
                boolean unchanged = existingRow && sameDatabaseValue(cm, originalValue, val);
                Object processedVal = unchanged ? originalValue : val;
                rows.get(row).set(col, processedVal);

                // An unchanged database value is already valid as stored, even if its JDBC
                // representation differs from the editor's String value.
                String validationError = unchanged ? null : validateInput(cm, processedVal);
                if (validationError != null) {
                    validationErrors.put(coord, validationError);
                } else {
                    validationErrors.remove(coord);
                }

                if (existingRow) {
                    if (unchanged) {
                        modifiedCells.remove(coord);
                    } else {
                        modifiedCells.put(coord, processedVal);
                    }
                } else {
                    int newRowIdx = row - originalRows.size();
                    if (newRowIdx < newRows.size()) {
                        newRows.get(newRowIdx).put(columns.get(col), processedVal);
                    }
                }
                fireTableCellUpdated(row, col);
                updatePendingChangesState();
            }
        }
    }

    private boolean sameDatabaseValue(ColumnMetadata column, Object original, Object candidate) {
        if (Objects.deepEquals(original, candidate)) return true;
        if (original == null || candidate == null) return false;
        if (column == null) return Objects.equals(String.valueOf(original), String.valueOf(candidate));

        try {
            Object originalTyped = convertInput(column, original);
            Object candidateTyped = convertInput(column, candidate);
            if (originalTyped instanceof BigDecimal originalDecimal
                    && candidateTyped instanceof BigDecimal candidateDecimal) {
                return originalDecimal.compareTo(candidateDecimal) == 0;
            }
            return Objects.deepEquals(originalTyped, candidateTyped);
        } catch (IllegalArgumentException invalidValue) {
            // Preserve an uneditable legacy database value until the user actually changes it.
            return Objects.equals(String.valueOf(original), String.valueOf(candidate));
        }
    }

    private class CellHighlightRenderer extends DefaultTableCellRenderer {

        // Modified existing cell (UPDATE): Ocean / Cyan highlight
        private final JBColor modifiedBg = new JBColor(new Color(205, 232, 255), new Color(24, 72, 115));
        private final JBColor modifiedFg = new JBColor(new Color(0, 45, 120), new Color(175, 225, 255));
        private final JBColor modifiedBorder = new JBColor(new Color(28, 125, 225), new Color(65, 155, 250));
        private final JBColor modifiedSelBg = new JBColor(new Color(175, 215, 250), new Color(36, 96, 152));

        // New inserted row cell (INSERT): Mint / Emerald highlight
        private final JBColor newRowBg = new JBColor(new Color(220, 245, 225), new Color(24, 70, 44));
        private final JBColor newRowFg = new JBColor(new Color(15, 90, 40), new Color(145, 240, 175));
        private final JBColor newRowBorder = new JBColor(new Color(38, 155, 78), new Color(72, 190, 118));
        private final JBColor newRowSelBg = new JBColor(new Color(195, 235, 205), new Color(34, 92, 60));

        // Validation Error: Rose / Red highlight
        private final JBColor errorBg = new JBColor(new Color(255, 225, 225), new Color(90, 25, 30));
        private final JBColor errorFg = new JBColor(new Color(180, 20, 20), new Color(255, 130, 130));
        private final JBColor errorBorder = new JBColor(new Color(220, 40, 40), new Color(240, 70, 70));
        private final JBColor errorSelBg = new JBColor(new Color(255, 200, 200), new Color(115, 35, 40));

        private final JBColor autoFg = new JBColor(new Color(120, 120, 120), new Color(155, 155, 155));

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            setFont(table.getFont());
            setBorder(JBUI.Borders.empty(0, 8));
            int modelRow = table.convertRowIndexToModel(row);
            int modelCol = table.convertColumnIndexToModel(column);
            CellCoord coord = new CellCoord(modelRow, modelCol);

            String errorMsg = tableModel.getValidationError(coord);
            boolean isModified = tableModel.isCellModified(modelRow, modelCol);
            boolean isNewRow = tableModel.isRowNew(modelRow);
            ColumnMetadata cm = tableModel.getColumnMeta(modelCol);
            boolean isAuto = cm != null && cm.isAutoIncrement();
            boolean numeric = cm != null && switch (cm.getDataType()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                        Types.NUMERIC, Types.DECIMAL, Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
                default -> false;
            };
            setHorizontalAlignment(numeric || value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);

            if (errorMsg != null) {
                c.setBackground(isSelected ? errorSelBg : errorBg);
                c.setForeground(errorFg);
                setFont(table.getFont());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(errorBorder, JBUI.scale(2)),
                        JBUI.Borders.empty(0, 6)
                ));
            } else if (isNewRow) {
                c.setBackground(isSelected ? newRowSelBg : newRowBg);
                c.setForeground(newRowFg);
                setFont(table.getFont());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, JBUI.scale(2), 0, 0, newRowBorder),
                        JBUI.Borders.empty(0, 6)
                ));
                if (isAuto && value == RowDefaults.Value.USE_DEFAULT) {
                    c.setForeground(autoFg);
                    setFont(getFont().deriveFont(Font.ITALIC));
                }
            } else if (isModified) {
                c.setBackground(isSelected ? modifiedSelBg : modifiedBg);
                c.setForeground(modifiedFg);
                setFont(table.getFont());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, JBUI.scale(2), 0, 0, modifiedBorder),
                        JBUI.Borders.empty(0, 6)
                ));
            } else {
                if (isSelected) {
                    c.setBackground(table.getSelectionBackground());
                    c.setForeground(table.getSelectionForeground());
                    setFont(getFont().deriveFont(Font.PLAIN));
                } else {
                    c.setBackground(table.getBackground());
                    if (value == null) {
                        c.setForeground(table.getForeground());
                        setFont(getFont().deriveFont(Font.ITALIC));
                    } else if (isAuto) {
                        c.setForeground(table.getForeground());
                        setFont(getFont().deriveFont(Font.PLAIN));
                    } else {
                        c.setForeground(table.getForeground());
                        setFont(getFont().deriveFont(Font.PLAIN));
                    }
                }
            }

            if (hasFocus && errorMsg == null) {
                setBorder(BorderFactory.createCompoundBorder(
                        JBUI.Borders.customLine(JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x548AF7))),
                        JBUI.Borders.empty(0, 7)));
            }
            if (value == null) {
                setText("NULL");
            } else if (isAuto && value == RowDefaults.Value.USE_DEFAULT) {
                setText("(Auto)");
            }
            return c;
        }
    }

}
