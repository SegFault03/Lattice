package com.segfault03.ideadb.sql.run;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.service.DryRunResult;
import com.segfault03.ideadb.ui.DatabaseUi;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The read-only content of the "Dry Run" tab of one connection: what was asked (connection, schema, kind, values) above
 * the plan, the rolled-back outcome or the reason there is none. Shows one run at a time. Used on the EDT only.
 */
public final class DryRunPanel extends JPanel {
    static final String LITERAL_NOTE = "Shown with the values written into the SQL; this database does not accept bound parameters here.";

    private final @Nullable Project project;
    private final ConnectionConfig config;
    private final JBLabel connection = new JBLabel();
    private final JBTextArea details = readOnlyArea();
    private final JBLabel banner = new JBLabel();
    private final JBLabel note = new JBLabel();
    private String bannerString = "";
    private String noteString = "";
    private final JBLabel badge = new JBLabel();
    private final JBTextArea body = readOnlyArea();
    private final JButton copy = DatabaseUi.action("Copy", AllIcons.Actions.Copy, "Copy the plan or the result");
    private final JButton rerun = DatabaseUi.action("Re-run", AllIcons.Actions.Refresh, "Run the same statement again with the same values");
    private final JButton sendToConsole = DatabaseUi.action("Send to Console", Icons.CONSOLE, "Put the statement, with its values written in, into the console");
    private final Font textFont = body.getFont();
    private final Font monospacedFont = ApplicationManager.getApplication() == null
            ? new Font(Font.MONOSPACED, Font.PLAIN, body.getFont().getSize())
            : EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
    private boolean running;
    private @Nullable String literalSql;
    private @Nullable Runnable rerunHandler;

