package com.segfault03.ideadb.dialog;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.provider.DatabaseProviders;
import com.segfault03.ideadb.ui.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;
import java.util.*;

/** Column Inspector view. JDBC work and dialog lifecycle belong to AlterTableDialog. */
public final class AlterTablePanel extends JPanel {
    private final ConnectionConfig config;
    private String tableName;
    private String tableDraft;
    private final List<DraftColumn> drafts = new ArrayList<>();
    private List<ColumnMetadata> columns;
    private Runnable changed = () -> {};
    private final DefaultListModel<DraftColumn> listModel = new DefaultListModel<>();
    private final JList<DraftColumn> columnList = new JList<>(listModel);
    private final JBTextField filter = DatabaseInputs.textField();
    private final JBTextField name = DatabaseInputs.textField();
    private final JComboBox<String> type;
    private final JBTextField length = DatabaseInputs.textField();
    private final JCheckBox nullable = new JCheckBox("Allow NULL values");
    private final JBTextField defaultValue = DatabaseInputs.textField();
    private final JLabel heading = new JLabel(), badge = new JLabel(), summary = new JLabel("No pending changes");
    private final JLabel columnCount = new JLabel("0 columns"), empty = new JLabel("No matching columns", SwingConstants.CENTER);
    private final JLabel status = new WrappingLabel("No pending changes");
    private final JLabel hint = new WrappingLabel(" ");
    private final JTextPane sql = new JTextPane();
    private final JPanel definition = vertical(), values = vertical(), warnings = vertical();
    private final JLabel warning = new WrappingLabel("");
    private final JPanel nameGroup;
    private final JLabel nameLabel = new JLabel("Column name");
    private final JButton add = DatabaseUi.action("", AllIcons.General.Add, "Add column");
    private final JButton refresh = DatabaseUi.action("", AllIcons.Actions.Refresh, "Reload table columns");
    private final JButton rename = DatabaseUi.action("Rename", AllIcons.Actions.Edit, "Rename column");
    private final JButton drop = DatabaseUi.action("Drop…", AllIcons.General.Remove, "Drop column");
    private final JButton renameTable = DatabaseUi.action("Rename table…", Icons.TABLE, "Rename table");
    private final JButton apply = DatabaseUi.action("Apply changes", AllIcons.Actions.Checked, "Apply table changes");
    private final JButton close = new JButton("Close");
    private final JButton revert = DatabaseUi.action("Revert", AllIcons.Actions.Rollback, "Revert column changes");
    private DraftColumn selected;
    private boolean tableMode;
    private TableAlterationBatch batch;
    private Prepared prepared;
    private String problem;
    private boolean binding, busy, recoveryRequired;
    private final Map<Component, Boolean> enabled = new IdentityHashMap<>();

    public record Prepared(TableAlterationBatch batch, List<String> statements) {
        public Prepared { statements = List.copyOf(statements); }
    }
    private record EditorValues(String name, String type, String length, boolean nullable, String defaultValue) {}

    /** Stable row identity: renaming or editing a draft never replaces its list entry. */
    public static final class DraftColumn {
        private final ColumnMetadata original;
        private EditorValues values;
        private boolean dropped;
        private DraftColumn(ColumnMetadata original, EditorValues values) { this.original = original; this.values = values; }
        public String getName() { return values.name.trim().isEmpty() ? "New column" : values.name.trim(); }
        public String getFormattedType() {
            return values.type + (TableAlteration.usesLength(values.type) && !values.length.isBlank() ? "(" + values.length.replace(" ", "") + ")" : "");
        }
        @Override public String toString() { return getName() + " : " + getFormattedType(); }
    }

