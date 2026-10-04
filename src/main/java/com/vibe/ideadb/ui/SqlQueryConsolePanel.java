package com.vibe.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.table.JBTable;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.QueryResult;
import com.vibe.ideadb.model.QueryHistoryEntry;
import com.vibe.ideadb.model.ReadOnlyResultModel;
import com.vibe.ideadb.service.DataService;
import com.vibe.ideadb.service.DatabaseConnectionManager;
import com.vibe.ideadb.service.DatabaseSession;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

public class SqlQueryConsolePanel extends JPanel implements AutoCloseable {
    private static final JBColor NORMAL_MSG_COLOR = new JBColor(new Color(40, 40, 40), new Color(200, 200, 200));
    private static final JBColor ERROR_MSG_COLOR = new JBColor(new Color(200, 40, 40), new Color(255, 107, 107));
    private static final JBColor SUCCESS_MSG_COLOR = new JBColor(new Color(30, 140, 60), new Color(98, 181, 67));
    private final Project project;
    private final ConnectionConfig config;
    private String activeDatabase;
    private final DatabaseSession session;
    private boolean running;
    private volatile com.vibe.ideadb.service.QueryExecution execution;
    private JButton cancelBtn;
    private JComboBox<Integer> resultLimit;
    private volatile boolean disposed;

    private JComboBox<String> databaseCombo;
    private JBTextArea editorArea;
    private JButton runBtn;
    private JComboBox<QueryHistoryEntry> historyCombo;
    private JBTabbedPane resultsTabs;
    private JBTable resultsTable;
    private DefaultTableModel resultsModel;
    private JBTextArea messagesArea;
    private JBLabel statusLabel;

    private final List<String> queryHistory = new ArrayList<>();

    public SqlQueryConsolePanel(Project project, ConnectionConfig config, String initialDatabase, List<String> allDatabases) {
        super(new BorderLayout(0, 0));
        this.project = project;
        this.config = config;
        this.session = DatabaseConnectionManager.getInstance().createSession(config);
        this.activeDatabase = initialDatabase != null ? initialDatabase : "";

        initUI(allDatabases);
    }

