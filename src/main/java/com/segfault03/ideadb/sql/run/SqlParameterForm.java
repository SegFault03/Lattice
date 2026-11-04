package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.card.SqlCardColors;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.view.SqlSwingHighlighter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The statement as the card shows it, with a field in the place of each parameter. A field says what it expects (its
 * placeholder and the line under the SQL, from {@link SqlParameterHints}), and a chip beside it holds the value's type,
 * which starts at the type the hint suggests and can be changed, NULL included. Every occurrence of a named parameter is
 * the same value: its fields share one document. Enter submits once every value fits its type; until then the first one
 * that does not is shown.
 */
public final class SqlParameterForm extends JPanel {
    private static final SqlParameterValue.Type[] TYPES = {
            SqlParameterValue.Type.TEXT, SqlParameterValue.Type.NUMBER, SqlParameterValue.Type.BOOLEAN,
            SqlParameterValue.Type.DATE, SqlParameterValue.Type.TIMESTAMP, SqlParameterValue.Type.NULL};
    /** Space around the content; also where the SQL starts, so the form can be laid over the card's own text. */
    static final int OUTER = 8;
    private static final int GAP = 6;
    private static final int MIN_CHARS = 4, MAX_CHARS = 36;

    private final CharSequence sql;
    private final SqlParameters.Result parameters;
    private final DatabaseType type;
    private final Map<String, Value> values = new LinkedHashMap<>();
    /** Every slot, in the order of the occurrences. */
    private final List<Slot> slots = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final JTextPane pane = new JTextPane();
    private final JBLabel status = new JBLabel();
    private final JButton submit;
    private final JPanel header;
    private final Font font;
    private @Nullable Runnable onSubmit;
    private @Nullable Runnable onCancel;
    private @Nullable Value focused;
    /** Set once the user has tried to submit: from then on every value that does not fit is marked, empty ones too. */
    private boolean showAllErrors;

    /** One parameter key: its hint, its type and the text its fields share. */
    private final class Value {
        final String key;
        SqlParameterHints.Hint hint;
        /** Whether the type is the user's own or remembered, so a hint that arrives later leaves it alone. */
        boolean typeChosen;
        final PlainDocument document = new PlainDocument();
        final List<Slot> slots = new ArrayList<>();
        SqlParameterValue.Type type;
        /** The type before NULL was picked, to return to. */
        SqlParameterValue.Type lastType;

        Value(String key, SqlParameterHints.Hint hint, SqlParameterValue initial, boolean chosen) {
            this.key = key;
            this.hint = hint;
            this.type = initial.type();
            this.typeChosen = chosen;
            this.lastType = initial.type() == SqlParameterValue.Type.NULL ? SqlParameterValue.Type.TEXT : initial.type();
            try {
                document.insertString(0, initial.text(), null);
            } catch (BadLocationException impossible) {
                throw new IllegalStateException(impossible);
            }
            document.addDocumentListener(new DocumentAdapter() {
                @Override protected void textChanged(@NotNull DocumentEvent event) { changed(); }
            });
        }

        String text() {
            try {
                return document.getText(0, document.getLength());
            } catch (BadLocationException impossible) {
                return "";
            }
        }

        SqlParameterValue current() { return new SqlParameterValue(type, text()); }

        void type(SqlParameterValue.Type next) {
            if (next != SqlParameterValue.Type.NULL) lastType = next;
            type = next;
            changed();
        }

        /** What a field shows while it is empty: the value's format, else the name of the parameter or its column. */
        String placeholder() {
            String name = key.startsWith(":") ? key : hint.column() != null ? shortName(hint.column()) : hint.note() != null && hint.note().equals("row count") ? "count" : null;
            return switch (type) {
                case DATE -> "yyyy-MM-dd";
                case TIMESTAMP -> "yyyy-MM-dd HH:mm:ss";
                case NULL -> "NULL";
                case NUMBER -> name != null ? name : "number";
                case BOOLEAN -> name != null ? name : "true/false";
                case TEXT -> name != null ? name : "text";
            };
        }

        /** How the status line names the parameter: as written (":name", "$2"), or "?2" for the second "?". */
        String label;

        String label() { return label != null ? label : key; }

        @Nullable String error() {
            if (type != SqlParameterValue.Type.NULL && type != SqlParameterValue.Type.TEXT && text().isBlank()) return "Enter a value";
            return current().validationError();
        }
    }

