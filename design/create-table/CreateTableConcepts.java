import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.segfault03.ideadb.dialog.CreateTableDialog;
import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.ui.DatabaseInputs;
import com.segfault03.ideadb.ui.DatabaseTable;
import com.segfault03.ideadb.ui.DatabaseUi;
import com.segfault03.ideadb.ui.Icons;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

/**
 * Three isolated Swing concepts with fixed schema and SQL fixtures. The new
 * layouts reuse shared plugin controls and have no business action listeners,
 * SQL generation or database callbacks. The production baseline is painted
 * separately for comparison.
 */
public final class CreateTableConcepts {
    private static boolean dark;
    private static Color bg, surface, line, muted, accent, selection, ink;
    private static final int WIDTH = 1120, HEIGHT = 820;
    private static final String SQL = """
            CREATE TABLE `shop`.`customers` (
              `id` BIGINT NOT NULL AUTO_INCREMENT,
              `name` VARCHAR(120) NOT NULL,
              `email` VARCHAR(255) NOT NULL,
              `status` VARCHAR(20) NOT NULL DEFAULT 'active',
              `balance` DECIMAL(10,2) DEFAULT 0,
              `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (`id`)
            );""";
    private static final String[][] COLUMNS = {
            {"id", "BIGINT", "Primary key · Auto increment"}, {"name", "VARCHAR(120)", "Required"},
            {"email", "VARCHAR(255)", "Required"}, {"status", "VARCHAR(20)", "Default 'active'"},
            {"balance", "DECIMAL(10,2)", "Nullable · Default 0"}, {"created_at", "TIMESTAMP", "Default CURRENT_TIMESTAMP"}
    };

