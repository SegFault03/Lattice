package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.QueryResult;
import com.segfault03.ideadb.model.QueryHistoryEntry;
import com.segfault03.ideadb.model.ReadOnlyResultModel;
import com.segfault03.ideadb.service.DataService;
import com.segfault03.ideadb.service.DatabaseConnectionManager;
import com.segfault03.ideadb.service.DatabaseSession;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
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
    private final com.segfault03.ideadb.service.DatabaseTaskScope tasks=com.segfault03.ideadb.service.DatabaseTaskService.getInstance().newScope();
    private final Project project;
    private final ConnectionConfig config;
    private String activeDatabase;
    private final DatabaseSession session;
    private boolean running;
    private volatile com.segfault03.ideadb.service.QueryExecution execution;
    private JButton cancelBtn;
    private JComboBox<Integer> resultLimit;
    private volatile boolean disposed;

    private JComboBox<String> databaseCombo;
    private JBTextArea editorArea;
    private JButton runBtn;
    private JComboBox<QueryHistoryEntry> historyCombo;
    private JBTabbedPane resultsTabs;
    private DatabaseTable resultsTable;
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
        JPanel heading = new JPanel(new BorderLayout());
        heading.setBorder(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR));
        JPanel toolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)));
        toolbar.setBorder(JBUI.Borders.empty(4, 8));
        databaseCombo = DatabaseInputs.comboBox();
        databaseCombo.setPrototypeDisplayValue("database_name_123");
        databaseCombo.setToolTipText("Database for the next query");
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
        int menuShortcut = GraphicsEnvironment.isHeadless() ? java.awt.event.InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        String runShortcut = (menuShortcut & java.awt.event.InputEvent.META_DOWN_MASK) != 0 ? "⌘Enter" : "Ctrl+Enter";
        runBtn = DatabaseUi.action("Run", AllIcons.Actions.Execute, "Run selection or query (" + runShortcut + ")");
        runBtn.addActionListener(e -> executeCurrentSql());
        cancelBtn = DatabaseUi.action("Stop", AllIcons.Actions.Suspend, "Cancel the running query");
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> cancelExecution());
        resultLimit = DatabaseInputs.comboBox(new Integer[]{100, 1000, 10000});
        resultLimit.setSelectedItem(1000);
        resultLimit.setToolTipText("Maximum rows returned by the next query");
        JButton clearBtn = DatabaseUi.action("", AllIcons.Actions.GC, "Clear query text");
        clearBtn.addActionListener(e -> editorArea.setText(""));
        toolbar.add(DatabaseUi.group(new JBLabel("Database"), databaseCombo));
        toolbar.add(DatabaseUi.group(DatabaseUi.separator(), runBtn, cancelBtn));
        toolbar.add(DatabaseUi.group(DatabaseUi.separator(), clearBtn));
        heading.add(toolbar, BorderLayout.NORTH);

        JPanel recall = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)));
        recall.setBorder(JBUI.Borders.empty(0, 8, 4, 8));
        historyCombo = DatabaseInputs.comboBox(new QueryHistoryEntry[]{new QueryHistoryEntry(null)});
        historyCombo.setPrototypeDisplayValue(new QueryHistoryEntry("SELECT … FROM customers"));
        historyCombo.addActionListener(e -> {
            if (historyCombo.getSelectedIndex() > 0) {
                QueryHistoryEntry query = (QueryHistoryEntry) historyCombo.getSelectedItem();
                if (query != null && query.sql()!=null) setSqlText(query.sql());
            }
        });
        historyCombo.setToolTipText("Restore a recent query into the editor");
        recall.add(DatabaseUi.group(new JBLabel("History"), historyCombo));
        JComboBox<String> snippetCombo = DatabaseInputs.comboBox(new String[]{
                "Choose a template…",
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
                snippetCombo.setSelectedIndex(0);
            }
        });
        snippetCombo.setPrototypeDisplayValue("UPDATE … SET … WHERE …");
        snippetCombo.setToolTipText("Insert a SQL template at the caret");
        recall.add(DatabaseUi.group(new JBLabel("Template"), snippetCombo));
        heading.add(recall, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);

        // Center Splitter: Editor on top, Results on bottom
        JBSplitter splitter = new JBSplitter(true, 0.45f);

        // Editor
        editorArea = new JBTextArea();
        editorArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, getFont().getSize()));
        editorArea.setMargin(JBUI.insets(10, 12));
        editorArea.setTabSize(4);
        editorArea.setText("-- " + config.getName() + "\n-- Write your query and press " + runShortcut + " to execute\n\n");
        editorArea.setCaretPosition(editorArea.getText().length());

        // Keyboard Shortcut: Ctrl+Enter / Cmd+Enter to Run
        KeyStroke runKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuShortcut);
        editorArea.getInputMap().put(runKeyStroke, "runSql");
        editorArea.getActionMap().put("runSql", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                executeCurrentSql();
            }
        });

        JPanel queryPanel = new JPanel(new BorderLayout());
        JPanel queryCaption = new JPanel(new BorderLayout());
        queryCaption.setBorder(JBUI.Borders.empty(6, 12));
        JBLabel queryTitle = new JBLabel("SQL query");
        queryTitle.setFont(queryTitle.getFont().deriveFont(Font.BOLD));
        queryCaption.add(queryTitle, BorderLayout.WEST);
        JBLabel shortcutHint = new JBLabel(runShortcut + " to run · selection or full query");
        shortcutHint.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        queryCaption.add(shortcutHint, BorderLayout.EAST);
        queryPanel.add(queryCaption, BorderLayout.NORTH);
        JBScrollPane editorScroll = new JBScrollPane(editorArea);
        editorScroll.setBorder(JBUI.Borders.empty());
        editorScroll.setRowHeaderView(new SqlLineNumbers(editorArea));
        queryPanel.add(editorScroll, BorderLayout.CENTER);
        splitter.setFirstComponent(queryPanel);

        // Results Pane
        resultsTabs = new JBTabbedPane();
        resultsTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        resultsModel = new ReadOnlyResultModel();
        resultsTable = new DatabaseTable(resultsModel);
        resultsTable.setDefaultRenderer(Object.class, new ConsoleResultCellRenderer());
        resultsTable.getEmptyText().setText("Run a query to see results");
        JBScrollPane resultsScroll = resultsTable.createScrollPane();
        resultsTabs.addTab("Results", resultsScroll);

        messagesArea = new JBTextArea();
        messagesArea.setEditable(false);
        messagesArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        messagesArea.setForeground(NORMAL_MSG_COLOR);
        messagesArea.setMargin(JBUI.insets(10, 12));
        JBScrollPane messagesScroll = new JBScrollPane(messagesArea);
        messagesScroll.setBorder(JBUI.Borders.empty());
        resultsTabs.addTab("Messages", messagesScroll);

        splitter.setSecondComponent(resultsTabs);
        add(splitter, BorderLayout.CENTER);

        // South: Status Bar
        JPanel statusBar = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineTop(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR), JBUI.Borders.empty(4, 12)));
        statusLabel = new JBLabel("Ready") {
            @Override public void setText(String text) { super.setText(text); setToolTipText(text); }
        };
        statusBar.add(statusLabel, BorderLayout.CENTER);
        statusBar.add(DatabaseUi.group(new JBLabel("Max rows"), resultLimit), BorderLayout.EAST);
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
        final com.segfault03.ideadb.service.QueryExecution current = new com.segfault03.ideadb.service.QueryExecution();
        execution = current;
        setRunning(true);
        statusLabel.setText("Executing query...");

        // Record history
        if (!queryHistory.contains(finalSql)) {
            queryHistory.add(0, finalSql);
            if (queryHistory.size() > 25) queryHistory.remove(queryHistory.size() - 1);
            updateHistoryCombo();
        }

        tasks.submit(() -> {
            try {
                QueryResult result = session.execute(conn -> DataService.getInstance().executeQuery(conn, database, finalSql, options, current));

                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    execution = null;
                    setRunning(false);
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
                    execution = null;
                    setRunning(false);
                    messagesArea.setText("Exception: " + ex.getMessage());
                    messagesArea.setForeground(ERROR_MSG_COLOR);
                    resultsTabs.setSelectedIndex(1);
                    statusLabel.setText("Execution error: " + ex.getMessage());
                });
            }
        });
    }

    private void setRunning(boolean running) {
        this.running = running;
        runBtn.setEnabled(!running);
        cancelBtn.setEnabled(running);
        databaseCombo.setEnabled(!running);
        resultLimit.setEnabled(!running);
    }

    private void cancelExecution() {
        var current = execution;
        if (current != null) com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(current::cancel);
    }
    @Override public void close() {
        disposed = true; cancelExecution();
        tasks.cancelPending();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(tasks::close);
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
        private final JBColor nullFg = new JBColor(0x777D86, 0xA0A5AE);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setFont(table.getFont().deriveFont(value == null ? Font.ITALIC : Font.PLAIN));
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            setForeground(isSelected ? table.getSelectionForeground() : value == null ? nullFg : table.getForeground());
            setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBorder(hasFocus ? BorderFactory.createCompoundBorder(
                    JBUI.Borders.customLine(JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x548AF7))),
                    JBUI.Borders.empty(0, 7)) : JBUI.Borders.empty(0, 8));
            if (value == null) setText("NULL");
            return this;
        }
    }
}
