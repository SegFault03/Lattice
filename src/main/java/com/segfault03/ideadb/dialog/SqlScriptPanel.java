package com.segfault03.ideadb.dialog;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.service.DatabaseChange;
import com.segfault03.ideadb.service.DatabaseChangeListener;
import com.segfault03.ideadb.service.DatabaseTaskService;
import com.segfault03.ideadb.service.SqlScriptRunner;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.run.SqlScript;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaListener;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic;
import com.segfault03.ideadb.sql.validate.SqlScriptValidator;
import com.segfault03.ideadb.sql.view.SqlSwingHighlighter;
import com.segfault03.ideadb.state.MetadataCacheState;
import com.segfault03.ideadb.ui.DatabaseUi;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.List;

/**
 * The content of the Execute SQL Script dialog: target, script file, an offline check of the script against the
 * cached schema, a read-only preview, and the run log. Used on the EDT; the run itself happens on a pooled thread.
 */
public final class SqlScriptPanel extends JPanel implements Disposable {
    /** Larger files are refused: a preview of a dump that size is not readable, and the check skips it anyway. */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int SUMMARY_LENGTH = 60;
    /** Scripts longer than this are previewed without colors. */
    static final int HIGHLIGHT_LIMIT = 1_000_000;
    private static final Color ERROR_LINE = new JBColor(new Color(0xFFDCDC), new Color(0x5A2E2E));
    private static final Color WARNING_LINE = new JBColor(new Color(0xFFF3C4), new Color(0x4D4220));
    private static final Color RUNNING_LINE = new JBColor(new Color(0xDCE8FF), new Color(0x2E3B5A));

    public enum Phase { EMPTY, READY, RUNNING, DONE }

    private final @Nullable Project project;
    private final ConnectionConfig config;
    private final SqlDialectProfile profile;
    private final Runnable stateChanged;

    final ComboBox<String> database = new ComboBox<>();
    final TextFieldWithBrowseButton path = new TextFieldWithBrowseButton();
    final JBLabel summary = new JBLabel();
    final JTextPane preview = new JTextPane();
    private final Gutter gutter = new Gutter();
    final JBTabbedPane tabs = new JBTabbedPane();
    final ProblemsModel problems = new ProblemsModel();
    final LogModel log = new LogModel();
    final JBRadioButton stopOnError = new JBRadioButton("Stop", true);
    final JBRadioButton continueOnError = new JBRadioButton("Continue");
    final JBCheckBox singleTransaction = new JBCheckBox("Run in one transaction, roll back on error");
    private final JBLabel transactionNote = new JBLabel();

    private Phase phase = Phase.EMPTY;
    private @Nullable SqlScript script;
    private @Nullable String fileError;
    private boolean namesChecked;
    private @Nullable SqlScriptRunner runner;
    private @Nullable SqlScriptRunner.Summary lastRun;
    private final Map<Integer, Icon> lineIcons = new HashMap<>();
    private final List<Object> marks = new ArrayList<>();
    private @Nullable Object runningMark;