    /** One occurrence of a parameter in the SQL: a field and the type chip. */
    private final class Slot extends JPanel {
        final Value value;
        final JBTextField field = new JBTextField();
        final JBLabel chip = new JBLabel();

        Slot(Value value) {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            this.value = value;
            setOpaque(false);
            field.setDocument(value.document);
            field.setFont(font);
            // Compact, so a line with a field is barely taller than the SQL around it.
            field.putClientProperty("JComponent.compactHeight", Boolean.TRUE);
            field.addActionListener(event -> trySubmit());
            field.getAccessibleContext().setAccessibleName("Value of " + value.label());
            field.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) {
                    focused = value;
                    field.selectAll();
                    updateStatus();
                }
            });
            chip.setFont(JBUI.Fonts.smallFont());
            chip.setForeground(UIUtil.getContextHelpForeground());
            chip.setBorder(JBUI.Borders.empty(0, 3, 0, 1));
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chip.getAccessibleContext().setAccessibleName("Type of " + value.label());
            chip.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) { chooseType(Slot.this); }
            });
            add(field);
            add(chip);
            refresh();
        }

        void refresh() {
            boolean isNull = value.type == SqlParameterValue.Type.NULL;
            field.setEnabled(!isNull);
            field.getEmptyText().setText(value.placeholder());
            chip.setText(chipText(value.type) + " ▾");
            String hint = value.hint.describe();
            field.setToolTipText(hint.isEmpty() ? value.label() : value.label() + " · " + hint);
            chip.setToolTipText("Type of " + value.label() + ": click to change");
            boolean marked = (showAllErrors || !value.text().isEmpty()) && value.error() != null;
            field.putClientProperty("JComponent.outline", marked ? "error" : null);
            field.repaint();
        }

        /** As wide as its text or placeholder, within bounds, so the SQL around it keeps its shape. */
        @Override public Dimension getPreferredSize() {
            FontMetrics metrics = field.getFontMetrics(font);
            String shown = value.text().isEmpty() ? value.placeholder() : value.text();
            int width = Math.max(metrics.stringWidth(shown), metrics.charWidth('m') * MIN_CHARS);
            width = Math.min(width, metrics.charWidth('m') * MAX_CHARS);
            Insets insets = field.getInsets();
            // A character to spare, so the caret after the last one never scrolls the text.
            field.setPreferredSize(new Dimension(width + insets.left + insets.right + metrics.charWidth('m'), fieldHeight()));
            return super.getPreferredSize();
        }

        /** The text's line height and a little room for the field's border, never more than the field asks for. */
        private int fieldHeight() {
            int natural = field.getUI().getPreferredSize(field).height;
            return Math.min(natural, field.getFontMetrics(font).getHeight() + JBUI.scale(6));
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        /** Lines the field's text up with the SQL's baseline. */
        @Override public float getAlignmentY() {
            Dimension size = getPreferredSize();
            Dimension fieldSize = field.getPreferredSize();
            int baseline = field.getBaseline(fieldSize.width, fieldSize.height);
            if (baseline < 0 || size.height <= 0) return 0.75f;
            return Math.min(1f, (float) (baseline + (size.height - fieldSize.height) / 2) / size.height);
        }
    }

    /**
     * @param hints      what each key expects, by key; keys without one get none
     * @param initial    values to start from; a key without one starts empty, with the type its hint suggests
     * @param actionText the name of what submitting does, for the button ("Run", "Run and Roll Back", "Send to Console")
     */
    public SqlParameterForm(@NotNull CharSequence sql, @NotNull SqlParameters.Result parameters, @NotNull DatabaseType type,
                            @Nullable ConnectionConfig connection, @NotNull Map<String, SqlParameterHints.Hint> hints,
                            @NotNull Map<String, SqlParameterValue> initial, @NotNull String actionText) {
        super(new BorderLayout(0, GAP));
        this.sql = sql;
        this.parameters = parameters;
        this.type = type;
        // Tests and previews can run without an application, hence the monospaced fallback.
        boolean ide = ApplicationManager.getApplication() != null;
        this.font = ide ? EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN)
                : new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(13));
        Color fill = ide ? SqlCardColors.fill(EditorColorsManager.getInstance().getGlobalScheme()) : JBColor.PanelBackground;
        setBackground(fill);
        setBorder(JBUI.Borders.empty(OUTER));

        for (String key : parameters.keys()) {
            SqlParameterHints.Hint hint = hints.getOrDefault(key, SqlParameterHints.Hint.NONE);
            SqlParameterValue start = initial.get(key);
            boolean remembered = start != null;
            if (start == null) start = new SqlParameterValue(hint.suggested() != null ? hint.suggested() : SqlParameterValue.Type.TEXT, "");
            values.put(key, new Value(key, hint, start, remembered));
        }

        submit = new JButton(actionText);
        submit.addActionListener(event -> trySubmit());
        header = header(actionText, connection);
        add(header, BorderLayout.NORTH);
        buildPane(connection);
        add(pane, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "lattice.submit");
        getActionMap().put("lattice.submit", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { trySubmit(); }
        });
        updateStatus();
    }

    private JPanel header(String actionText, @Nullable ConnectionConfig connection) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        row.setOpaque(false);
        JBLabel title = new JBLabel(connection == null ? actionText + " with values" : actionText + " on " + connection.getName());
        title.setFont(JBUI.Fonts.label().asBold());
        row.add(title, BorderLayout.CENTER);
        JBLabel keys = new JBLabel("Enter to " + actionText.toLowerCase(java.util.Locale.ROOT) + " · Esc to cancel");
        keys.setFont(JBUI.Fonts.smallFont());
        keys.setForeground(UIUtil.getContextHelpForeground());
        row.add(keys, BorderLayout.EAST);
        return row;
    }

    private JPanel footer() {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        row.setOpaque(false);
        status.setFont(JBUI.Fonts.smallFont());
        // The status line takes the room the buttons leave; it never widens the form, which would squeeze the buttons.
        status.setMinimumSize(new Dimension(0, 0));
        status.setPreferredSize(new Dimension(0, status.getPreferredSize().height));
        row.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> { if (onCancel != null) onCancel.run(); });
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(JBUI.scale(4)));
        submit.putClientProperty("JButton.buttonType", "default");
        buttons.add(submit);
        row.add(buttons, BorderLayout.EAST);
        return row;
    }

    /** The SQL, colored as the editor colors it, with a slot in place of each parameter occurrence. */
    private void buildPane(@Nullable ConnectionConfig connection) {
        pane.setEditable(false);
        pane.setFocusable(false);
        pane.setOpaque(false);
        pane.setFont(font);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getAccessibleContext().setAccessibleName("SQL with parameter values");
        JTextPane colored = new JTextPane();
        colored.setFont(font);
        colored.setText(sql.toString());
        if (connection != null && ApplicationManager.getApplication() != null) SqlSwingHighlighter.apply(colored, connection, null);
        else SqlSwingHighlighter.apply(colored, SqlDialects.of(type));
        StyledDocument source = colored.getStyledDocument();
        StyledDocument target = pane.getStyledDocument();
        List<int[]> hidden = hiddenRanges(sql);
        int at = 0;
        try {
            for (SqlParameters.Occurrence occurrence : parameters.occurrences()) {
                copy(source, target, at, occurrence.start(), hidden);
                Value value = values.get(occurrence.key());
                if (value.label == null) {
                    String written = sql.subSequence(occurrence.start(), occurrence.end()).toString();
                    value.label = written.equals("?") ? "?" + occurrence.key() : written;
                }
                Slot slot = new Slot(value);
                slots.add(slot);
                value.slots.add(slot);
                SimpleAttributeSet holder = new SimpleAttributeSet();
                StyleConstants.setComponent(holder, slot);
                target.insertString(target.getLength(), " ", holder);
                at = occurrence.end();
            }
            copy(source, target, at, sql.length(), hidden);
        } catch (BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * What the form leaves out of the SQL: blank lines before and after it, and the indentation every line shares, as
     * SQL in a text block or a concatenation carries its code's indentation. Ascending, non-overlapping {start, end}.
     */
    static @NotNull List<int[]> hiddenRanges(@NotNull CharSequence sql) {
        List<int[]> hidden = new ArrayList<>();
        int length = sql.length();
        int last = length;
        while (last > 0 && Character.isWhitespace(sql.charAt(last - 1))) last--;
        if (last == 0) return hidden;
        int first = 0;
        for (int i = 0; i < last && Character.isWhitespace(sql.charAt(i)); i++) if (sql.charAt(i) == '\n') first = i + 1;
        if (first > 0) hidden.add(new int[] {0, first});
        int indent = Integer.MAX_VALUE;
        for (int line = first; line < last; ) {
            int i = line;
            while (i < last && (sql.charAt(i) == ' ' || sql.charAt(i) == '\t')) i++;
            if (i < last && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') indent = Math.min(indent, i - line);
            int next = i;
            while (next < last && sql.charAt(next) != '\n') next++;
            line = next + 1;
        }
        for (int line = first; indent > 0 && indent != Integer.MAX_VALUE && line < last; ) {
            int i = line;
            while (i < last && i - line < indent && (sql.charAt(i) == ' ' || sql.charAt(i) == '\t')) i++;
            if (i > line) hidden.add(new int[] {line, i});
            int next = i;
            while (next < last && sql.charAt(next) != '\n') next++;
            line = next + 1;
        }
        if (last < length) hidden.add(new int[] {last, length});
        return hidden;
    }

    /** Appends {@code source[from, to)} without the hidden ranges to {@code target}, run by run with its attributes. */
    private static void copy(StyledDocument source, StyledDocument target, int from, int to, List<int[]> hidden) throws BadLocationException {
        int at = from;
        while (at < to) {
            int stop = to;
            boolean skip = false;
            for (int[] range : hidden) {
                if (range[1] <= at) continue;
                if (range[0] <= at) { stop = Math.min(range[1], to); skip = true; }
                else stop = Math.min(range[0], to);
                break;
            }
            if (skip) { at = stop; continue; }
            Element run = source.getCharacterElement(at);
            int end = Math.min(run.getEndOffset(), stop);
            AttributeSet attributes = run.getAttributes().copyAttributes();
            target.insertString(target.getLength(), source.getText(at, end - at), attributes);
            at = end;
        }
    }

    private void chooseType(Slot slot) {
        Value value = slot.value;
        var step = new BaseListPopupStep<>("Value Type", TYPES) {
            @Override public @NotNull String getTextFor(SqlParameterValue.Type item) { return typeName(item); }
            @Override public @Nullable PopupStep<?> onChosen(SqlParameterValue.Type chosen, boolean finalChoice) {
                value.typeChosen = true;
                value.type(chosen);
                if (chosen != SqlParameterValue.Type.NULL) slot.field.requestFocusInWindow();
                return FINAL_CHOICE;
            }
        };
        step.setDefaultOptionIndex(java.util.Arrays.asList(TYPES).indexOf(value.type));
        JBPopupFactory.getInstance().createListPopup(step).show(new RelativePoint(slot.chip, new Point(0, slot.chip.getHeight())));
    }

    private void changed() {
        for (Value value : values.values()) for (Slot slot : value.slots) slot.refresh();
        pane.revalidate();
        pane.repaint();
        updateStatus();
        listeners.forEach(Runnable::run);
    }

    /** The first value that does not fit its type, else what the focused parameter expects. */
    private void updateStatus() {
        updateStatusText();
        status.setToolTipText(status.getText().isBlank() ? null : status.getText());
    }

    private void updateStatusText() {
        Value invalid = firstInvalid(showAllErrors);
        Value shown = invalid != null ? invalid : focused;
        if (parameters.error() != null) {
            status.setForeground(JBColor.RED);
            status.setText(parameters.error());
        } else if (invalid != null) {
            status.setForeground(JBColor.namedColor("Label.errorForeground", JBColor.RED));
            status.setText(invalid.label() + ": " + invalid.error());
        } else if (shown != null) {
            status.setForeground(UIUtil.getContextHelpForeground());
            String hint = shown.hint.describe();
            status.setText(hint.isEmpty() ? shown.label() + " · " + typeName(shown.type) : shown.label() + " · " + hint);
        } else {
            status.setText(" ");
        }
        submit.setEnabled(parameters.error() == null);
    }

    private @Nullable Value firstInvalid(boolean includeEmpty) {
        for (Value value : values.values()) {
            if (value.error() == null) continue;
            if (includeEmpty || !value.text().isEmpty()) return value;
        }
        return null;
    }

    private void trySubmit() {
        if (!valuesValid()) {
            showAllErrors = true;
            changed();
            Value invalid = firstInvalid(true);
            if (invalid != null && !invalid.slots.isEmpty()) invalid.slots.get(0).field.requestFocusInWindow();
            return;
        }
        if (onSubmit != null) onSubmit.run();
    }

    /** The field that takes the focus first: the first parameter's first occurrence. */
    public @Nullable JComponent firstField() {
        for (Value value : values.values()) if (!value.slots.isEmpty()) return value.slots.get(0).field;
        return null;
    }

    /** Where the SQL's first character sits in the form, so the form can be placed with its SQL over the card's. */
    @NotNull Point sqlOrigin() {
        Insets insets = getInsets();
        return new Point(insets.left, insets.top + header.getPreferredSize().height + JBUI.scale(GAP));
    }

    /** Lays the SQL out no wider than {@code maxWidth} pixels, wrapping long lines. */
    void fitWidth(int maxWidth) {
        pane.setPreferredSize(null);
        Dimension natural = pane.getPreferredSize();
        int width = Math.min(natural.width, Math.max(JBUI.scale(240), maxWidth));
        if (width == natural.width) return;
        pane.setSize(width, Short.MAX_VALUE);
        pane.setPreferredSize(new Dimension(width, pane.getPreferredSize().height));
    }

    /**
     * Hints worked out again, once the schema has been read: each parameter shows its new hint, and one whose type the
     * user has not chosen (nor had remembered) takes the type the new hint suggests.
     */
    public void updateHints(@NotNull Map<String, SqlParameterHints.Hint> hints) {
        for (Value value : values.values()) {
            SqlParameterHints.Hint hint = hints.get(value.key);
            if (hint == null) continue;
            value.hint = hint;
            if (!value.typeChosen && hint.suggested() != null && value.type != SqlParameterValue.Type.NULL) {
                value.type = hint.suggested();
                value.lastType = hint.suggested();
            }
        }
        changed();
    }

    public void onSubmit(@NotNull Runnable handler) { onSubmit = handler; }
    public void onCancel(@NotNull Runnable handler) { onCancel = handler; }

    /** Called after any value or type changes. */
    public void addChangeListener(@NotNull Runnable listener) { listeners.add(listener); }

    /** The values as entered, by key, in the order of the keys. */
    public @NotNull Map<String, SqlParameterValue> values() {
        Map<String, SqlParameterValue> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value.current()));
        return result;
    }

    /** Whether submitting would go ahead. Named so it does not override {@link java.awt.Component#isValid()}. */
    public boolean valuesValid() {
        return parameters.error() == null && values.values().stream().allMatch(value -> value.error() == null);
    }

    /** The SQL with the values written in, or null while one does not fit its type. */
    public @Nullable String renderedSql() {
        if (!valuesValid()) return null;
        return SqlLiteralRenderer.render(sql, parameters, values(), type);
    }

    @TestOnly @NotNull String statusText() { return status.getText(); }

    @TestOnly @NotNull List<JBTextField> fieldsForTests() {
        return slots.stream().map(slot -> slot.field).toList();
    }

    @TestOnly void typeForTests(@NotNull String key, @NotNull SqlParameterValue.Type next) { values.get(key).type(next); }

    @TestOnly @NotNull String chipForTests(@NotNull String key) { return values.get(key).slots.get(0).chip.getText(); }

    @TestOnly @NotNull String paneTextForTests() { return pane.getText(); }

    @TestOnly void submitForTests() { trySubmit(); }

    private static String chipText(SqlParameterValue.Type type) {
        return switch (type) {
            case TEXT -> "text";
            case NUMBER -> "number";
            case BOOLEAN -> "bool";
            case DATE -> "date";
            case TIMESTAMP -> "timestamp";
            case NULL -> "NULL";
        };
    }

    private static String typeName(SqlParameterValue.Type type) {
        return switch (type) {
            case TEXT -> "Text";
            case NUMBER -> "Number";
            case BOOLEAN -> "Boolean";
            case DATE -> "Date";
            case TIMESTAMP -> "Timestamp";
            case NULL -> "NULL";
        };
    }

    private static String shortName(String column) {
        int dot = column.lastIndexOf('.');
        return dot < 0 ? column : column.substring(dot + 1);
    }
}