    private static Color tone(int light, int night) { return new Color(dark ? night : light); }
    private static Border pad(int t, int l, int b, int r) {
        return BorderFactory.createEmptyBorder(t, l, b, r);
    }
    private static JPanel panel(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }
    private static JPanel vertical() {
        JPanel p = panel(null);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }
    private static JLabel label(String text, int size, boolean bold, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(UIManager.getFont("Label.font").deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size));
        l.setForeground(color);
        return l;
    }
    private static JLabel text(String value) { return label(value, 13, false, ink); }
    private static JLabel note(String value) { return label(value, 12, false, muted); }
    private static JLabel mono(String value, int size, Color color) {
        JLabel l = label(value, size, false, color);
        l.setFont(new Font("DejaVu Sans Mono", Font.PLAIN, size));
        return l;
    }
    private static JPanel row(int gap, Component... children) {
        JPanel p = panel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        for (Component child : children) p.add(child);
        return p;
    }
    private static void stack(JPanel target, JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension preferred = component.getPreferredSize();
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
        target.add(component);
    }
    private static void gap(JPanel target, int height) { target.add(Box.createVerticalStrut(height)); }
    private static JComponent rule() {
        JPanel p = new JPanel(); p.setBackground(line);
        p.setPreferredSize(new Dimension(1, 1));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return p;
    }
    private static JPanel section(String name) { return DatabaseUi.section(name); }
    private static JLabel tag(String value, Color foreground, Color background) {
        JLabel l = label(value, 11, true, foreground);
        l.setBorder(pad(4, 8, 4, 8));
        l.setOpaque(true); l.setBackground(background);
        l.putClientProperty("FlatLaf.style", "arc: 8");
        return l;
    }
    private static class RoundedPanel extends JPanel {
        private final Color fill, stroke;
        RoundedPanel(LayoutManager layout, Color fill, Color stroke) {
            super(layout); this.fill = fill; this.stroke = stroke; setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (fill != null) { g.setColor(fill); g.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10); }
            if (stroke != null) { g.setColor(stroke); g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10); }
            g.dispose(); super.paintComponent(graphics);
        }
    }
    private static JButton quiet(String value, Icon icon) {
        return DatabaseUi.action(value, icon, value);
    }
    private static JButton link(String value) {
        JButton b = quiet(value, null); b.setForeground(accent); return b;
    }
    private static JComponent field(String title, JComponent input) {
        JPanel p = panel(new BorderLayout(0, 6));
        p.add(label(title, 12, false, muted), BorderLayout.NORTH);
        JPanel control = panel(new BorderLayout());
        control.add(input, BorderLayout.NORTH);
        p.add(control, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(100, 49));
        return p;
    }
    private static JTextField input(String value) { return DatabaseInputs.textField(value); }
    private static JTextField placeholder(String value) {
        JTextField field = input("");
        field.putClientProperty("JTextField.placeholderText", value);
        return field;
    }
    private static JComboBox<String> type() {
        return DatabaseInputs.comboBox(new String[]{"VARCHAR", "TEXT", "BIGINT", "DECIMAL", "TIMESTAMP"});
    }
    private static JComponent pair(JComponent left, JComponent right, int rightWidth) {
        JPanel p = panel(new BorderLayout(16, 0));
        right.setPreferredSize(new Dimension(rightWidth, right.getPreferredSize().height));
        p.add(left, BorderLayout.CENTER); p.add(right, BorderLayout.EAST);
        return p;
    }
    private static JCheckBox nullable() {
        JCheckBox check = new JCheckBox("Allow NULL values", false);
        check.setOpaque(false); check.setBorder(pad(0, 0, 0, 0));
        return check;
    }

    private static JCheckBox check(String title, boolean selected) {
        JCheckBox check = new JCheckBox(title, selected);
        check.setOpaque(false); check.setBorder(pad(0, 0, 0, 0)); return check;
    }
    private static JPanel header() {
        JPanel p = panel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, line), pad(20, 24, 18, 24)));
        JPanel title = vertical();
        JLabel heading = label("Create table", 22, true, ink); heading.setIcon(Icons.TABLE); heading.setIconTextGap(10);
        stack(title, heading); gap(title, 8); stack(title, note("Local development   ›   shop"));
        p.add(title, BorderLayout.WEST);
        JLabel source = note("MySQL"); source.setIcon(Icons.dataSource(DatabaseType.MYSQL, 24)); source.setIconTextGap(9);
        p.add(source, BorderLayout.EAST); return p;
    }
    private static JPanel footer() {
        JPanel footer = panel(new BorderLayout(20, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, line), pad(14, 24, 14, 24)));
        JPanel status = vertical(); stack(status, label("6 columns · 1 primary key", 12, true, ink));
        gap(status, 5); stack(status, note("Ready to create · shop.customers")); footer.add(status, BorderLayout.CENTER);
        JButton create = quiet("Create table", AllIcons.General.Add);
        DatabaseUi.setActionEmphasis(create, DatabaseUi.POSITIVE_ACTION_COLOR); create.setBorder(pad(7, 14, 7, 14));
        JButton cancel = new JButton("Cancel"); cancel.setPreferredSize(new Dimension(80, 31));
        footer.add(row(10, cancel, create), BorderLayout.EAST); return footer;
    }
    private static JPanel shell(JComponent body) {
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(bg); root.setBorder(BorderFactory.createLineBorder(line));
        root.add(header(), BorderLayout.NORTH); root.add(body, BorderLayout.CENTER); root.add(footer(), BorderLayout.SOUTH); return root;
    }
    private static JPanel identity() {
        JPanel identity = panel(new BorderLayout(24, 0));
        identity.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, line), pad(16, 24, 17, 24)));
        identity.add(field("Table name", input("customers")), BorderLayout.CENTER);
        JPanel counts = row(10, tag("6 columns", accent, selection), tag("1 primary key", muted, tone(0xE8EAED, 0x414348)));
        counts.setBorder(pad(21, 0, 0, 0)); identity.add(counts, BorderLayout.EAST); return identity;
    }
    private static JPanel frame(JComponent workspace) {
        JPanel body = panel(new BorderLayout()); body.add(identity(), BorderLayout.NORTH); body.add(workspace, BorderLayout.CENTER);
        return shell(body);
    }
    private static JPanel ordering() {
        return row(3, quiet("", AllIcons.Actions.MoveUp), quiet("", AllIcons.Actions.MoveDown), quiet("Remove", AllIcons.General.Remove));
    }
    private static JPanel constraints() { return row(20, nullable(), check("Primary key", false), check("Auto increment", false)); }
    private static JPanel definition(String headingText, boolean full) {
        JPanel form = vertical();
        JPanel heading = panel(new BorderLayout());
        heading.add(row(9, label(headingText, 18, true, ink), tag("Column 03", accent, selection)), BorderLayout.WEST);
        heading.add(ordering(), BorderLayout.EAST); stack(form, heading); gap(form, 10);
        stack(form, section("Definition")); stack(form, field("Column name", input("email"))); gap(form, 12);
        stack(form, pair(field("Data type", type()), field("Length", input("255")), 155)); gap(form, 8);
        stack(form, section("Values & constraints")); stack(form, constraints()); gap(form, 14);
        stack(form, field("Default value", placeholder("No default")));
        if (full) { gap(form, 8); stack(form, note("Blank means no default · TEXT: literal · SQL: expression")); }
        return form;
    }
    private static JPanel sql(int height, boolean compact) {
        JPanel box = new RoundedPanel(new BorderLayout(0, 10), tone(0xF5F6F8, 0x303236), line); box.setBorder(pad(12, 15, 12, 15));
        JPanel title = panel(new BorderLayout());
        JLabel caption = label("SQL preview", 12, true, muted); caption.setIcon(Icons.CONSOLE); caption.setIconTextGap(7);
        title.add(caption, BorderLayout.WEST); title.add(note("MySQL"), BorderLayout.EAST); box.add(title, BorderLayout.NORTH);
        JTextPane code = new JTextPane(); code.setEditable(false); code.setOpaque(false); code.setBorder(pad(0, 0, 0, 0));
        code.setFont(new Font("DejaVu Sans Mono", Font.PLAIN, compact ? 11 : 12)); code.setForeground(ink); code.setText(SQL);
        StyledDocument doc = code.getStyledDocument();
        SimpleAttributeSet normal = new SimpleAttributeSet(); StyleConstants.setForeground(normal, ink); doc.setCharacterAttributes(0, SQL.length(), normal, true);
        var tokens = java.util.regex.Pattern.compile("`[^`]*`|'[^']*'|\\b(?:CREATE|TABLE|NOT|NULL|AUTO_INCREMENT|DEFAULT|PRIMARY|KEY|CURRENT_TIMESTAMP)\\b").matcher(SQL);
        SimpleAttributeSet keyword = new SimpleAttributeSet(); StyleConstants.setForeground(keyword, accent);
        while (tokens.find()) if (Character.isLetter(tokens.group().charAt(0))) doc.setCharacterAttributes(tokens.start(), tokens.end()-tokens.start(), keyword, false);
        var types = java.util.regex.Pattern.compile("\\b(?:BIGINT|VARCHAR|DECIMAL|TIMESTAMP)\\b").matcher(SQL);
        SimpleAttributeSet type = new SimpleAttributeSet(); StyleConstants.setForeground(type, tone(0x976215, 0xD5B778));
        while (types.find()) doc.setCharacterAttributes(types.start(), types.end()-types.start(), type, false);
        JScrollPane scroll = new JScrollPane(code); scroll.setBorder(pad(0, 0, 0, 0)); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        box.add(scroll, BorderLayout.CENTER); box.setPreferredSize(new Dimension(100, height)); return box;
    }

    /** 01: a direct companion to the selected alter-table Column Inspector. */
    private static JComponent composer() {
        JPanel body = panel(new BorderLayout());
        JPanel sidebar = panel(new BorderLayout()); sidebar.setPreferredSize(new Dimension(246, 100));
        sidebar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, line), pad(18, 14, 17, 14)));
        JPanel nav = vertical(); JPanel title = panel(new BorderLayout());
        title.add(label("Columns", 13, true, ink), BorderLayout.WEST); title.add(quiet("", AllIcons.General.Add), BorderLayout.EAST);
        stack(nav, title); gap(nav, 12); JTextField search = placeholder("Filter columns"); search.putClientProperty("JTextField.leadingIcon", AllIcons.Actions.Search);
        stack(nav, search); gap(nav, 12);
        for (int i = 0; i < COLUMNS.length; i++) {
            boolean selected = i == 2;
            JPanel item = new RoundedPanel(new BorderLayout(10, 0), selected ? selection : null, selected ? tone(0xA9C4F7, 0x45618D) : null);
            item.setBorder(pad(9, 10, 9, 10)); item.add(new JLabel(i == 0 ? Icons.KEY : Icons.COLUMN), BorderLayout.WEST);
            JPanel captions = vertical(); stack(captions, label(COLUMNS[i][0], 13, selected, selected ? accent : ink)); gap(captions, 4); stack(captions, note(COLUMNS[i][1]));
            item.add(captions, BorderLayout.CENTER); item.add(mono(String.format("%02d", i+1), 10, muted), BorderLayout.EAST);
            stack(nav, item); gap(nav, 4);
        }
        sidebar.add(nav, BorderLayout.NORTH);
        JPanel summary = vertical(); stack(summary, note("1 primary key · 1 nullable column")); gap(summary, 12); stack(summary, rule()); gap(summary, 8);
        stack(summary, link("Add column…")); sidebar.add(summary, BorderLayout.SOUTH); body.add(sidebar, BorderLayout.WEST);
        JPanel detail = panel(new BorderLayout(0, 16)); detail.setBorder(pad(18, 24, 20, 24));
        detail.add(definition("email", true), BorderLayout.NORTH); detail.add(sql(173, true), BorderLayout.SOUTH);
        body.add(detail, BorderLayout.CENTER); return frame(body);
    }

    private static JScrollPane schemaGrid() {
        String[] headers = {"Column", "Data type", "Length", "Nullable", "Key", "Identity", "Default"};
        Object[][] data = {{"id", "BIGINT", "—", "No", "Primary", "Auto", "—"}, {"name", "VARCHAR", "120", "No", "—", "—", "—"},
                {"email", "VARCHAR", "255", "No", "—", "—", "—"}, {"status", "VARCHAR", "20", "No", "—", "—", "'active'"},
                {"balance", "DECIMAL", "10, 2", "Yes", "—", "—", "0"}, {"created_at", "TIMESTAMP", "—", "No", "—", "—", "CURRENT_TIMESTAMP"}};
        DefaultTableModel model = new DefaultTableModel(data, headers) { @Override public boolean isCellEditable(int r, int c) { return false; } };
        DatabaseTable table = new DatabaseTable(model); table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS); table.setRowHeight(38);
        table.setShowVerticalLines(false); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowSelectionInterval(2, 2);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private int currentRow, currentColumn;
            @Override public Color getBackground() { return currentRow == 2 ? selection : surface; }
            @Override public Color getForeground() { return currentRow == 2 && currentColumn < 3 ? accent : currentColumn >= 3 ? muted : ink; }
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r, int c) {
                currentRow = r; currentColumn = c; super.getTableCellRendererComponent(t, value, selected, false, r, c);
                setBorder(pad(0, 12, 0, 10)); setIcon(c == 0 ? r == 0 ? Icons.KEY : Icons.COLUMN : null); setIconTextGap(8);
                setFont(c < 3 || c == 6 ? new Font("DejaVu Sans Mono", r == 2 ? Font.BOLD : Font.PLAIN, 11) : UIManager.getFont("Label.font"));
                return this;
            }
        });
        int[] widths = {180, 145, 80, 85, 95, 95, 240};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        JScrollPane scroll = table.createScrollPane(); scroll.setBorder(BorderFactory.createLineBorder(line));
        scroll.setPreferredSize(new Dimension(100, 261)); return scroll;
    }

    /** 02: a generous schema grid with a compact selected-column editor. */
    private static JComponent canvas() {
        JPanel body = panel(new BorderLayout(0, 18)); body.setBorder(pad(16, 24, 20, 24));
        JPanel upper = panel(new BorderLayout(0, 12)); JPanel toolbar = panel(new BorderLayout());
        toolbar.add(row(10, label("Table structure", 15, true, ink), note("6 columns")), BorderLayout.WEST);
        toolbar.add(row(6, quiet("Add column", AllIcons.General.Add), quiet("Remove", AllIcons.General.Remove),
                quiet("", AllIcons.Actions.MoveUp), quiet("", AllIcons.Actions.MoveDown)), BorderLayout.EAST);
        upper.add(toolbar, BorderLayout.NORTH); upper.add(schemaGrid(), BorderLayout.CENTER); body.add(upper, BorderLayout.NORTH);
        JPanel lower = panel(new GridLayout(1, 2, 18, 0));
        JPanel edit = new RoundedPanel(new BorderLayout(0, 12), surface, line); edit.setBorder(pad(14, 16, 14, 16));
        edit.add(row(9, label("email", 15, true, ink), note("Column 03")), BorderLayout.NORTH);
        JPanel form = vertical(); JPanel fields = panel(new GridLayout(1, 3, 12, 0));
        fields.add(field("Column name", input("email"))); fields.add(field("Data type", type())); fields.add(field("Length", input("255")));
        stack(form, fields); gap(form, 14); stack(form, field("Default value", placeholder("No default"))); gap(form, 14); stack(form, constraints());
        edit.add(form, BorderLayout.CENTER); lower.add(edit); lower.add(sql(220, true)); body.add(lower, BorderLayout.CENTER); return frame(body);
    }

    private static JPanel outlineCard(int index, boolean expanded) {
        JPanel card = new RoundedPanel(new BorderLayout(0, 13), expanded ? tone(0xF7F9FE, 0x303640) : surface, expanded ? tone(0xA9C4F7, 0x45618D) : line);
        card.setBorder(pad(expanded ? 13 : 11, 15, expanded ? 15 : 11, 15));
        JPanel head = panel(new BorderLayout());
        JLabel name = label(COLUMNS[index][0], 13, expanded, expanded ? accent : ink); name.setIcon(index == 0 ? Icons.KEY : Icons.COLUMN); name.setIconTextGap(10);
        head.add(row(8, mono(String.format("%02d", index+1), 10, muted), name), BorderLayout.WEST);
        head.add(expanded ? ordering() : row(14, mono(COLUMNS[index][1], 11, muted), note(index == 0 ? "Primary key" : index == 4 ? "Nullable" : "Required")), BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);
        if (expanded) {
            JPanel fields = vertical(); JPanel first = panel(new GridLayout(1, 3, 12, 0));
            first.add(field("Column name", input("email"))); first.add(field("Data type", type())); first.add(field("Length", input("255")));
            stack(fields, first); gap(fields, 12); stack(fields, field("Default value", placeholder("No default"))); gap(fields, 13); stack(fields, constraints());
            card.add(fields, BorderLayout.CENTER);
        }
        return card;
    }

    /** 03: an outline of expandable column cards beside a persistent DDL sheet. */
    private static JComponent blueprint() {
        JPanel body = panel(new BorderLayout());
        JPanel builder = panel(new BorderLayout(0, 14)); builder.setBorder(pad(18, 24, 20, 20));
        JPanel top = vertical(); stack(top, field("Table name", input("customers"))); gap(top, 14);
        JPanel title = panel(new BorderLayout()); title.add(row(9, label("Columns", 15, true, ink), note("6 columns")), BorderLayout.WEST);
        title.add(quiet("Add column", AllIcons.General.Add), BorderLayout.EAST); stack(top, title); builder.add(top, BorderLayout.NORTH);
        JPanel cards = vertical();
        for (int i = 0; i < COLUMNS.length; i++) { stack(cards, outlineCard(i, i == 2)); if (i < COLUMNS.length-1) gap(cards, 8); }
        builder.add(cards, BorderLayout.CENTER); body.add(builder, BorderLayout.CENTER);
        JPanel review = panel(new BorderLayout(0, 16)); review.setPreferredSize(new Dimension(386, 100));
        review.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, line), pad(22, 20, 20, 24)));
        JPanel topReview = vertical(); stack(topReview, label("Definition preview", 16, true, ink)); gap(topReview, 8);
        stack(topReview, note("shop.customers")); gap(topReview, 20);
        JPanel facts = new RoundedPanel(new GridLayout(1, 3, 10, 0), surface, line); facts.setBorder(pad(14, 14, 14, 14));
        for (String[] metric : new String[][]{{"6", "Columns"}, {"1", "Primary key"}, {"1", "Nullable"}}) {
            JPanel metricPanel = vertical(); stack(metricPanel, label(metric[0], 22, true, ink)); gap(metricPanel, 5); stack(metricPanel, label(metric[1], 10, false, muted)); facts.add(metricPanel);
        }
        stack(topReview, facts); review.add(topReview, BorderLayout.NORTH);
        // Wrap only this narrow sheet, keeping the full statement available without horizontal scrolling.
        JPanel sheet = sql(350, true); review.add(sheet, BorderLayout.CENTER);
        JPanel bottom = vertical(); stack(bottom, note("Primary key")); gap(bottom, 6);
        JLabel key = text("id · Auto increment"); key.setIcon(Icons.KEY); key.setIconTextGap(8); stack(bottom, key); review.add(bottom, BorderLayout.SOUTH);
        body.add(review, BorderLayout.EAST); return shell(body);
    }

    private static JComponent baseline() throws Exception {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "Local development"); config.setDatabaseName("shop");
        CreateTableDialog dialog = new CreateTableDialog(null, config, "shop");
        List<ColumnDefinition> columns = new java.util.ArrayList<>();
        columns.add(new ColumnDefinition("id", "BIGINT", 0, false, true, true, ""));
        columns.add(new ColumnDefinition("name", "VARCHAR", 120, false, false, false, ""));
        columns.add(new ColumnDefinition("email", "VARCHAR", 255, false, false, false, ""));
        columns.add(new ColumnDefinition("status", "VARCHAR", 20, false, false, false, "active"));
        ColumnDefinition balance = new ColumnDefinition("balance", "DECIMAL", 10, true, false, false, "0"); balance.setDecimalDigits(2); columns.add(balance);
        columns.add(new ColumnDefinition("created_at", "TIMESTAMP", 0, false, false, false, "SQL:CURRENT_TIMESTAMP"));
        var field = CreateTableDialog.class.getDeclaredField("editor"); field.setAccessible(true);
        var editor = (com.segfault03.ideadb.dialog.CreateTablePanel)field.get(dialog);
        editor.loadDefinition(new TableCreation("customers", columns.stream().map(c -> new TableCreation.Column(c.getName(),c.getType(),c.getSize(),Math.max(0,c.getDecimalDigits()),c.isNullable(),c.isPrimaryKey(),c.isAutoIncrement(),c.getDefaultValue())).toList()));
        editor.columnList().setSelectedIndex(2); return dialog.previewPanel();
    }
    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void render(JComponent panel, Path target, int width, int height) throws Exception {
        panel.setSize(width, height);
        for (int pass = 0; pass < 5; pass++) layout(panel);
        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        panel.printAll(g); g.dispose();
        ImageIO.write(image, "png", target.toFile());
        System.out.println(target.getFileName() + " · " + image.getWidth() + " × " + image.getHeight());
    }
    private static void overview(Path output, String theme) throws Exception {
        String[] files = {"01-column-composer", "02-schema-canvas", "03-blueprint"};
        String[] titles = {"01 · Column Composer", "02 · Schema Canvas", "03 · Blueprint"};
        BufferedImage sheet = new BufferedImage(WIDTH * 3 + 96, HEIGHT + 96, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(bg); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("DejaVu Sans", Font.BOLD, 23));
        for (int i = 0; i < files.length; i++) {
            int x = 24 + i * (WIDTH + 24);
            g.setColor(ink); g.drawString(titles[i], x, 44);
            BufferedImage shot = ImageIO.read(output.resolve(files[i] + "-" + theme + ".png").toFile());
            g.drawImage(shot, x, 70, WIDTH, HEIGHT, null);
        }
        g.dispose(); ImageIO.write(sheet, "png", output.resolve("overview-" + theme + ".png").toFile());
    }
    public static void main(String[] args) throws Exception {
        try {
            com.intellij.testFramework.common.TestApplicationKt.loadApp();
            dark = args[1].equals("dark");
            UIManager.setLookAndFeel(dark ? new FlatDarculaLaf() : new FlatIntelliJLaf());
            Font font = new javax.swing.plaf.FontUIResource("DejaVu Sans", Font.PLAIN, 13);
            UIManager.put("defaultFont", font);
            for (String key : List.of("Label.font", "Button.font", "CheckBox.font", "ComboBox.font",
                    "TextField.font", "TextArea.font", "TextPane.font", "Table.font", "TableHeader.font"))
                UIManager.put(key, font);
            JBColor.setDark(dark);
            com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
            IconLoader.activate(); IconLoader.setUseDarkIcons(dark);
            bg = UIManager.getColor("Panel.background");
            surface = UIManager.getColor("Table.background");
            ink = UIManager.getColor("Label.foreground");
            muted = tone(0x6B7078, 0xA8ADB5);
            line = tone(0xD8DBE0, 0x505358);
            accent = tone(0x3574F0, 0x82ABFF);
            selection = tone(0xE8F0FF, 0x334760);
            Path output = Path.of(args[0]); Files.createDirectories(output);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    render(composer(), output.resolve("01-column-composer-"+args[1]+".png"), WIDTH, HEIGHT);
                    render(canvas(), output.resolve("02-schema-canvas-"+args[1]+".png"), WIDTH, HEIGHT);
                    render(blueprint(), output.resolve("03-blueprint-"+args[1]+".png"), WIDTH, HEIGHT);
                    render(baseline(), output.resolve("00-current-dialog-"+args[1]+".png"), 900, 680);
                    overview(output, args[1]);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