    public AlterTablePanel(ConnectionConfig config, String namespace, String tableName,
                           List<ColumnMetadata> columns) {
        super(new BorderLayout());
        this.config = config; this.tableName = tableName; this.tableDraft = tableName; this.columns = List.copyOf(columns);
        type = DatabaseInputs.comboBox(DatabaseProviders.get(config.getType()).dataTypes().toArray(String[]::new));
        type.setEditable(DatabaseProviders.get(config.getType()).supportsCustomTypes());
        setPreferredSize(JBUI.size(940, 690)); setMinimumSize(JBUI.size(780, 570));
        setBorder(JBUI.Borders.empty());

        JPanel header = panel(new BorderLayout());
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, border()), JBUI.Borders.empty(8, 8, 18, 8)));
        JPanel identity = vertical();
        JLabel title = new JLabel(tableName, Icons.TABLE, SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f)); title.setIconTextGap(JBUI.scale(9));
        stack(identity, title); gap(identity, 7);
        stack(identity, muted(config.getName() + "   ›   " + namespace));
        header.add(identity, BorderLayout.WEST);
        JLabel source = muted(config.getType().getDisplayName());
        source.setIcon(Icons.dataSource(config.getType(), 24)); source.setIconTextGap(JBUI.scale(8));
        header.add(source, BorderLayout.EAST); add(header, BorderLayout.NORTH);

        JPanel body = panel(new BorderLayout());
        JPanel sidebar = panel(new BorderLayout(0, JBUI.scale(12)));
        sidebar.setPreferredSize(JBUI.size(218, 100));
        sidebar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, border()), JBUI.Borders.empty(18, 4, 14, 14)));
        JPanel top = vertical();
        JPanel titleRow = panel(new BorderLayout());
        JLabel caption = new JLabel("Columns"); caption.setFont(caption.getFont().deriveFont(Font.BOLD));
        titleRow.add(caption, BorderLayout.WEST); titleRow.add(DatabaseUi.group(refresh, add), BorderLayout.EAST);
        stack(top, titleRow); gap(top, 12);
        filter.getAccessibleContext().setAccessibleName("Filter columns");
        filter.putClientProperty("JTextField.placeholderText", "Filter columns");
        filter.getEmptyText().setText("Filter columns");
        JPanel search = panel(new BorderLayout(JBUI.scale(6), 0));
        search.add(new JLabel(AllIcons.Actions.Search), BorderLayout.WEST); search.add(filter, BorderLayout.CENTER);
        stack(top, search); sidebar.add(top, BorderLayout.NORTH);
        columnList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        columnList.setFixedCellHeight(JBUI.scale(61)); columnList.setCellRenderer(new ColumnRenderer());
        columnList.setOpaque(false); columnList.getAccessibleContext().setAccessibleName("Table columns");
        JBScrollPane listScroll = new JBScrollPane(columnList); listScroll.setBorder(JBUI.Borders.empty());
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel listArea = panel(new BorderLayout()); listArea.add(listScroll, BorderLayout.CENTER);
        empty.setForeground(info()); empty.setVisible(false); listArea.add(empty, BorderLayout.SOUTH);
        sidebar.add(listArea, BorderLayout.CENTER);
        JPanel sidebarBottom = vertical(); columnCount.setForeground(info());
        stack(sidebarBottom, columnCount); gap(sidebarBottom, 12); stack(sidebarBottom, new JSeparator()); gap(sidebarBottom, 8);
        renameTable.setForeground(accent()); stack(sidebarBottom, renameTable); sidebar.add(sidebarBottom, BorderLayout.SOUTH);
        body.add(sidebar, BorderLayout.WEST);

        JPanel detail = vertical(); detail.setBorder(JBUI.Borders.empty(18, 22, 20, 8));
        JPanel detailTitle = panel(new BorderLayout()); heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        badge.setForeground(accent()); badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
        JPanel selectedTitle = panel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        selectedTitle.add(heading); selectedTitle.add(badge);
        detailTitle.add(selectedTitle, BorderLayout.WEST); detailTitle.add(DatabaseUi.group(revert, rename, drop), BorderLayout.EAST);
        stack(detail, detailTitle); gap(detail, 12);
        stack(definition, DatabaseUi.section("Definition"));
        nameGroup = field(nameLabel, name, "Column name"); stack(definition, nameGroup); gap(definition, 12);
        JPanel typeRow = panel(new BorderLayout(JBUI.scale(16), 0));
        typeRow.add(field(new JLabel("Data type"), type, "Data type"), BorderLayout.CENTER);
        JPanel lengthGroup = field(new JLabel("Length / precision"), length, "Length / precision");
        lengthGroup.setPreferredSize(JBUI.size(160, lengthGroup.getPreferredSize().height));
        typeRow.add(lengthGroup, BorderLayout.EAST); stack(definition, typeRow); gap(definition, 7);
        hint.setForeground(info()); hint.setFont(hint.getFont().deriveFont(12f)); stack(definition, hint);
        stack(detail, definition);
        stack(values, DatabaseUi.section("Values"));
        nullable.setOpaque(false); nullable.setBorder(JBUI.Borders.empty()); stack(values, nullable); gap(values, 14);
        stack(values, field(new JLabel("Default value"), defaultValue, "Default value")); gap(values, 6);
        defaultValue.putClientProperty("JTextField.placeholderText", "No default");
        defaultValue.getEmptyText().setText("No default");
        defaultValue.setToolTipText("Blank removes the default. Use TEXT: for a literal or SQL: for an expression.");
        stack(values, muted("Blank removes the default · TEXT: literal · SQL: expression"));
        stack(detail, values);
        warnings.setBorder(JBUI.Borders.empty(14, 0)); warning.setForeground(JBColor.namedColor("Label.warningForeground", new JBColor(0x88600C, 0xE5BA6A)));
        stack(warnings, warning); stack(detail, warnings); gap(detail, 20);
        stack(detail, createPreview());
        JBScrollPane detailScroll = new JBScrollPane(detail); detailScroll.setBorder(JBUI.Borders.empty());
        detailScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        body.add(detailScroll, BorderLayout.CENTER); add(body, BorderLayout.CENTER);
        add(createFooter(), BorderLayout.SOUTH);

        DocumentListener edits = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { edited(); }
            public void removeUpdate(DocumentEvent e) { edited(); }
            public void changedUpdate(DocumentEvent e) { edited(); }
        };
        for (JTextField f : List.of(name, length, defaultValue)) f.getDocument().addDocumentListener(edits);
        if (type.isEditable()) ((JTextField)type.getEditor().getEditorComponent()).getDocument().addDocumentListener(edits);
        type.addActionListener(e -> edited()); nullable.addActionListener(e -> edited());
        filter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterColumns(); }
            public void removeUpdate(DocumentEvent e) { filterColumns(); }
            public void changedUpdate(DocumentEvent e) { filterColumns(); }
        });
        columnList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || binding || busy || columnList.getSelectedValue() == null) return;
            selected = columnList.getSelectedValue(); tableMode = false; bind();
        });
        add.addActionListener(e -> addDraft());
        rename.addActionListener(e -> {
            nameLabel.setText("New column name"); name.getAccessibleContext().setAccessibleName("New column name");
            name.requestFocusInWindow(); name.selectAll();
        });
        drop.addActionListener(e -> {
            if (selected == null || busy) return;
            if (selected.original == null) { drafts.remove(selected); selected = drafts.isEmpty() ? null : drafts.getLast(); }
            else selected.dropped = !selected.dropped;
            filterColumns(); bind();
        });
        revert.addActionListener(e -> {
            if (selected == null || busy) return;
            if (selected.original == null) { drafts.remove(selected); selected = drafts.isEmpty() ? null : drafts.getFirst(); }
            else { selected.values = valuesFor(selected.original); selected.dropped = false; }
            filterColumns(); bind();
        });
        renameTable.addActionListener(e -> { tableMode = true; bind(); });
        replaceColumns(columns, null);
    }

    public void onChange(Runnable callback) { changed = callback; }
    public JButton applyButton() { return apply; }
    public JButton closeButton() { return close; }
    public JButton refreshButton() { return refresh; }
    public JList<DraftColumn> columnList() { return columnList; }
    public TableAlterationBatch batch() { return batch; }
    public Prepared prepared() { return prepared; }
    public boolean dirty() { return !tableDraft.trim().equals(tableName) || drafts.stream().anyMatch(this::draftChanged); }

    private EditorValues editorValues() {
        Object selectedType = type.isEditable() ? type.getEditor().getItem() : type.getSelectedItem();
        return new EditorValues(name.getText(), Objects.toString(selectedType, ""), length.getText(), nullable.isSelected(), defaultValue.getText());
    }
    private static EditorValues valuesFor(ColumnMetadata column) {
        if (column == null) return new EditorValues("", "VARCHAR", "255", true, "");
        String size = column.getColumnSize() == 0 ? "" : TableAlteration.numeric(column.getTypeName())
                ? column.getColumnSize() + ", " + column.getDecimalDigits() : String.valueOf(column.getColumnSize());
        return new EditorValues(column.getName(), column.getTypeName(), size, column.isNullable(), Objects.toString(column.getDefaultValue(), ""));
    }
    private TableAlteration properties(DraftColumn draft) {
        EditorValues v = draft.values;
        return TableAlteration.column(draft.original == null ? TableAlteration.Kind.ADD : TableAlteration.Kind.MODIFY,
                draft.original, draft.original == null ? v.name : draft.original.getName(), v.type, v.length, v.nullable, v.defaultValue);
    }
    private boolean draftChanged(DraftColumn draft) {
        if (draft.dropped || draft.original == null || !draft.values.name.trim().equals(draft.original.getName())) return true;
        try { return properties(draft).changed(tableName); } catch (IllegalArgumentException error) { return true; }
    }
    private void addDraft() {
        if (busy) return;
        selected = new DraftColumn(null, valuesFor(null)); drafts.add(selected); tableMode = false;
        filter.setText(""); filterColumns(); bind(); name.requestFocusInWindow();
    }
    private void bind() {
        binding = true;
        EditorValues v = tableMode ? new EditorValues(tableDraft, "VARCHAR", "255", true, "")
                : selected == null ? valuesFor(null) : selected.values;
        boolean properties = !tableMode && selected != null && !selected.dropped;
        name.setText(v.name); name.setEditable(tableMode || properties);
        nameLabel.setText(tableMode ? "New table name" : "Column name"); name.getAccessibleContext().setAccessibleName(nameLabel.getText());
        boolean available = false; for (int i = 0; i < type.getItemCount(); i++) if (type.getItemAt(i).equals(v.type)) available = true;
        if (!available) type.addItem(v.type); type.setSelectedItem(v.type);
        length.setText(v.length); nullable.setSelected(v.nullable); defaultValue.setText(v.defaultValue);
        for (Component child : definition.getComponents()) child.setVisible(properties);
        nameGroup.setVisible(tableMode || properties); definition.setVisible(tableMode || properties); values.setVisible(properties);
        heading.setText(tableMode ? "Rename table" : selected == null ? "Select a column" : selected.dropped ? "Drop " + selected.getName() : selected.getName());
        boolean generated = selected != null && selected.original != null && selected.original.isGenerated();
        warning.setText(!tableMode && selected != null && selected.dropped ? "This column will be dropped when you apply the batch. Its values will be permanently deleted."
                : !tableMode && generated ? "This is a generated column. Edit its expression in the SQL console." : "");
        warnings.setVisible(!tableMode && selected != null && (selected.dropped || generated));
        rename.setVisible(properties); drop.setVisible(!tableMode && selected != null); revert.setVisible(!tableMode && selected != null);
        drop.setText(selected != null && selected.dropped ? "Restore" : selected != null && selected.original == null ? "Remove" : "Drop…");
        drop.getAccessibleContext().setAccessibleName(selected != null && selected.dropped ? "Restore column" : "Drop column");
        restoreSelection(); binding = false; edited(); revalidate(); repaint();
    }
    public void replaceColumns(List<ColumnMetadata> updated, String selectName) {
        columns = List.copyOf(updated); tableDraft = tableName; drafts.clear(); recoveryRequired = false;
        for (ColumnMetadata column : columns) drafts.add(new DraftColumn(column, valuesFor(column)));
        selected = drafts.stream().filter(c -> c.getName().equals(selectName)).findFirst().orElse(drafts.isEmpty() ? null : drafts.getFirst());
        tableMode = false; filterColumns(); bind();
    }

    /** Rebase desired definitions onto real metadata; successful additions/renames become existing rows. */
    public void recoverBatch(List<ColumnMetadata> updated, List<TableAlteration> completed, String actualTableName) {
        List<DraftColumn> old = new ArrayList<>(drafts);
        String desiredTableName = tableDraft;
        String selectedName = selected == null ? null : selected.getName();
        tableName = actualTableName; replaceColumns(updated, null); tableDraft = desiredTableName;
        Set<DraftColumn> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (DraftColumn draft : old) {
            if (!draftChanged(draft)) continue;
            if (draft.dropped && completed.stream().anyMatch(c -> c.kind() == TableAlteration.Kind.DROP
                    && c.original().getName().equals(draft.original.getName()))) continue;
            String liveName = draft.original == null ? draft.getName() : draft.original.getName();
            for (TableAlteration done : completed) if (done.kind() == TableAlteration.Kind.RENAME_COLUMN
                    && done.original().getName().equals(liveName)) liveName = done.name();
            final String lookup = liveName;
            DraftColumn live = drafts.stream().filter(c -> !used.contains(c) && c.original.getName().equals(lookup)).findFirst().orElse(null);
            boolean added = draft.original == null && completed.stream().anyMatch(c -> c.kind() == TableAlteration.Kind.ADD && c.name().equals(draft.getName()));
            if (draft.dropped && live == null) continue;
            if (live != null && (draft.original != null || added)) {
                live.values = draft.values; live.dropped = draft.dropped; used.add(live);
            } else {
                // Retain missing/conflicted edits visibly; validation blocks replay until resolved.
                drafts.add(draft); used.add(draft);
            }
        }
        selected = drafts.stream().filter(c -> c.getName().equals(selectedName)).findFirst().orElse(drafts.isEmpty() ? null : drafts.getFirst());
        filterColumns(); bind();
    }
    public void requireReload() { recoveryRequired = true; edited(); }
    private void restoreSelection() {
        boolean previous = binding; binding = true;
        if (!tableMode) columnList.setSelectedValue(selected, true); else columnList.clearSelection();
        binding = previous;
    }
    private void filterColumns() {
        boolean previous = binding; binding = true; listModel.clear();
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        for (DraftColumn draft : drafts) if (draft.getName().toLowerCase(Locale.ROOT).contains(query)
                || draft.original != null && draft.original.getName().toLowerCase(Locale.ROOT).contains(query)) listModel.addElement(draft);
        restoreSelection(); empty.setVisible(listModel.isEmpty()); binding = previous;
    }
    private void edited() {
        if (binding) return;
        if (tableMode) tableDraft = name.getText(); else if (selected != null && !selected.dropped) selected.values = editorValues();
        prepared = null; batch = null; problem = null; apply.setEnabled(false);
        List<TableAlteration> changes = new ArrayList<>();
        for (DraftColumn draft : drafts) {
            try {
                if (draft.dropped) {
                    if (draft.original != null) changes.add(new TableAlteration(TableAlteration.Kind.DROP, draft.original, draft.original.getName(), "", 0, 0, false, ""));
                    continue;
                }
                TableAlteration properties = properties(draft);
                if (draft.original == null || properties.changed(tableName)) changes.add(properties);
                if (draft.original != null && !draft.values.name.trim().equals(draft.original.getName()))
                    changes.add(new TableAlteration(TableAlteration.Kind.RENAME_COLUMN, draft.original, draft.values.name.trim(), "", 0, 0, false, ""));
            } catch (IllegalArgumentException error) { if (problem == null) problem = draft.getName() + " · " + error.getMessage(); }
        }
        if (!tableDraft.trim().equals(tableName)) changes.add(new TableAlteration(TableAlteration.Kind.RENAME_TABLE, null, tableDraft.trim(), "", 0, 0, false, ""));
        TableAlterationBatch candidate = new TableAlterationBatch(tableName, columns, changes);
        if (problem == null) problem = candidate.problem();
        if (problem == null && changes.stream().anyMatch(c -> !DatabaseProviders.get(config.getType()).features().contains(c.feature())))
            problem = "This database does not support one of the pending operations.";
        if (recoveryRequired) problem = "Reload columns before retrying; the database state could not be refreshed.";
        if (problem == null) batch = candidate;
        boolean pending = dirty();
        badge.setText(tableMode ? !tableDraft.trim().equals(tableName) ? "Renamed" : "" : selected == null ? "" : selected.dropped ? "To be dropped"
                : selected.original == null ? "New" : draftChanged(selected) ? "Modified" : "");
        long count = Math.max(changes.size(), drafts.stream().filter(this::draftChanged).count() + (!tableDraft.trim().equals(tableName) ? 1 : 0));
        summary.setText(pending ? count + " pending change" + (count == 1 ? "" : "s") : "No pending changes");
        apply.setText("Apply " + (count > 1 ? "all changes" : "changes"));
        DatabaseUi.setActionEmphasis(apply, changes.stream().anyMatch(c -> c.kind() == TableAlteration.Kind.DROP) ? DatabaseUi.DESTRUCTIVE_ACTION_COLOR : DatabaseUi.POSITIVE_ACTION_COLOR);
        long keys = columns.stream().filter(ColumnMetadata::isPrimaryKey).count();
        columnCount.setText(drafts.size() + " columns · " + keys + " primary key" + (keys == 1 ? "" : "s"));
        boolean properties = !tableMode && selected != null && !selected.dropped;
        ColumnMetadata original = selected == null ? null : selected.original;
        boolean generated = original != null && original.isGenerated();
        type.setEnabled(!busy && properties && !generated);
        String currentType = selected == null ? "" : selected.values.type;
        length.setEnabled(!busy && properties && !generated && TableAlteration.usesLength(currentType));
        nullable.setEnabled(!busy && properties && !generated && (original == null || !original.isPrimaryKey()));
        defaultValue.setEnabled(!busy && properties && !generated && (original == null || !original.isAutoIncrement()));
        revert.setEnabled(!busy && selected != null && draftChanged(selected));
        hint.setText(TableAlteration.numeric(currentType) ? "Precision, scale · for example 10, 2"
                : original != null ? "Original type: " + original.getFormattedType() : "Maximum number of characters or bytes.");
        sql.setText(problem != null && pending ? problem : pending ? "Preparing SQL preview…" : "Edit columns to preview the complete batch.");
        DatabaseUi.status(status, pending && problem != null ? problem : pending ? "Preparing batch preview…" : "Ready", pending && problem != null ? DatabaseUi.Tone.ERROR : DatabaseUi.Tone.NORMAL);
        columnList.repaint(); changed.run();
    }
    public boolean needsPreview() { return !busy && batch != null && !batch.changes().isEmpty() && !recoveryRequired; }
    public void showPreview(TableAlterationBatch request, List<String> statements) {
        if (!needsPreview() || !request.equals(batch)) return;
        prepared = new Prepared(request, statements); sql.setText(String.join("\n", statements)); sql.setCaretPosition(0);
        highlightSql(); apply.setEnabled(true); DatabaseUi.status(status, "Ready to apply · " + statements.size() + " SQL statement" + (statements.size() == 1 ? "" : "s"), DatabaseUi.Tone.NORMAL);
    }
    public void previewFailed(TableAlterationBatch request, String message) {
        if (!Objects.equals(request, batch) || busy) return;
        prepared = null; apply.setEnabled(false); sql.setText("Batch preview unavailable. Reload columns or correct the values to retry.");
        DatabaseUi.status(status, message, DatabaseUi.Tone.ERROR);
    }
    public void status(String message, DatabaseUi.Tone tone) { DatabaseUi.status(status, message, tone); }
    public void setBusy(boolean value) {
        busy = value;
        if (value) disable(this);
        else { enabled.forEach(Component::setEnabled); enabled.clear(); edited(); }
    }
    private void disable(Component c) {
        if (c == status) return;
        enabled.put(c, c.isEnabled()); c.setEnabled(false);
        if (c instanceof Container container) for (Component child : container.getComponents()) disable(child);
    }
    private JComponent createFooter() {
        JPanel footer = panel(new BorderLayout(JBUI.scale(16), 0));
        footer.setBorder(JBUI.Borders.empty(14, 8, 0, 8));
        JPanel feedback = vertical(); summary.setFont(summary.getFont().deriveFont(Font.BOLD, 12f));
        stack(feedback, summary); gap(feedback, 5); status.setFont(status.getFont().deriveFont(11f)); stack(feedback, status);
        status.getAccessibleContext().setAccessibleName("Alter table status"); footer.add(feedback, BorderLayout.CENTER);
        apply.setBorder(JBUI.Borders.empty(6, 12)); apply.setDefaultCapable(true);
        close.getAccessibleContext().setAccessibleName("Close alter table");
        footer.add(DatabaseUi.group(close, apply), BorderLayout.EAST); return footer;
    }
    private JComponent createPreview() {
        JPanel preview = new RoundedPanel(); preview.setLayout(new BorderLayout(0, JBUI.scale(10)));
        preview.setBorder(JBUI.Borders.empty(12, 14));
        JPanel title = panel(new BorderLayout());
        JLabel caption = muted("SQL preview"); caption.setFont(caption.getFont().deriveFont(Font.BOLD));
        caption.setIcon(Icons.CONSOLE); caption.setIconTextGap(JBUI.scale(7));
        title.add(caption, BorderLayout.WEST); title.add(muted(config.getType().getDisplayName()), BorderLayout.EAST);
        preview.add(title, BorderLayout.NORTH);
        sql.setEditable(false); sql.setOpaque(false); sql.setBorder(JBUI.Borders.empty());
        sql.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); sql.getAccessibleContext().setAccessibleName("Alter table SQL preview");
        JBScrollPane scroll = new JBScrollPane(sql); scroll.setBorder(JBUI.Borders.empty());
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        preview.add(scroll, BorderLayout.CENTER); preview.setPreferredSize(JBUI.size(400, 132));
        return preview;
    }
    private void highlightSql() {
        StyledDocument doc = sql.getStyledDocument(); SimpleAttributeSet normal = new SimpleAttributeSet();
        StyleConstants.setForeground(normal, UIManager.getColor("Label.foreground"));
        doc.setCharacterAttributes(0, doc.getLength(), normal, true);
        var tokens = java.util.regex.Pattern.compile("`(?:``|[^`])*`|\"(?:\"\"|[^\"])*\"|'(?:''|[^'])*'|\\b(?:ALTER|TABLE|COLUMN|ADD|MODIFY|DROP|RENAME|TO|SET|DATA|TYPE|DEFAULT|NULL|NOT|USING)\\b").matcher(sql.getText());
        SimpleAttributeSet keyword = new SimpleAttributeSet(); StyleConstants.setForeground(keyword, accent());
        while (tokens.find()) if (Character.isLetter(tokens.group().charAt(0))) doc.setCharacterAttributes(tokens.start(), tokens.end()-tokens.start(), keyword, false);
    }
    private static JPanel panel(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    private static JPanel vertical() {
        JPanel p = new JPanel() {
            @Override public void doLayout() {
                for (Component child : getComponents()) if (child instanceof JComponent c && !(c instanceof Box.Filler))
                    c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
                super.doLayout();
            }
        };
        p.setOpaque(false); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); return p;
    }
    private static void stack(JPanel p, JComponent c) { c.setAlignmentX(LEFT_ALIGNMENT); c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height)); p.add(c); }
    private static void gap(JPanel p, int size) { p.add(Box.createVerticalStrut(JBUI.scale(size))); }
    private static JPanel field(JLabel label, JComponent input, String accessibleName) {
        JPanel p = panel(new BorderLayout(0, JBUI.scale(6))); label.setForeground(info()); label.setFont(label.getFont().deriveFont(12f));
        label.setLabelFor(input); input.getAccessibleContext().setAccessibleName(accessibleName);
        p.add(label, BorderLayout.NORTH); JPanel fixedHeight = panel(new BorderLayout()); fixedHeight.add(input, BorderLayout.NORTH);
        p.add(fixedHeight, BorderLayout.CENTER); return p;
    }
    private static JLabel muted(String value) { JLabel l = new JLabel(value); l.setForeground(info()); l.setFont(l.getFont().deriveFont(12f)); return l; }
    private static Color info() { return JBColor.namedColor("Lattice.secondaryForeground", new JBColor(0x6B7078, 0xA8ADB5)); }
    private static Color accent() { return JBColor.namedColor("Link.activeForeground", new JBColor(0x3574F0, 0x82ABFF)); }
    private static Color border() { return JBColor.namedColor("Component.borderColor", new JBColor(0xD8DBE0, 0x505358)); }
    private static Color selectedBackground() { return JBColor.namedColor("List.selectionInactiveBackground", new JBColor(0xE8F0FF, 0x334760)); }
    private static final class RoundedPanel extends JPanel {
        RoundedPanel() { setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(JBColor.namedColor("EditorPane.background", new JBColor(0xF5F6F8, 0x303236)));
            g.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, JBUI.scale(10), JBUI.scale(10));
            g.setColor(border()); g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, JBUI.scale(10), JBUI.scale(10)); g.dispose(); super.paintComponent(graphics);
        }
    }
    private final class ColumnRenderer implements ListCellRenderer<DraftColumn> {
        @Override public Component getListCellRendererComponent(JList<? extends DraftColumn> list, DraftColumn column, int index, boolean isSelected, boolean focus) {
            JPanel row = new JPanel(new BorderLayout(JBUI.scale(9), 0)) {
                @Override protected void paintComponent(Graphics graphics) {
                    if (isSelected) {
                        Graphics2D g = (Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(selectedBackground()); g.fillRoundRect(0, 2, getWidth()-1, getHeight()-4, JBUI.scale(8), JBUI.scale(8));
                        if (focus) { g.setColor(accent()); g.drawRoundRect(0, 2, getWidth()-1, getHeight()-4, JBUI.scale(8), JBUI.scale(8)); } g.dispose();
                    }
                    super.paintComponent(graphics);
                }
            };
            row.setOpaque(false); row.setBorder(JBUI.Borders.empty(9, 10));
            row.add(new JLabel(column.original != null && column.original.isPrimaryKey() ? column.original != null && column.original.isForeignKey() ? Icons.PRIMARY_FOREIGN_KEY : Icons.KEY : column.original != null && column.original.isForeignKey() ? Icons.FOREIGN_KEY : Icons.COLUMN), BorderLayout.WEST);
            JPanel captions = vertical(); JLabel title = new JLabel(column.getName());
            if (isSelected) { title.setForeground(accent()); title.setFont(title.getFont().deriveFont(Font.BOLD)); }
            stack(captions, title); gap(captions, 4); stack(captions, muted(column.getFormattedType())); row.add(captions, BorderLayout.CENTER);
            if (draftChanged(column)) { JLabel dot = new JLabel(column.dropped ? "Drop" : column.original == null ? "New" : "●"); dot.setFont(dot.getFont().deriveFont(11f)); dot.setForeground(accent()); row.add(dot, BorderLayout.EAST); }
            row.getAccessibleContext().setAccessibleName(column.getName() + " " + column.getFormattedType()); return row;
        }
    }
}
