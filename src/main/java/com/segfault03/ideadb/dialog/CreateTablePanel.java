package com.segfault03.ideadb.dialog;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.provider.*;
import com.segfault03.ideadb.service.DdlService;
import com.segfault03.ideadb.ui.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;
import java.util.*;

/** Column Composer: persistent drafts and pure SQL preview, with no JDBC or dialog lifecycle. */
public final class CreateTablePanel extends JPanel {
    private final ConnectionConfig config;
    private final String namespace;
    private final List<DraftColumn> columns = new ArrayList<>();
    private final DefaultListModel<DraftColumn> listModel = new DefaultListModel<>();
    private final JList<DraftColumn> list = new JList<>(listModel);
    private final JBTextField tableName = DatabaseInputs.textField();
    private final JBTextField filter = DatabaseInputs.textField();
    private final JBTextField name = DatabaseInputs.textField();
    private final JComboBox<String> type;
    private final JBTextField length = DatabaseInputs.textField();
    private final JBTextField defaultValue = DatabaseInputs.textField();
    private final JCheckBox nullable = check("Allow NULL values", "Allow NULL values");
    private final JCheckBox primaryKey = check("Primary key", "Primary key");
    private final JCheckBox identity = check("Auto increment", "Auto increment");
    private final JButton add = DatabaseUi.action("", AllIcons.General.Add, "Add column");
    private final JButton addBottom = DatabaseUi.action("Add column…", AllIcons.General.Add, "Add another column");
    private final JButton remove = DatabaseUi.action("Remove", AllIcons.General.Remove, "Remove column");
    private final JButton up = DatabaseUi.action("", AllIcons.Actions.MoveUp, "Move column up");
    private final JButton down = DatabaseUi.action("", AllIcons.Actions.MoveDown, "Move column down");
    private final JButton create = DatabaseUi.dialogAction("Create table", AllIcons.General.Add, "Create new table");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel heading = new JLabel(), position = new JLabel(), count = new JLabel(), keys = new JLabel();
    private final JLabel summary = new JLabel(), status = new WrappingLabel("Ready"), empty = new JLabel("No matching columns");
    private final JTextPane sql = new JTextPane();
    private final JPanel form = vertical();
    private final Map<Component, Boolean> enabled = new IdentityHashMap<>();
    private DraftColumn selected;
    private List<Values> initialColumns = List.of();
    private String initialName = "", problem;
    private boolean binding, busy;
    private Prepared prepared;

    public record Prepared(TableCreation definition, String sql) {}
    private record Values(String name, String type, String length, boolean nullable, boolean primaryKey,
                          boolean identity, String defaultValue) {}
    public static final class DraftColumn {
        private Values values;
        private DraftColumn(Values values) { this.values = values; }
        public String getName() { return values.name.trim().isEmpty() ? "New column" : values.name.trim(); }
        private String formattedType() {
            return values.type + (TableAlteration.usesLength(values.type) && !values.length.isBlank() ? "(" + values.length.replace(" ", "") + ")" : "")
                    + (values.identity ? " · Auto" : "");
        }
        @Override public String toString() { return getName() + " : " + formattedType(); }
    }