    public DryRunPanel(@Nullable Project project, @NotNull ConnectionConfig config) {
        super(new BorderLayout());
        this.project = project;
        this.config = config;
        connection.setIcon(Icons.dataSource(config.getType(), 16));
        connection.setText(config.getName());
        connection.setFont(connection.getFont().deriveFont(Font.BOLD));
        banner.setForeground(DatabaseUi.DESTRUCTIVE_ACTION_COLOR);
        banner.setFont(banner.getFont().deriveFont(Font.BOLD));
        banner.setIcon(AllIcons.General.Error);
        badge.setForeground(DatabaseUi.POSITIVE_ACTION_COLOR);
        badge.setIcon(AllIcons.General.GreenCheckmark);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0));
        buttons.setOpaque(false);
        buttons.add(copy);
        buttons.add(rerun);
        buttons.add(sendToConsole);
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(JBUI.Borders.empty(8, 12));
        for (var part : List.of(connection, details, buttons)) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            header.add(part);
        }
        JPanel notices = new JPanel();
        notices.setLayout(new BoxLayout(notices, BoxLayout.Y_AXIS));
        notices.setBorder(JBUI.Borders.empty(0, 12, 4, 12));
        for (var part : List.of(banner, note, badge)) {
            part.setAlignmentX(LEFT_ALIGNMENT);
            part.setVisible(false);
            notices.add(part);
        }
        JPanel center = new JPanel(new BorderLayout());
        center.add(notices, BorderLayout.NORTH);
        body.setOpaque(true);
        body.setBorder(JBUI.Borders.empty(6, 12));
        center.add(new JBScrollPane(body), BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);

        copy.addActionListener(event -> CopyPasteManager.getInstance().setContents(new StringSelection(copyText())));
        rerun.addActionListener(event -> clickRerun());
        sendToConsole.addActionListener(event -> {
            if (project != null && literalSql != null) SqlRunActionSupport.openConsole(project, config, literalSql);
        });
        rerun.setEnabled(false);
        copy.setEnabled(false);
        sendToConsole.setEnabled(false);
    }

    private static JBTextArea readOnlyArea() {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty(2, 0));
        return area;
    }

    /** What happens when Re-run is pressed; set for each run, because it depends on which action started it. */
    void setRerunHandler(@NotNull Runnable handler) { rerunHandler = handler; }

    void clickRerun() {
        if (rerun.isEnabled() && rerunHandler != null) rerunHandler.run();
    }

    /** True from {@link #showRunning} until {@link #show}. */
    public boolean isRunning() { return running; }

    public void showRunning(@NotNull SqlRunTarget target, @NotNull String kindLabel, @NotNull Map<String, SqlParameterValue> values) {
        running = true;
        literalSql = literal(target, values);
        List<String> lines = new ArrayList<>();
        String schema = config.getDatabaseName();
        if (schema != null && !schema.isEmpty()) lines.add("Schema: " + schema);
        lines.add("Kind: " + kindLabel);
        values.forEach((key, value) -> lines.add(key + " = " + (value.type() == SqlParameterValue.Type.NULL ? "NULL" : value.text())));
        details.setText(String.join("\n", lines));
        showBody("Running… (" + kindLabel + ")", false, "", "", "");
        sendToConsole.setEnabled(literalSql != null);
    }

    private static @Nullable String literal(SqlRunTarget target, Map<String, SqlParameterValue> values) {
        if (target.connection() == null) return null;
        try {
            return SqlLiteralRenderer.render(target.sql(), target.parameters(), values, target.connection().getType());
        } catch (RuntimeException unusable) {
            return null;
        }
    }

    public void show(@NotNull DryRunResult result) {
        running = false;
        switch (result) {
            case DryRunResult.Plan plan -> showBody(plan.text(), true, "", plan.literalFallback() ? LITERAL_NOTE : "", "");
            case DryRunResult.RolledBack done -> {
                StringBuilder text = new StringBuilder(done.rows() + " row(s) affected\n" + done.elapsedMs() + " ms");
                for (String warning : done.warnings()) text.append('\n').append(warning);
                showBody(text.toString(), false, "", "", "Rolled back");
            }
            case DryRunResult.Failed failed -> showBody(failed.message(), false, "", "", "");
            case DryRunResult.OutcomeUnknown unknown -> showBody(unknown.message(), false,
                    "Outcome unknown: \"" + unknown.connectionName() + "\" may hold uncommitted changes", "", "");
            case DryRunResult.Cancelled cancelled -> showBody(cancelled.rolledBack() ? "Cancelled; any changes were rolled back" : "Cancelled", false, "", "", "");
        }
    }

    private void showBody(String text, boolean monospaced, String bannerText, String noteText, String badgeText) {
        body.setFont(monospaced ? monospacedFont : textFont);
        body.setText(text);
        body.setCaretPosition(0);
        bannerString = bannerText;
        banner.setText(bannerText);
        banner.setVisible(!bannerText.isEmpty());
        noteString = noteText;
        note.setText(noteText);
        note.setVisible(!noteText.isEmpty());
        badge.setText(badgeText);
        badge.setVisible(!badgeText.isEmpty());
        rerun.setEnabled(!running);
        copy.setEnabled(!running);
        revalidate();
        repaint();
    }

    /** The connection, schema, kind and values shown above the result. */
    String headerText() { return connection.getText() + "\n" + details.getText(); }

    /** Everything shown below the header, in reading order. */
    String bodyText() {
        List<String> parts = new ArrayList<>();
        if (!bannerString.isEmpty()) parts.add(bannerString);
        if (!noteString.isEmpty()) parts.add(noteString);
        if (!body.getText().isEmpty()) parts.add(body.getText());
        if (badge.isVisible()) parts.add(badge.getText());
        return String.join("\n", parts);
    }

    String bannerText() { return bannerString; }

    boolean rerunEnabled() { return rerun.isEnabled(); }

    boolean bodyMonospaced() { return body.getFont().equals(monospacedFont) && !monospacedFont.equals(textFont); }

    /** The plan as it is, or the summary of the outcome. */
    String copyText() { return body.getText().isEmpty() ? bodyText() : body.getText(); }
}