    private void initUI(List<String> allDatabases) {
        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        toolbar.add(new JBLabel("Database:"));
        databaseCombo = new JComboBox<>();
        if (allDatabases != null) {
            for (String db : allDatabases) {
                databaseCombo.addItem(db);
            }
        }
        if (activeDatabase != null && !activeDatabase.isEmpty()) {
            databaseCombo.setSelectedItem(activeDatabase);
        }
        if (databaseCombo.getSelectedItem() != null) activeDatabase = databaseCombo.getSelectedItem().toString();
        databaseCombo.addActionListener(e -> {
            Object selected = databaseCombo.getSelectedItem();
            if (selected != null) {
                activeDatabase = selected.toString();
            }
        });
        toolbar.add(databaseCombo);

        toolbar.add(new JSeparator(SwingConstants.VERTICAL));

        runBtn = new JButton("Run (Ctrl+Enter)", AllIcons.Actions.Execute);
        runBtn.addActionListener(e -> executeCurrentSql());
        toolbar.add(runBtn);
        cancelBtn = new JButton("Cancel"); cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> cancelExecution()); toolbar.add(cancelBtn);
        toolbar.add(new JBLabel("Rows:"));
        resultLimit = new JComboBox<>(new Integer[]{100,1000,10000}); resultLimit.setSelectedItem(1000); toolbar.add(resultLimit);

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> editorArea.setText(""));
        toolbar.add(clearBtn);

        toolbar.add(new JSeparator(SwingConstants.VERTICAL));

        toolbar.add(new JBLabel("History:"));
        historyCombo = new JComboBox<>(new QueryHistoryEntry[]{new QueryHistoryEntry(null)});
        historyCombo.addActionListener(e -> {
            if (historyCombo.getSelectedIndex() > 0) {
                QueryHistoryEntry query = (QueryHistoryEntry) historyCombo.getSelectedItem();
                if (query != null && query.sql()!=null) setSqlText(query.sql());
            }
        });
        toolbar.add(historyCombo);

        toolbar.add(new JBLabel("Snippets:"));
        JComboBox<String> snippetCombo = new JComboBox<>(new String[]{
                "(Insert Template)",
                "SELECT * FROM ... LIMIT 50;",
                "SELECT COUNT(*) FROM ...;",
                "INSERT INTO ... VALUES (...);",
                "UPDATE ... SET ... WHERE ...;",
                "DELETE FROM ... WHERE ...;",
                "SHOW TABLES;"
        });
        snippetCombo.addActionListener(e -> {
            if (snippetCombo.getSelectedIndex() > 0) {
                String snip = (String) snippetCombo.getSelectedItem();
                editorArea.insert(snip + "\n", editorArea.getCaretPosition());
            }
        });
        toolbar.add(snippetCombo);

        add(toolbar, BorderLayout.NORTH);

        // Center Splitter: Editor on top, Results on bottom
        JBSplitter splitter = new JBSplitter(true, 0.45f);

        // Editor
        editorArea = new JBTextArea();
        editorArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editorArea.setTabSize(4);
        editorArea.setText("-- SQL Query Console (" + config.getName() + ")\n-- Write your query and press Ctrl+Enter to execute\n\n");
        editorArea.setCaretPosition(editorArea.getText().length());

        // Keyboard Shortcut: Ctrl+Enter / Cmd+Enter to Run
        KeyStroke runKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        editorArea.getInputMap().put(runKeyStroke, "runSql");
        editorArea.getActionMap().put("runSql", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                executeCurrentSql();
            }
        });

        JBScrollPane editorScroll = new JBScrollPane(editorArea);
        splitter.setFirstComponent(editorScroll);

        // Results Pane
        resultsTabs = new JBTabbedPane();
        resultsTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        resultsModel = new ReadOnlyResultModel();
        resultsTable = new JBTable(resultsModel);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultsTable.setRowHeight(24);
        resultsTable.setShowGrid(true);
        resultsTable.setGridColor(new JBColor(new Color(230, 230, 230), new Color(60, 63, 65)));
        resultsTable.setDefaultRenderer(Object.class, new ConsoleResultCellRenderer());

        JTableHeader header = resultsTable.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 28));
        header.setDefaultRenderer(new ConsoleTableHeaderRenderer());

        resultsTabs.addTab("Results", new JBScrollPane(resultsTable));

        messagesArea = new JBTextArea();
        messagesArea.setEditable(false);
        messagesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        messagesArea.setForeground(NORMAL_MSG_COLOR);
        resultsTabs.addTab("Messages", new JBScrollPane(messagesArea));

        splitter.setSecondComponent(resultsTabs);
        add(splitter, BorderLayout.CENTER);

        // South: Status Bar
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        statusLabel = new JBLabel("Ready");
        statusBar.add(statusLabel);
        add(statusBar, BorderLayout.SOUTH);
    }

    public void setSqlText(String sql) {
        editorArea.setText(sql);
        editorArea.setCaretPosition(sql.length());
    }

    private void executeCurrentSql() {
        if (running || disposed) return;
        String sql = editorArea.getSelectedText();
        if (sql == null || sql.trim().isEmpty()) {
            sql = editorArea.getText().trim();
        }

        if (sql.isEmpty()) {
            return;
        }

        final String finalSql = sql;
        final String database = activeDatabase;
        final DataService.QueryOptions options = new DataService.QueryOptions((Integer) resultLimit.getSelectedItem(),60,100);
        final com.vibe.ideadb.service.QueryExecution current = new com.vibe.ideadb.service.QueryExecution();
        execution = current;
        cancelBtn.setEnabled(true);
        running = true;
        runBtn.setEnabled(false);
        statusLabel.setText("Executing query...");

        // Record history
        if (!queryHistory.contains(finalSql)) {
            queryHistory.add(0, finalSql);
            if (queryHistory.size() > 25) queryHistory.remove(queryHistory.size() - 1);
            updateHistoryCombo();
        }

        new Thread(() -> {
            try {
                QueryResult result = session.execute(conn -> DataService.getInstance().executeQuery(conn, database, finalSql, options, current));

                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    execution = null; cancelBtn.setEnabled(false);
                    running = false;
                    runBtn.setEnabled(true);
                    if (result.hasError()) {
                        messagesArea.setText("ERROR: " + result.getError() + "\nElapsed: " + result.getExecutionTimeMs() + " ms");
                        messagesArea.setForeground(ERROR_MSG_COLOR);
                        resultsTabs.setSelectedIndex(1); // Switch to Messages tab
                        statusLabel.setText("Query failed: " + result.getError());
                    } else if (result.isResultSet()) {
                        messagesArea.setText(result.getMessage());
                        messagesArea.setForeground(NORMAL_MSG_COLOR);

                        // Populate results table
                        Object[][] values = result.getRows().stream().map(List::toArray).toArray(Object[][]::new);
                        resultsModel.setDataVector(values, result.getColumnNames().toArray());

                        for (int i = 0; i < resultsTable.getColumnCount(); i++) {
                            int headerWidth = resultsTable.getColumnModel().getColumn(i).getHeaderValue().toString().length() * 10 + 30;
                            resultsTable.getColumnModel().getColumn(i).setPreferredWidth(Math.max(headerWidth, 90));
                        }

                        resultsTabs.setTitleAt(0, "Results (" + result.getRows().size() + ")");
                        resultsTabs.setSelectedIndex(0); // Switch to Results tab
                        statusLabel.setText(result.getMessage());
                    } else {
                        messagesArea.setText(result.getMessage());
                        messagesArea.setForeground(SUCCESS_MSG_COLOR);
                        resultsTabs.setSelectedIndex(1);
                        statusLabel.setText(result.getMessage());
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    execution = null; cancelBtn.setEnabled(false);
                    running = false;
                    runBtn.setEnabled(true);
                    messagesArea.setText("Exception: " + ex.getMessage());
                    messagesArea.setForeground(ERROR_MSG_COLOR);
                    resultsTabs.setSelectedIndex(1);
                    statusLabel.setText("Execution error: " + ex.getMessage());
                });
            }
        }).start();
    }

    private void cancelExecution() {
        var current = execution;
        if (current != null) com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(current::cancel);
    }
    @Override public void close() {
        disposed = true; cancelExecution();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(session::close);
    }

    private void updateHistoryCombo() {
        historyCombo.removeAllItems();
        historyCombo.addItem(new QueryHistoryEntry(null));
        for (String q : queryHistory) {
            historyCombo.addItem(new QueryHistoryEntry(q));
        }
    }

    private static class ConsoleResultCellRenderer extends DefaultTableCellRenderer {
        private final JBColor oddBg = new JBColor(new Color(245, 247, 250), new Color(43, 45, 48));
        private final JBColor nullFg = new JBColor(new Color(150, 150, 150), new Color(125, 125, 125));

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (isSelected) {
                c.setBackground(table.getSelectionBackground());
                c.setForeground(table.getSelectionForeground());
                setFont(getFont().deriveFont(Font.PLAIN));
            } else {
                c.setBackground(row % 2 == 0 ? table.getBackground() : oddBg);
                if (value == null) {
                    c.setForeground(nullFg);
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else {
                    c.setForeground(table.getForeground());
                    setFont(getFont().deriveFont(Font.PLAIN));
                }
            }
            if (value == null) {
                setText("<null>");
            }
            return c;
        }
    }

    private static class ConsoleTableHeaderRenderer extends DefaultTableCellRenderer {
        private final JBColor headerBg = new JBColor(new Color(232, 236, 242), new Color(48, 51, 56));
        private final JBColor headerFg = new JBColor(new Color(30, 32, 36), new Color(220, 224, 230));
        private final JBColor headerBorder = new JBColor(new Color(205, 210, 216), new Color(70, 73, 78));

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            c.setBackground(headerBg);
            c.setForeground(headerFg);
            setFont(getFont().deriveFont(Font.BOLD, 12f));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 1, headerBorder),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)
            ));
            return c;
        }
    }
}