    public CreateTablePanel(ConnectionConfig config, String namespace) {
        super(new BorderLayout()); this.config = config; this.namespace = namespace;
        var provider = DatabaseProviders.get(config.getType());
        type = DatabaseInputs.comboBox(provider.dataTypes().toArray(String[]::new)); type.setEditable(provider.supportsCustomTypes());
        setMinimumSize(JBUI.size(840, 620));
        add(header(), BorderLayout.NORTH);
        JPanel body = panel(new BorderLayout()); body.add(tableIdentity(), BorderLayout.NORTH);
        JPanel workspace = panel(new BorderLayout()); workspace.add(sidebar(), BorderLayout.WEST); workspace.add(inspector(), BorderLayout.CENTER);
        body.add(workspace, BorderLayout.CENTER); add(body, BorderLayout.CENTER); add(footer(), BorderLayout.SOUTH);
        DocumentListener edits = listener(this::edited);
        for (JTextField field : List.of(tableName, name, length, defaultValue)) field.getDocument().addDocumentListener(edits);
        if (type.isEditable()) ((JTextField)type.getEditor().getEditorComponent()).getDocument().addDocumentListener(edits);
        type.addActionListener(e -> edited()); nullable.addActionListener(e -> edited());
        for (JCheckBox constrained : List.of(primaryKey, identity)) constrained.addActionListener(e -> {
            if (binding) return;
            if (constrained.isSelected()) nullable.setSelected(false);
            edited();
        });
        filter.getDocument().addDocumentListener(listener(this::filterColumns));
        list.addListSelectionListener(e -> {
            if (binding || busy || e.getValueIsAdjusting() || list.getSelectedValue() == null) return;
            selected = list.getSelectedValue(); bind();
        });
        add.addActionListener(e -> addColumn()); addBottom.addActionListener(e -> addColumn());
        remove.addActionListener(e -> removeColumn()); up.addActionListener(e -> move(-1)); down.addActionListener(e -> move(1));
        String integer = config.getType() == DatabaseType.POSTGRESQL ? "INTEGER" : "INT";
        loadDefinition(new TableCreation("new_table", List.of(
                TableCreation.Column.parse("id", integer, "", false, true, true, ""),
                TableCreation.Column.parse("name", "VARCHAR", "255", false, false, false, ""),
                TableCreation.Column.parse("created_at", "TIMESTAMP", "", true, false, false, ""))));
    }
    private JComponent header() { return TableDialogChrome.header(config,namespace,"Create table"); }
    private JComponent tableIdentity() {
        JPanel identity=panel(new GridBagLayout());
        identity.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,border()),JBUI.Borders.empty(8,4,10,4)));
        JLabel label=muted("Table name");label.setLabelFor(tableName);tableName.getAccessibleContext().setAccessibleName("Table name");
        GridBagConstraints cell=new GridBagConstraints();cell.gridx=0;cell.gridy=0;cell.weightx=1;cell.fill=GridBagConstraints.HORIZONTAL;
        cell.anchor=GridBagConstraints.WEST;cell.insets=JBUI.insetsBottom(4);identity.add(label,cell);
        cell.gridy=1;cell.insets=JBUI.emptyInsets();cell.anchor=GridBagConstraints.BASELINE_LEADING;identity.add(tableName,cell);
        count.setForeground(accent());keys.setForeground(info());
        count.getAccessibleContext().setAccessibleName("Table column count");
        keys.getAccessibleContext().setAccessibleName("Table primary key count");
        cell.gridx=1;cell.weightx=0;cell.fill=GridBagConstraints.NONE;cell.insets=JBUI.insetsLeft(24);identity.add(count,cell);
        cell.gridx=2;cell.insets=JBUI.insetsLeft(16);identity.add(keys,cell);return identity;
    }
    private JComponent sidebar() {
        JPanel sidebar = panel(new BorderLayout(0, JBUI.scale(12))); sidebar.setPreferredSize(JBUI.size(210, 100));
        sidebar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, border()), JBUI.Borders.empty(10, 4, 10, 12)));
        JPanel top = vertical(); JPanel caption = panel(new BorderLayout()); JLabel title = new JLabel("Columns"); title.setFont(title.getFont().deriveFont(Font.BOLD));
        caption.add(title, BorderLayout.WEST); caption.add(add, BorderLayout.EAST); stack(top, caption); gap(top, 8);
        filter.getAccessibleContext().setAccessibleName("Filter columns"); filter.getEmptyText().setText("Filter columns"); filter.putClientProperty("JTextField.placeholderText", "Filter columns");
        JPanel search = panel(new BorderLayout(JBUI.scale(6), 0)); search.add(new JLabel(AllIcons.Actions.Search), BorderLayout.WEST); search.add(filter, BorderLayout.CENTER);
        stack(top, search); sidebar.add(top, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setFixedCellHeight(-1); list.setCellRenderer(new ColumnRenderer());
        list.getAccessibleContext().setAccessibleName("Table columns"); list.setOpaque(false);
        JBScrollPane scroll = new JBScrollPane(list); scroll.setBorder(JBUI.Borders.empty()); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel middle = panel(new BorderLayout()); middle.add(scroll, BorderLayout.CENTER); empty.setForeground(info()); middle.add(empty, BorderLayout.SOUTH); sidebar.add(middle, BorderLayout.CENTER);
        JPanel bottom = vertical(); JLabel hint = new WrappingLabel("Order follows the column list"); hint.setForeground(info()); stack(bottom, hint); gap(bottom, 12); stack(bottom, new JSeparator()); gap(bottom, 8);
        addBottom.setForeground(accent()); stack(bottom, addBottom); sidebar.add(bottom, BorderLayout.SOUTH); return sidebar;
    }
    private JComponent inspector() {
        JPanel detail = vertical(); detail.setBorder(JBUI.Borders.empty(10, 18, 12, 4));
        JPanel title = panel(new BorderLayout()); heading.setFont(TableDialogChrome.font(Font.BOLD,5));
        heading.getAccessibleContext().setAccessibleName("Selected column heading");
        position.setForeground(accent()); position.setFont(TableDialogChrome.font(Font.PLAIN,-2));
        title.add(TableDialogChrome.inline(heading,position), BorderLayout.CENTER); title.add(DatabaseUi.group(up, down, remove), BorderLayout.EAST);
        stack(detail, title); gap(detail, 4); stack(form, DatabaseUi.section("Definition"));
        stack(form, field("Column name", name)); gap(form, 8);
        JPanel types = panel(new BorderLayout(JBUI.scale(16), 0)); types.add(field("Data type", type), BorderLayout.CENTER);
        types.add(TableDialogLayout.width(field("Length / precision", length), 160), BorderLayout.EAST);
        stack(form, types); stack(form, DatabaseUi.section("Values & constraints"));
        stack(form, TableDialogChrome.constraints(nullable,primaryKey,identity)); gap(form, 8); stack(form, field("Default value", defaultValue)); gap(form, 4);
        defaultValue.getEmptyText().setText("No default"); defaultValue.putClientProperty("JTextField.placeholderText", "No default");
        stack(form, help("Blank means no default · TEXT: literal · SQL: expression")); stack(detail, form); gap(detail, 12); stack(detail, preview());
        JBScrollPane scroll = new JBScrollPane(detail); scroll.setBorder(JBUI.Borders.empty()); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); return scroll;
    }
    private JComponent preview() {
        JPanel preview = new RoundedPanel(); preview.setLayout(new BorderLayout(0, JBUI.scale(10))); preview.setBorder(JBUI.Borders.empty(12, 14));
        JPanel title = panel(new BorderLayout()); JLabel label = muted("SQL preview"); label.getAccessibleContext().setAccessibleName("Table SQL preview title"); label.setFont(label.getFont().deriveFont(Font.BOLD)); label.setIcon(Icons.CONSOLE); label.setIconTextGap(JBUI.scale(7));
        title.add(label, BorderLayout.CENTER); title.add(muted(config.getType().getDisplayName()), BorderLayout.EAST); preview.add(title, BorderLayout.NORTH);
        sql.setEditable(false); sql.setOpaque(false); sql.setBorder(JBUI.Borders.empty()); sql.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.round(TableDialogChrome.font(Font.PLAIN,-1).getSize2D())));
        sql.getAccessibleContext().setAccessibleName("Create table SQL preview"); JBScrollPane scroll = new JBScrollPane(sql); scroll.setBorder(JBUI.Borders.empty()); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        preview.add(scroll, BorderLayout.CENTER); preview.setPreferredSize(JBUI.size(400, 180)); return preview;
    }
    private JComponent footer() {
        JPanel footer = panel(new BorderLayout(JBUI.scale(16), 0)); footer.setBorder(JBUI.Borders.empty(10, 4, 0, 4));
        JPanel feedback = vertical(); summary.setFont(TableDialogChrome.font(Font.BOLD,-1)); stack(feedback, summary); gap(feedback, 3);
        status.setFont(TableDialogChrome.font(Font.PLAIN,-2)); status.getAccessibleContext().setAccessibleName("Create table status"); stack(feedback, status); footer.add(feedback, BorderLayout.CENTER);
        DatabaseUi.setActionEmphasis(create, DatabaseUi.POSITIVE_ACTION_COLOR);
        cancel.getAccessibleContext().setAccessibleName("Cancel create table"); footer.add(TableDialogChrome.buttons(cancel,create), BorderLayout.EAST); return footer;
    }
    private static JCheckBox check(String text, String accessibleName) {
        JCheckBox check = new JCheckBox(text); check.setOpaque(false); check.setBorder(JBUI.Borders.empty()); check.getAccessibleContext().setAccessibleName(accessibleName); return check;
    }

    @Override public Dimension getPreferredSize() { return TableDialogLayout.preferredSize(this, 920, 620, 700); }
    public Prepared prepared() { return prepared; }
    public JButton createButton() { return create; }
    public JButton cancelButton() { return cancel; }
    public JList<DraftColumn> columnList() { return list; }
    public JTextField tableNameField() { return tableName; }
    public String problem() { return problem; }
    public boolean dirty() { return !initialName.equals(tableName.getText()) || !initialColumns.equals(columns.stream().map(c -> c.values).toList()); }
    public void loadDefinition(TableCreation definition) {
        binding = true; tableName.setText(definition.name()); columns.clear();
        for (var c : definition.columns()) columns.add(new DraftColumn(new Values(c.name(), c.type(), TableAlteration.usesLength(c.type())
                ? TableAlteration.numeric(c.type()) ? c.size() + ", " + c.scale() : String.valueOf(c.size()) : "",
                c.nullable(), c.primaryKey(), c.autoIncrement(), c.defaultValue())));
        selected = columns.isEmpty() ? null : columns.getFirst(); filter.setText("");
        initialName = tableName.getText(); initialColumns = columns.stream().map(c -> c.values).toList(); binding = false; filterColumns(); bind();
    }
    private Values values() {
        String dataType = Objects.toString(type.isEditable() ? type.getEditor().getItem() : type.getSelectedItem(), "");
        return new Values(name.getText(), dataType, length.getText(), nullable.isSelected(), primaryKey.isSelected(), identity.isSelected(), defaultValue.getText());
    }
    private void bind() {
        binding = true;
        Values v = selected == null ? new Values("", "VARCHAR", "255", true, false, false, "") : selected.values;
        name.setText(v.name); boolean available = false; for (int i=0;i<type.getItemCount();i++) if (v.type.equals(type.getItemAt(i))) available=true;
        if (!available) type.addItem(v.type); type.setSelectedItem(v.type); length.setText(v.length); nullable.setSelected(v.nullable);
        primaryKey.setSelected(v.primaryKey); identity.setSelected(v.identity); defaultValue.setText(v.defaultValue);
        form.setVisible(selected != null); binding = false; edited(); revalidate(); repaint();
    }
    private void addColumn() {
        if (busy) return;
        int suffix = columns.size()+1; String columnName;
        do { columnName = "col_" + suffix++; } while (containsName(columnName));
        selected = new DraftColumn(new Values(columnName,"VARCHAR","255",true,false,false,"")); columns.add(selected);
        filter.setText(""); filterColumns(); bind(); name.requestFocusInWindow(); name.selectAll();
    }
    private boolean containsName(String value) { return columns.stream().anyMatch(c -> c.values.name.trim().equalsIgnoreCase(value)); }
    private void removeColumn() {
        if (busy || selected == null) return;
        int index = columns.indexOf(selected); columns.remove(selected); selected = columns.isEmpty() ? null : columns.get(Math.min(index, columns.size()-1));
        filterColumns(); bind();
    }
    private void move(int direction) {
        if (busy || selected == null) return;
        int index = columns.indexOf(selected), target = index + direction;
        if (target < 0 || target >= columns.size()) return;
        Collections.swap(columns, index, target); filterColumns(); edited();
    }
    private void filterColumns() {
        boolean previous = binding; binding = true; listModel.clear(); String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        for (DraftColumn column : columns) if (column.getName().toLowerCase(Locale.ROOT).contains(query)) listModel.addElement(column);
        list.clearSelection(); list.setSelectedValue(selected, true); empty.setVisible(listModel.isEmpty()); binding = previous;
    }
    private void edited() {
        if (binding) return;
        if (selected != null) selected.values = values();
        prepared = null; problem = null;
        List<TableCreation.Column> definitions = new ArrayList<>();
        for (DraftColumn column : columns) {
            Values v = column.values;
            try { definitions.add(TableCreation.Column.parse(v.name,v.type,v.length,v.nullable,v.primaryKey,v.identity,v.defaultValue)); }
            catch (IllegalArgumentException error) { if (problem == null) problem = column.getName() + " · " + error.getMessage(); }
        }
        TableCreation snapshot = new TableCreation(tableName.getText(), definitions);
        if (problem == null) problem = snapshot.problem(config.getType());
        if (problem == null) {
            try {
                String statement = DdlService.getInstance().buildCreateTableSql(config, namespace, snapshot.name(), snapshot.definitions());
                prepared = new Prepared(snapshot, statement); sql.setText(statement); sql.setCaretPosition(0); highlightSql();
            } catch (IllegalArgumentException | UnsupportedOperationException error) { problem = error.getMessage(); }
        }
        if (problem != null) sql.setText(problem);
        create.setEnabled(!busy && prepared != null);
        long keyColumns = columns.stream().filter(c -> c.values.primaryKey).count();
        count.setText(columns.size() + " column" + (columns.size() == 1 ? "" : "s"));
        keys.setText(keyColumns == 0 ? "No primary key" : keyColumns == 1 ? "1 primary key" : "Composite key (" + keyColumns + ")");
        summary.setText(count.getText() + " · " + keys.getText().toLowerCase(Locale.ROOT));
        heading.setText(selected == null ? "Add a column" : selected.getName());
        int index = selected == null ? -1 : columns.indexOf(selected); position.setText(index < 0 ? "" : String.format("Column %02d", index+1));
        up.setEnabled(!busy && index > 0); down.setEnabled(!busy && index >= 0 && index < columns.size()-1); remove.setEnabled(!busy && selected != null);
        name.setEnabled(!busy && selected != null); type.setEnabled(!busy && selected != null); primaryKey.setEnabled(!busy && selected != null);
        Values v = selected == null ? null : selected.values;
        length.setEnabled(!busy && v != null && TableAlteration.usesLength(v.type));
        nullable.setEnabled(!busy && v != null && !v.primaryKey && !v.identity);
        identity.setEnabled(!busy && v != null && (v.identity || TableCreation.identityType(config.getType(),v.type)));
        defaultValue.setEnabled(!busy && v != null && (!v.identity || !v.defaultValue.isBlank()));
        if (!busy) DatabaseUi.status(status, problem == null ? "Ready to create · " + namespace + "." + snapshot.name() : problem, problem == null ? DatabaseUi.Tone.NORMAL : DatabaseUi.Tone.ERROR);
        list.repaint();
    }
    public void status(String message, DatabaseUi.Tone tone) { DatabaseUi.status(status,message,tone); }
    public void setBusy(boolean busy) {
        if (this.busy == busy) return;
        this.busy = busy;
        if (busy) disable(this); else { enabled.forEach(Component::setEnabled); enabled.clear(); edited(); }
    }
    private void disable(Component component) {
        if (component == status) return;
        enabled.put(component,component.isEnabled()); component.setEnabled(false);
        if (component instanceof Container container) for (Component child:container.getComponents()) disable(child);
    }
    private void highlightSql() {
        StyledDocument doc = sql.getStyledDocument(); SimpleAttributeSet normal = new SimpleAttributeSet();
        StyleConstants.setForeground(normal,JBColor.namedColor("Label.foreground",new JBColor(0x000000,0xDFE1E5)));
        doc.setCharacterAttributes(0,doc.getLength(),normal,true);
        var tokens = java.util.regex.Pattern.compile("`(?:``|[^`])*`|\"(?:\"\"|[^\"])*\"|'(?:''|[^'])*'|\\b(?:CREATE|TABLE|PRIMARY|KEY|NOT|NULL|DEFAULT|AUTO_INCREMENT|GENERATED|BY|AS|IDENTITY|CURRENT_TIMESTAMP)\\b").matcher(sql.getText());
        SimpleAttributeSet keyword = new SimpleAttributeSet(); StyleConstants.setForeground(keyword,accent());
        while(tokens.find())if(Character.isLetter(tokens.group().charAt(0)))doc.setCharacterAttributes(tokens.start(),tokens.end()-tokens.start(),keyword,false);
    }
    private static DocumentListener listener(Runnable callback) { return new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { callback.run(); } public void removeUpdate(DocumentEvent e) { callback.run(); } public void changedUpdate(DocumentEvent e) { callback.run(); }
    }; }
    private static JPanel panel(LayoutManager layout) { JPanel p = new JPanel(layout); p.setOpaque(false); return p; }
    private static JPanel vertical() { return TableDialogLayout.column(); }
    private static void stack(JPanel p, JComponent c) { TableDialogLayout.stack(p, c); }
    private static void gap(JPanel p, int size) { p.add(Box.createVerticalStrut(JBUI.scale(size))); }
    private static JPanel field(String accessibleName, JComponent input) {
        JLabel label = new JLabel(accessibleName);
        JPanel p = panel(new BorderLayout(0, JBUI.scale(4))); label.setForeground(info()); label.setFont(TableDialogChrome.font(Font.PLAIN,-1));
        label.setLabelFor(input); input.getAccessibleContext().setAccessibleName(accessibleName);
        p.add(label, BorderLayout.NORTH); JPanel fixedHeight = panel(new BorderLayout()); fixedHeight.add(input, BorderLayout.NORTH);
        p.add(fixedHeight, BorderLayout.CENTER); return p;
    }
    private static JLabel help(String value) { JLabel l = new WrappingLabel(value); l.setForeground(info()); l.setFont(TableDialogChrome.font(Font.PLAIN,-1)); return l; }
    private static JLabel muted(String value) { JLabel l = new JLabel(value); l.setForeground(info()); l.setFont(TableDialogChrome.font(Font.PLAIN,-1)); return l; }
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
        @Override public Component getListCellRendererComponent(JList<? extends DraftColumn> list, DraftColumn column, int index, boolean selected, boolean focus) {
            JPanel row = new JPanel(new BorderLayout(JBUI.scale(9),0)) {
                @Override public Dimension getPreferredSize() {
                    Dimension size=super.getPreferredSize();size.height=Math.max(size.height,JBUI.scale(56));return size;
                }

                @Override protected void paintComponent(Graphics graphics) {
                    if (selected) {
                        Graphics2D g=(Graphics2D)graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(selectedBackground());g.fillRoundRect(0,2,getWidth()-1,getHeight()-4,JBUI.scale(8),JBUI.scale(8));
                        if(focus){g.setColor(accent());g.drawRoundRect(0,2,getWidth()-1,getHeight()-4,JBUI.scale(8),JBUI.scale(8));}g.dispose();
                    }
                    super.paintComponent(graphics);
                }
            };
            row.setOpaque(false);row.setBorder(JBUI.Borders.empty(9,10));row.add(new JLabel(column.values.primaryKey?Icons.KEY:Icons.COLUMN),BorderLayout.WEST);
            JPanel captions=vertical();JLabel title=new JLabel(column.getName());title.setFont(list.getFont());if(selected){title.setForeground(accent());title.setFont(title.getFont().deriveFont(Font.BOLD));}
            stack(captions,title);gap(captions,4);JLabel dataType=muted(column.formattedType());dataType.setFont(list.getFont().deriveFont(Math.max(10f,list.getFont().getSize2D()-1)));stack(captions,dataType);row.add(captions,BorderLayout.CENTER);
            row.add(muted(String.format("%02d",columns.indexOf(column)+1)),BorderLayout.EAST);return row;
        }
    }
}