    public SqlScriptPanel(@Nullable Project project, @NotNull ConnectionConfig config, @Nullable String namespace, @NotNull Runnable stateChanged) {
        super(new BorderLayout(0, JBUI.scale(8)));
        this.project = project;
        this.config = config;
        this.profile = SqlDialects.of(config.getType());
        this.stateChanged = stateChanged;
        setBorder(JBUI.Borders.empty());
        add(header(namespace), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(options(), BorderLayout.SOUTH);
        showSummary();
        if (ApplicationManager.getApplication() != null) {
            ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(SqlSchemaListener.TOPIC, (SqlSchemaListener) id -> {
                // the schema arrived after the file was checked: check its names now
                if (id.equals(config.getId()) && script != null && !namesChecked && phase == Phase.READY) check();
            });
        }
    }

    // ---- layout ---------------------------------------------------------------------------------------------------

    private JComponent header(@Nullable String namespace) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = JBUI.insets(0, 0, 6, 8);

        var provider = DatabaseProviders.get(config.getType());
        c.gridx = 0; c.gridy = 0;
        form.add(new JBLabel("Connection:"), c);
        JBLabel connection = new JBLabel(config.getName() + " (" + config.getType().getDisplayName() + ")", Icons.DATABASE, SwingConstants.LEFT);
        String label = provider.namespaceLabel();
        JBLabel databaseLabel = new JBLabel(Character.toUpperCase(label.charAt(0)) + label.substring(1) + ":");
        databaseLabel.setLabelFor(database);
        for (String name : namespaces(namespace)) database.addItem(name);
        if (namespace != null && !namespace.isBlank()) database.setSelectedItem(namespace);
        database.setToolTipText("Unqualified names in the script resolve in this " + label);
        database.addActionListener(event -> { if (script != null && phase != Phase.RUNNING) check(); });
        JPanel target = new JPanel(new GridBagLayout());
        target.setOpaque(false);
        GridBagConstraints t = new GridBagConstraints();
        t.anchor = GridBagConstraints.WEST;
        target.add(connection, t);
        t.insets = JBUI.insetsLeft(16);
        target.add(databaseLabel, t);
        t.insets = JBUI.insetsLeft(6);
        t.weightx = 1; t.fill = GridBagConstraints.HORIZONTAL;
        target.add(database, t);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = JBUI.insetsBottom(6);
        form.add(target, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.insets = JBUI.insets(0, 0, 6, 8);
        JBLabel scriptLabel = new JBLabel("Script:");
        scriptLabel.setLabelFor(path.getTextField());
        form.add(scriptLabel, c);
        path.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor("sql").withTitle("Choose SQL Script"));
        path.getTextField().getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(@NotNull javax.swing.event.DocumentEvent event) { SwingUtilities.invokeLater(SqlScriptPanel.this::loadIfChanged); }
        });
        path.getTextField().getAccessibleContext().setAccessibleName("SQL script file");
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = JBUI.insetsBottom(6);
        form.add(path, c);

        c.gridx = 1; c.gridy = 2; c.insets = JBUI.emptyInsets();
        summary.getAccessibleContext().setAccessibleName("Script status");
        form.add(summary, c);
        return form;
    }

    private JComponent body() {
        preview.setEditable(false);
        preview.setFont(DatabaseUi.codeFont(UIUtil.getLabelFont().getSize() + 0f));
        preview.setBorder(JBUI.Borders.empty(4, 6));
        preview.getAccessibleContext().setAccessibleName("Script preview");
        JBScrollPane previewScroll = new JBScrollPane(preview);
        previewScroll.setRowHeaderView(gutter);
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setOpaque(false);
        previewPanel.add(DatabaseUi.section("Preview (read-only)"), BorderLayout.NORTH);
        previewPanel.add(previewScroll, BorderLayout.CENTER);

        JBTable problemTable = table(problems, new int[]{28, 56});
        JBTable logTable = table(log, new int[]{28, 36, 56, 0, 0, 72});
        logTable.getColumnModel().getColumn(5).setCellRenderer(rightAligned());
        jumpOnClick(problemTable, row -> problems.rows.get(row).line);
        jumpOnClick(logTable, row -> log.rows.get(row).statement.line());
        tabs.addTab("Problems", new JBScrollPane(problemTable));
        tabs.addTab("Run log", new JBScrollPane(logTable));
        updateTabTitles();

        OnePixelSplitter split = new OnePixelSplitter(true, 0.62f);
        split.setFirstComponent(previewPanel);
        split.setSecondComponent(tabs);
        return split;
    }

    private JComponent options() {
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.setOpaque(false);
        options.setBorder(JBUI.Borders.emptyTop(4));
        ButtonGroup group = new ButtonGroup();
        group.add(stopOnError);
        group.add(continueOnError);
        options.add(new JBLabel("On error:"));
        options.add(Box.createHorizontalStrut(JBUI.scale(8)));
        options.add(stopOnError);
        options.add(Box.createHorizontalStrut(JBUI.scale(8)));
        options.add(continueOnError);
        options.add(Box.createHorizontalStrut(JBUI.scale(12)));
        options.add(DatabaseUi.separator());
        options.add(Box.createHorizontalStrut(JBUI.scale(12)));
        options.add(singleTransaction);
        String implicit = implicitDdlCommit(config.getType());
        if (implicit != null) {
            transactionNote.setText(implicit);
            transactionNote.setIcon(AllIcons.General.Information);
            transactionNote.setForeground(UIUtil.getContextHelpForeground());
            transactionNote.setBorder(JBUI.Borders.emptyLeft(8));
            singleTransaction.setToolTipText(implicit + "; those statements cannot be rolled back");
            options.add(transactionNote);
        }
        singleTransaction.addItemListener(event -> {
            // in one transaction the first error rolls everything back, so running on makes no sense
            if (singleTransaction.isSelected()) stopOnError.setSelected(true);
            continueOnError.setEnabled(!singleTransaction.isSelected() && phase != Phase.RUNNING);
        });
        return options;
    }

    /** What the engine does with DDL inside a transaction, when it commits it; null when DDL is transactional. */
    static @Nullable String implicitDdlCommit(DatabaseType type) {
        return switch (type) {
            case POSTGRESQL -> null;
            default -> type.getDisplayName() + " commits CREATE, ALTER and DROP immediately";
        };
    }

    private List<String> namespaces(@Nullable String selected) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (selected != null && !selected.isBlank()) names.add(selected);
        if (ApplicationManager.getApplication() != null) {
            var snapshot = MetadataCacheState.getInstance().snapshot(config);
            if (snapshot != null) for (var namespace : snapshot.namespaces()) names.add(namespace.name());
        }
        List<String> sorted = new ArrayList<>(names);
        if (selected != null && !selected.isBlank()) { sorted.remove(selected); Collections.sort(sorted); sorted.add(0, selected); }
        else Collections.sort(sorted);
        return sorted;
    }

    // ---- loading and checking ---------------------------------------------------------------------------------------

    private String loadedPath = "";

    private void loadIfChanged() {
        String text = path.getText().strip();
        if (text.equals(loadedPath) || phase == Phase.RUNNING) return;
        loadedPath = text;
        fileFacts = "";
        if (text.isEmpty()) { setScript(null, null); return; }
        File file = new File(text.startsWith("~" + File.separator) ? System.getProperty("user.home") + text.substring(1) : text);
        if (!file.isFile()) { setScript(null, "No file at this path"); return; }
        if (file.length() > MAX_FILE_BYTES) { setScript(null, "The file is larger than 5 MB; run it with the database's own client"); return; }
        try {
            VirtualFile virtual = ApplicationManager.getApplication() == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
            String content;
            Charset charset;
            if (virtual != null) {
                content = VfsUtilCore.loadText(virtual);
                charset = virtual.getCharset();
            } else {
                content = java.nio.file.Files.readString(file.toPath());
                charset = java.nio.charset.StandardCharsets.UTF_8;
            }
            fileFacts = size(file.length()) + ", " + charset.displayName();
            setScript(content.replace("\r\n", "\n"), null);
        } catch (IOException failure) {
            setScript(null, "Could not read the file: " + failure.getMessage());
        }
        check();
    }

    private String fileFacts = "";

    /** Shows the script text, or nothing with a reason. Package-private for tests, which set text without a file. */
    void setScript(@Nullable String text, @Nullable String error) {
        fileError = error;
        lastRun = null;
        log.clear();
        script = text == null ? null : SqlScript.parse(text, profile);
        preview.setText(text == null ? "" : text);
        preview.setCaretPosition(0);
        if (text != null) highlight();
        phase = text == null ? Phase.EMPTY : Phase.READY;
        if (text != null && fileFacts.isEmpty()) fileFacts = size(text.length());
        if (text == null) { problems.set(List.of()); lineIcons.clear(); clearMarks(); }
        updateTabTitles();
        showSummary();
        gutter.revalidate();
        gutter.repaint();
        stateChanged.run();
    }

    /** Checks the script offline: its shape, and its names against the cached schema when there is one. */
    public void check() {
        if (script == null) return;
        SqlSchema schema = null;
        if (ApplicationManager.getApplication() != null) {
            SqlSchemaService service = SqlSchemaService.getInstance();
            schema = service.schema(config);
            if (schema != null && !service.isStale(config.getId())) schema = schema.withDefaultNamespace(selectedNamespace());
            else { schema = null; service.ensureLoaded(config); }
        }
        namesChecked = schema != null;
        highlight(); // the schema or the database may have changed since the text was colored
        List<Problem> found = new ArrayList<>();
        for (SqlDiagnostic diagnostic : SqlScriptValidator.validate(script.text(), profile, schema, false)) {
            boolean warning = diagnostic.kind() == SqlDiagnostic.Kind.AMBIGUOUS_COLUMN;
            found.add(new Problem(warning, script.lineOf(diagnostic.start()), diagnostic.start(), diagnostic.end(), diagnostic.message()));
        }
        problems.set(found);
        if (script.text().length() > SqlScriptValidator.MAX_LENGTH) namesChecked = false;
        repaintMarks();
        updateTabTitles();
        if (phase == Phase.DONE) phase = Phase.READY;
        showSummary();
        stateChanged.run();
    }

    /** Colors the preview like the editor; a very large script keeps plain text, which coloring would stall. */
    private void highlight() {
        if (preview.getDocument().getLength() <= HIGHLIGHT_LIMIT) SqlSwingHighlighter.apply(preview, config, selectedNamespace());
    }

    // ---- running ----------------------------------------------------------------------------------------------------

    public int errorCount() { return (int) problems.rows.stream().filter(problem -> !problem.warning).count(); }
    public @NotNull Phase phase() { return phase; }
    public boolean hasLog() { return !log.rows.isEmpty(); }
    public @Nullable SqlScript script() { return script; }

    /** Runs every statement of the script; the outcome of each arrives in the run log as it finishes. */
    public void execute() {
        if (script == null || script.statements().isEmpty() || phase == Phase.RUNNING) return;
        SqlScript running = script;
        String namespace = selectedNamespace();
        var options = new SqlScriptRunner.Options(stopOnError.isSelected(), singleTransaction.isSelected());
        SqlScriptRunner current = new SqlScriptRunner();
        runner = current;
        lastRun = null;
        log.start(running.statements());
        clearMarks();
        repaintMarks();
        phase = Phase.RUNNING;
        tabs.setSelectedIndex(1);
        setInputsEnabled(false);
        showSummary();
        stateChanged.run();
        var listener = new SqlScriptRunner.Listener() {
            @Override public void started(@NotNull SqlScript.Statement statement) {
                SwingUtilities.invokeLater(() -> { log.started(statement); markRunning(statement); showSummary(); });
            }
            @Override public void finished(@NotNull SqlScript.Statement statement, @NotNull SqlScriptRunner.Outcome outcome) {
                SwingUtilities.invokeLater(() -> {
                    log.finished(statement, outcome);
                    if (outcome.status() == SqlScriptRunner.Status.FAILED) markFailed(statement);
                    showSummary();
                });
            }
            @Override public void rolledBack(@NotNull List<SqlScript.Statement> statements) {
                SwingUtilities.invokeLater(() -> log.rolledBack(statements));
            }
        };
        Runnable work = () -> {
            SqlScriptRunner.Summary result = current.run(config, namespace, running.statements(), options, listener);
            if (result.succeeded() > 0) DatabaseChangeListener.publish(DatabaseChange.connection(config.getId()));
            SwingUtilities.invokeLater(() -> finish(result));
        };
        if (ApplicationManager.getApplication() != null) DatabaseTaskService.getInstance().submit(work);
        else new Thread(work, "Lattice script run").start();
    }

    /** Stops a run: the running statement is cancelled and the rest are skipped. */
    public void stop() {
        SqlScriptRunner current = runner;
        if (current == null || phase != Phase.RUNNING) return;
        summaryOverride = "Stopping…";
        showSummary();
        if (ApplicationManager.getApplication() != null) DatabaseTaskService.getInstance().submit(current::cancel);
        else current.cancel();
    }

    private @Nullable String summaryOverride;

    void finish(SqlScriptRunner.Summary result) {
        lastRun = result;
        runner = null;
        summaryOverride = null;
        phase = Phase.DONE;
        clearRunningMark();
        setInputsEnabled(true);
        updateTabTitles();
        showSummary();
        stateChanged.run();
    }

    private void setInputsEnabled(boolean enabled) {
        path.setEnabled(enabled);
        database.setEnabled(enabled);
        stopOnError.setEnabled(enabled);
        continueOnError.setEnabled(enabled && !singleTransaction.isSelected());
        singleTransaction.setEnabled(enabled);
    }

    private @Nullable String selectedNamespace() {
        Object selected = database.getSelectedItem();
        return selected == null || selected.toString().isBlank() ? null : selected.toString();
    }

    /** The run log as tab-separated text, for the clipboard. */
    public @NotNull String logText() {
        StringBuilder text = new StringBuilder("#\tLine\tStatement\tResult\tTime\n");
        for (LogRow row : log.rows) {
            text.append(row.statement.index()).append('\t').append(row.statement.line()).append('\t')
                    .append(row.statement.summary(200)).append('\t').append(row.result).append('\t')
                    .append(row.millis < 0 ? "" : row.millis + " ms").append('\n');
        }
        return text.toString();
    }

    // ---- summary line -----------------------------------------------------------------------------------------------

    void showSummary() {
        if (summaryOverride != null) { DatabaseUi.status(summary, summaryOverride, DatabaseUi.Tone.BUSY); return; }
        switch (phase) {
            case EMPTY -> {
                if (fileError != null) DatabaseUi.status(summary, fileError, DatabaseUi.Tone.ERROR);
                else DatabaseUi.status(summary, "Choose a .sql file to check and run", DatabaseUi.Tone.NORMAL);
            }
            case READY -> {
                int statements = script == null ? 0 : script.statements().size();
                String facts = statements + (statements == 1 ? " statement" : " statements") + (fileFacts.isEmpty() ? "" : ", " + fileFacts);
                int errors = errorCount();
                int warnings = problems.rows.size() - errors;
                String found = errors == 0 && warnings == 0 ? (namesChecked ? "no problems found" : "no syntax problems; names not checked, the schema is not read yet")
                        : plural(errors, "error") + (warnings == 0 ? "" : (errors == 0 ? "" : ", ") + plural(warnings, "warning"));
                if (errors == 0 && warnings > 0) found = plural(warnings, "warning");
                DatabaseUi.Tone tone = statements == 0 ? DatabaseUi.Tone.WARNING : errors > 0 ? DatabaseUi.Tone.ERROR
                        : warnings > 0 || !namesChecked ? DatabaseUi.Tone.WARNING : DatabaseUi.Tone.SUCCESS;
                DatabaseUi.status(summary, statements == 0 ? "The file has no statements" : facts + "  ·  " + found, tone);
            }
            case RUNNING -> {
                int total = log.rows.size();
                int at = log.runningIndex();
                DatabaseUi.status(summary, "Running statement " + Math.max(1, at) + " of " + total + "…", DatabaseUi.Tone.BUSY);
            }
            case DONE -> {
                var run = lastRun;
                if (run == null) return;
                String time = String.format(Locale.ROOT, "%.2f s", run.millis() / 1000.0);
                if (run.connectionError() != null) {
                    DatabaseUi.status(summary, "Could not run the script: " + run.connectionError(), DatabaseUi.Tone.ERROR);
                    return;
                }
                int total = log.rows.size();
                String counts = run.succeeded() + " succeeded" + (run.failed() > 0 ? ", " + run.failed() + " failed" : "")
                        + (run.notRun() > 0 ? ", " + run.notRun() + " not run" : "");
                String text;
                DatabaseUi.Tone tone;
                if (run.cancelled()) { text = "Stopped: " + counts; tone = DatabaseUi.Tone.WARNING; }
                else if (run.failed() == 0) { text = "Ran " + plural(total, "statement"); tone = DatabaseUi.Tone.SUCCESS; }
                else if (run.notRun() > 0) { text = "Stopped at statement " + log.firstFailure() + " of " + total + ": " + counts; tone = DatabaseUi.Tone.ERROR; }
                else { text = "Finished with errors: " + counts; tone = DatabaseUi.Tone.ERROR; }
                if (run.rolledBack()) text += "; rolled back";
                DatabaseUi.status(summary, text + "  ·  " + time, tone);
            }
        }
    }

    private static String plural(int count, String noun) { return count + " " + noun + (count == 1 ? "" : "s"); }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private void updateTabTitles() {
        tabs.setTitleAt(0, problems.rows.isEmpty() ? "Problems" : "Problems (" + problems.rows.size() + ")");
        tabs.setTitleAt(1, log.rows.isEmpty() ? "Run log" : "Run log (" + log.rows.size() + ")");
    }

    // ---- preview marks ------------------------------------------------------------------------------------------------

    private void repaintMarks() {
        clearMarks();
        lineIcons.clear();
        for (Problem problem : problems.rows) {
            Icon icon = problem.warning ? AllIcons.General.Warning : AllIcons.General.Error;
            if (lineIcons.get(problem.line) != AllIcons.General.Error) lineIcons.put(problem.line, icon);
            markLines(problem.line, problem.line, problem.warning ? WARNING_LINE : ERROR_LINE, marks);
        }
        for (LogRow row : log.rows) if (row.status == SqlScriptRunner.Status.FAILED) markFailed(row.statement);
        gutter.repaint();
    }

    private void markFailed(SqlScript.Statement statement) {
        lineIcons.put(statement.line(), AllIcons.General.Error);
        markLines(statement.line(), lineAt(statement.end()), ERROR_LINE, marks);
        gutter.repaint();
    }

    private void markRunning(SqlScript.Statement statement) {
        clearRunningMark();
        List<Object> added = new ArrayList<>();
        markLines(statement.line(), lineAt(statement.end()), RUNNING_LINE, added);
        runningMark = added.isEmpty() ? null : added.get(0);
        scrollToLine(statement.line());
    }

    private void clearRunningMark() {
        if (runningMark != null) preview.getHighlighter().removeHighlight(runningMark);
        runningMark = null;
    }

    private void clearMarks() {
        for (Object mark : marks) preview.getHighlighter().removeHighlight(mark);
        marks.clear();
        clearRunningMark();
    }

    private int lineAt(int offset) { return preview.getDocument().getDefaultRootElement().getElementIndex(offset) + 1; }

    private void markLines(int first, int last, Color color, List<Object> into) {
        Element root = preview.getDocument().getDefaultRootElement();
        if (first < 1 || first > root.getElementCount()) return;
        int start = root.getElement(first - 1).getStartOffset();
        int end = root.getElement(Math.min(last, root.getElementCount()) - 1).getEndOffset() - 1;
        try {
            into.add(preview.getHighlighter().addHighlight(start, Math.max(start, end), new DefaultHighlighter.DefaultHighlightPainter(color)));
        } catch (BadLocationException ignored) { /* the text changed under the mark */ }
    }

    private void scrollToLine(int line) {
        Element root = preview.getDocument().getDefaultRootElement();
        if (line < 1 || line > root.getElementCount()) return;
        try {
            Rectangle2D view = preview.modelToView2D(root.getElement(line - 1).getStartOffset());
            if (view != null) preview.scrollRectToVisible(view.getBounds());
        } catch (BadLocationException ignored) { }
    }

    private void jumpOnClick(JBTable table, java.util.function.IntUnaryOperator lineOfRow) {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (row < 0) return;
                int line = lineOfRow.applyAsInt(table.convertRowIndexToModel(row));
                scrollToLine(line);
                Element root = preview.getDocument().getDefaultRootElement();
                if (line >= 1 && line <= root.getElementCount()) preview.setCaretPosition(root.getElement(line - 1).getStartOffset());
            }
        });
    }

    // ---- tables -----------------------------------------------------------------------------------------------------

    private static JBTable table(AbstractTableModel model, int[] widths) {
        JBTable table = new JBTable(model);
        table.setShowGrid(false);
        table.setStriped(false);
        table.setRowHeight(JBUI.scale(24));
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Icon.class, new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) { setIcon((Icon) value); setText(""); setHorizontalAlignment(CENTER); }
        });
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] <= 0) continue;
            var column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(JBUI.scale(widths[i]));
            column.setMaxWidth(JBUI.scale(widths[i]));
            column.setMinWidth(JBUI.scale(widths[i]));
        }
        return table;
    }

    private static DefaultTableCellRenderer rightAligned() {
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.RIGHT);
        renderer.setBorder(JBUI.Borders.emptyRight(8));
        return renderer;
    }

    record Problem(boolean warning, int line, int start, int end, String message) {}

    static final class ProblemsModel extends AbstractTableModel {
        final List<Problem> rows = new ArrayList<>();
        void set(List<Problem> problems) { rows.clear(); rows.addAll(problems); fireTableDataChanged(); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int column) { return new String[]{"", "Line", "Problem"}[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Icon.class : column == 1 ? Integer.class : String.class; }
        @Override public Object getValueAt(int row, int column) {
            Problem problem = rows.get(row);
            return switch (column) {
                case 0 -> problem.warning ? AllIcons.General.Warning : AllIcons.General.Error;
                case 1 -> problem.line;
                default -> problem.message;
            };
        }
    }

    static final class LogRow {
        final SqlScript.Statement statement;
        SqlScriptRunner.Status status;
        boolean running;
        String result = "";
        long millis = -1;
        LogRow(SqlScript.Statement statement) { this.statement = statement; }
    }

    static final class LogModel extends AbstractTableModel {
        final List<LogRow> rows = new ArrayList<>();
        void clear() { rows.clear(); fireTableDataChanged(); }
        void start(List<SqlScript.Statement> statements) {
            rows.clear();
            for (SqlScript.Statement statement : statements) rows.add(new LogRow(statement));
            fireTableDataChanged();
        }
        void started(SqlScript.Statement statement) {
            LogRow row = rows.get(statement.index() - 1);
            row.running = true;
            row.result = "running";
            fireTableRowsUpdated(statement.index() - 1, statement.index() - 1);
        }
        void finished(SqlScript.Statement statement, SqlScriptRunner.Outcome outcome) {
            LogRow row = rows.get(statement.index() - 1);
            row.running = false;
            row.status = outcome.status();
            row.result = outcome.result();
            row.millis = outcome.status() == SqlScriptRunner.Status.NOT_RUN || outcome.status() == SqlScriptRunner.Status.CANCELLED && outcome.millis() == 0 ? -1 : outcome.millis();
            fireTableRowsUpdated(statement.index() - 1, statement.index() - 1);
        }
        void rolledBack(List<SqlScript.Statement> statements) {
            for (SqlScript.Statement statement : statements) {
                LogRow row = rows.get(statement.index() - 1);
                if (row.status != SqlScriptRunner.Status.OK) continue;
                row.status = SqlScriptRunner.Status.ROLLED_BACK;
                row.result = row.result + ", rolled back";
            }
            fireTableDataChanged();
        }
        int runningIndex() {
            for (LogRow row : rows) if (row.running) return row.statement.index();
            int done = 0;
            for (LogRow row : rows) if (row.status != null) done++;
            return done + 1;
        }
        int firstFailure() {
            for (LogRow row : rows) if (row.status == SqlScriptRunner.Status.FAILED) return row.statement.index();
            return 0;
        }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 6; }
        @Override public String getColumnName(int column) { return new String[]{"", "#", "Line", "Statement", "Result", "Time"}[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Icon.class : column == 1 || column == 2 ? Integer.class : String.class; }
        @Override public Object getValueAt(int index, int column) {
            LogRow row = rows.get(index);
            return switch (column) {
                case 0 -> row.running ? AnimatedIcon.Default.INSTANCE : icon(row.status);
                case 1 -> row.statement.index();
                case 2 -> row.statement.line();
                case 3 -> row.statement.summary(SUMMARY_LENGTH);
                case 4 -> row.result;
                default -> row.millis < 0 ? "" : row.millis < 1000 ? row.millis + " ms" : String.format(Locale.ROOT, "%.2f s", row.millis / 1000.0);
            };
        }
        private static @Nullable Icon icon(@Nullable SqlScriptRunner.Status status) {
            if (status == null) return null;
            return switch (status) {
                case OK -> AllIcons.General.GreenCheckmark;
                case FAILED -> AllIcons.General.Error;
                case NOT_RUN, CANCELLED -> AllIcons.RunConfigurations.TestIgnored;
                case ROLLED_BACK -> AllIcons.Actions.Rollback;
            };
        }
    }

    // ---- gutter ----------------------------------------------------------------------------------------------------

    /** Line numbers aligned with the preview's own lines, and the icon of the worst problem on each line. */
    private final class Gutter extends JComponent {
        Gutter() {
            setOpaque(true);
            setBackground(JBColor.namedColor("EditorGutter.background", new JBColor(0xF5F7FA, 0x2E3138)));
            setForeground(JBColor.namedColor("EditorLineNumber.foreground", UIUtil.getContextHelpForeground()));
            preview.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
                @Override protected void textChanged(@NotNull javax.swing.event.DocumentEvent event) { revalidate(); repaint(); }
            });
        }

        @Override public Dimension getPreferredSize() {
            int lines = preview.getDocument().getDefaultRootElement().getElementCount();
            int digits = Math.max(2, String.valueOf(lines).length());
            int width = getFontMetrics(preview.getFont()).charWidth('0') * digits + JBUI.scale(16) + AllIcons.General.Error.getIconWidth() + JBUI.scale(4);
            return new Dimension(width, preview.getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                com.intellij.ide.ui.UISettings.setupAntialiasing(g);
                g.setFont(preview.getFont());
                FontMetrics metrics = g.getFontMetrics();
                Element root = preview.getDocument().getDefaultRootElement();
                Rectangle clip = g.getClipBounds();
                for (int i = 0; i < root.getElementCount(); i++) {
                    Rectangle2D line;
                    try { line = preview.modelToView2D(root.getElement(i).getStartOffset()); }
                    catch (BadLocationException e) { continue; }
                    if (line == null) continue;
                    int top = (int) line.getY();
                    int height = (int) line.getHeight();
                    if (clip != null && (top + height < clip.y || top > clip.y + clip.height)) continue;
                    String number = String.valueOf(i + 1);
                    int baseline = top + (height - metrics.getHeight()) / 2 + metrics.getAscent();
                    g.setColor(getForeground());
                    g.drawString(number, getWidth() - JBUI.scale(8) - metrics.stringWidth(number), baseline);
                    Icon icon = lineIcons.get(i + 1);
                    if (icon != null) icon.paintIcon(this, g, JBUI.scale(4), top + (height - icon.getIconHeight()) / 2);
                }
            } finally {
                g.dispose();
            }
        }
    }

    @Override public void dispose() {
        SqlScriptRunner current = runner;
        if (current != null) current.cancel();
    }
}
