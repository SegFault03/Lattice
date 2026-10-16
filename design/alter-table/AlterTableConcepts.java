import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.segfault03.ideadb.dialog.AlterTableDialog;
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
 * Three isolated, presentation-only Swing concepts. Every value, change marker,
 * before/after comparison and SQL statement is a fixed visual fixture.
 * No database services, action listeners, SQL generation or mutation callbacks.
 * Shared production inputs, toolbar actions, grids, section rules and SVG icons
 * keep these explorations in the plugin's visual language.
 */
public final class AlterTableConcepts {
    private static boolean dark;
    private static Color bg, surface, line, muted, accent, selection, ink;
    private static final int WIDTH = 1000, HEIGHT = 720;
    private static final String SQL = "ALTER TABLE `shop`.`customers`\n  MODIFY COLUMN `email` VARCHAR(255) NULL;";

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
        JCheckBox check = new JCheckBox("Allow NULL values", true);
        check.setOpaque(false); check.setBorder(pad(0, 0, 0, 0));
        return check;
    }

    private static JPanel header() {
        JPanel p = panel(new BorderLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, line), pad(21, 24, 19, 24)));
        JPanel left = vertical();
        stack(left, label("Edit table", 21, true, ink));
        gap(left, 9);
        JLabel path = note("Local development   ›   shop   ›   customers");
        path.setIcon(Icons.TABLE); path.setIconTextGap(8);
        stack(left, path);
        p.add(left, BorderLayout.WEST);
        JLabel source = label("MySQL", 12, false, muted);
        source.setIcon(Icons.dataSource(DatabaseType.MYSQL, 24));
        source.setIconTextGap(9);
        p.add(source, BorderLayout.EAST);
        return p;
    }
    private static JPanel footer(String action) {
        JPanel p = panel(new BorderLayout(20, 0));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, line), pad(15, 24, 15, 24)));
        JPanel status = row(8, tag("1", accent, selection), text("column changed"), note("·  email"));
        p.add(status, BorderLayout.WEST);
        JButton primary = quiet(action, AllIcons.Actions.Checked);
        DatabaseUi.setActionEmphasis(primary, DatabaseUi.POSITIVE_ACTION_COLOR);
        primary.setBorder(pad(6, 13, 6, 13));
        JButton cancel = new JButton("Close");
        cancel.setPreferredSize(new Dimension(80, 30));
        p.add(row(12, cancel, primary), BorderLayout.EAST);
        return p;
    }
    private static JPanel shell(JComponent content, String action) {
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(bg);
        root.setBorder(BorderFactory.createLineBorder(line));
        root.add(header(), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        root.add(footer(action), BorderLayout.SOUTH);
        return root;
    }
    private static JComponent sql(boolean compact) {
        JPanel box = new RoundedPanel(new BorderLayout(0, 9), tone(0xF5F6F8, 0x303236), line);
        box.setBorder(pad(12, 15, 12, 15));
        JPanel title = panel(new BorderLayout());
        JLabel caption = label("SQL preview", 12, true, muted);
        caption.setIcon(Icons.CONSOLE); caption.setIconTextGap(7);
        title.add(caption, BorderLayout.WEST);
        title.add(note("MySQL"), BorderLayout.EAST);
        box.add(title, BorderLayout.NORTH);
        JTextPane code = new JTextPane(); code.setEditable(false); code.setOpaque(false);
        code.setBorder(pad(0, 0, 0, 0));
        code.setFont(new Font("DejaVu Sans Mono", Font.PLAIN, compact ? 12 : 13));
        code.setForeground(ink); code.setText(SQL);
        StyledDocument doc = code.getStyledDocument();
        SimpleAttributeSet key = new SimpleAttributeSet();
        StyleConstants.setForeground(key, accent);
        for (String keyword : List.of("ALTER TABLE", "MODIFY COLUMN", "NULL")) {
            int at = SQL.indexOf(keyword); doc.setCharacterAttributes(at, keyword.length(), key, false);
        }
        SimpleAttributeSet type = new SimpleAttributeSet();
        StyleConstants.setForeground(type, tone(0x9B6813, 0xD5B778));
        doc.setCharacterAttributes(SQL.indexOf("VARCHAR"), 12, type, false);
        box.add(code, BorderLayout.CENTER);
        box.setPreferredSize(new Dimension(100, compact ? 100 : 112));
        return box;
    }

    /** Concept 01: object navigation and a spacious property inspector. */
    private static JComponent inspector() {
        JPanel body = panel(new BorderLayout());
        JPanel sidebar = panel(new BorderLayout());
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, line), pad(18, 14, 16, 14)));
        sidebar.setPreferredSize(new Dimension(246, 100));
        JPanel nav = vertical();
        JPanel top = panel(new BorderLayout());
        top.add(row(7, label("Columns", 13, true, ink), tag("5", muted, tone(0xE8EAED, 0x45474B))), BorderLayout.WEST);
        JButton add = quiet("", AllIcons.General.Add); add.setToolTipText("Add column");
        top.add(add, BorderLayout.EAST);
        stack(nav, top); gap(nav, 14);
        JTextField search = placeholder("Filter columns");
        search.putClientProperty("JTextField.leadingIcon", AllIcons.Actions.Search);
        stack(nav, search); gap(nav, 13);
        String[][] columns = {{"id", "BIGINT · Primary key"}, {"name", "VARCHAR(120)"},
                {"email", "VARCHAR(255)"}, {"status", "VARCHAR(20)"}, {"balance", "DECIMAL(10,2)"}};
        for (int i = 0; i < columns.length; i++) {
            boolean selected = i == 2;
            JPanel item = new RoundedPanel(new BorderLayout(10, 0), selected ? selection : null,
                    selected ? tone(0xA9C4F7, 0x45618D) : null);
            item.setBorder(pad(11, 10, 11, 10));
            item.add(new JLabel(i == 0 ? Icons.KEY : i == 3 ? Icons.FOREIGN_KEY : Icons.COLUMN), BorderLayout.WEST);
            JPanel labels = vertical();
            stack(labels, label(columns[i][0], 13, selected, selected ? accent : ink));
            gap(labels, 4); stack(labels, note(columns[i][1]));
            item.add(labels, BorderLayout.CENTER);
            if (selected) item.add(label("●", 9, false, accent), BorderLayout.EAST);
            stack(nav, item); gap(nav, 5);
        }
        sidebar.add(nav, BorderLayout.NORTH);
        JPanel end = vertical();
        stack(end, note("5 columns · 1 primary key")); gap(end, 12); stack(end, rule()); gap(end, 9);
        stack(end, link("Rename table…"));
        sidebar.add(end, BorderLayout.SOUTH);
        body.add(sidebar, BorderLayout.WEST);

        JPanel detail = panel(new BorderLayout(0, 16)); detail.setBorder(pad(19, 24, 20, 24));
        JPanel form = vertical();
        JPanel title = panel(new BorderLayout());
        title.add(row(9, label("email", 18, true, ink), tag("Modified", accent, selection)), BorderLayout.WEST);
        title.add(row(4, quiet("Rename", AllIcons.Actions.Edit), quiet("Drop…", AllIcons.General.Remove)), BorderLayout.EAST);
        stack(form, title); gap(form, 7);
        stack(form, section("Definition"));
        stack(form, field("Column name", input("email"))); gap(form, 12);
        stack(form, pair(field("Data type", type()), field("Length", input("255")), 155));
        gap(form, 7); stack(form, note("Length changed from 180 to 255 characters.")); gap(form, 7);
        stack(form, section("Values"));
        stack(form, nullable()); gap(form, 14);
        stack(form, field("Default value", placeholder("No default")));
        detail.add(form, BorderLayout.NORTH);
        detail.add(sql(false), BorderLayout.SOUTH);
        body.add(detail, BorderLayout.CENTER);
        return shell(body, "Apply changes");
    }

    /** Concept 02: the production database grid becomes the main schema surface. */
    private static JComponent grid() {
        JPanel body = panel(new BorderLayout(0, 15)); body.setBorder(pad(18, 22, 20, 22));
        JPanel upper = panel(new BorderLayout(0, 10));
        JPanel toolbar = panel(new BorderLayout());
        toolbar.add(row(9, label("Columns", 14, true, ink), note("5 total")), BorderLayout.WEST);
        toolbar.add(row(9, quiet("Add column", AllIcons.General.Add), quiet("Rename", AllIcons.Actions.Edit),
                quiet("Drop…", AllIcons.General.Remove), link("Rename table…")), BorderLayout.EAST);
        upper.add(toolbar, BorderLayout.NORTH);
        String[] headers = {"Column", "Type", "Length", "Nullable", "Default", "Change"};
        Object[][] data = {{"id", "BIGINT", "—", "No", "Auto", "—"},
                {"name", "VARCHAR", "120", "No", "—", "—"},
                {"email", "VARCHAR", "255", "Yes", "—", "Modified"},
                {"status", "VARCHAR", "20", "No", "'active'", "—"},
                {"balance", "DECIMAL", "10, 2", "No", "0", "—"}};
        DefaultTableModel model = new DefaultTableModel(data, headers) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        DatabaseTable table = new DatabaseTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setRowHeight(36); table.setShowVerticalLines(false);
        table.setSelectionBackground(selection); table.setSelectionForeground(ink);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowSelectionInterval(2, 2);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private int fixtureRow = -1, fixtureColumn;
            // The IDE normally mutes selection when its window is inactive.
            // Keep this fixed example legible during offscreen painting.
            @Override public Color getBackground() { return fixtureRow == 2 ? selection : surface; }
            @Override public Color getForeground() {
                return fixtureRow == 2 && (fixtureColumn == 0 || fixtureColumn == 2 || fixtureColumn == 5)
                        ? accent : fixtureColumn == 5 ? muted : ink;
            }
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                                      boolean focus, int r, int c) {
                fixtureRow = r; fixtureColumn = c;
                super.getTableCellRendererComponent(t, value, selected, false, r, c);
                setBorder(pad(0, 12, 0, 10)); setIcon(null); setIconTextGap(8);
                setFont(c < 3 ? new Font("DejaVu Sans Mono", r == 2 && (c == 0 || c == 2) ? Font.BOLD : Font.PLAIN, 12)
                        : UIManager.getFont("Label.font"));
                if (c == 0) setIcon(r == 0 ? Icons.KEY : r == 3 ? Icons.FOREIGN_KEY : Icons.COLUMN);
                return this;
            }
        });
        int[] widths = {235, 160, 110, 110, 150, 140};
        for (int c = 0; c < widths.length; c++) table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        JScrollPane scroll = table.createScrollPane();
        scroll.setBorder(BorderFactory.createLineBorder(line));
        scroll.setPreferredSize(new Dimension(100, 214));
        upper.add(scroll, BorderLayout.CENTER);
        body.add(upper, BorderLayout.NORTH);

        JPanel lower = panel(new BorderLayout(0, 15));
        JPanel editor = new RoundedPanel(new BorderLayout(0, 12), surface, line);
        editor.setBorder(pad(15, 17, 15, 17));
        JPanel title = panel(new BorderLayout());
        title.add(row(8, label("email", 14, true, ink), note("Column properties")), BorderLayout.WEST);
        title.add(label("Length  180 → 255", 12, false, accent), BorderLayout.EAST);
        editor.add(title, BorderLayout.NORTH);
        JPanel fields = panel(new GridLayout(1, 4, 16, 0));
        fields.add(field("Column name", input("email")));
        fields.add(field("Data type", type()));
        fields.add(field("Length", input("255")));
        fields.add(field("Default value", placeholder("No default")));
        editor.add(fields, BorderLayout.CENTER);
        JPanel constraints = panel(new BorderLayout());
        constraints.add(nullable(), BorderLayout.WEST);
        constraints.add(note("VARCHAR(255) · NULL allowed · No default"), BorderLayout.EAST);
        editor.add(constraints, BorderLayout.SOUTH);
        lower.add(editor, BorderLayout.CENTER);
        lower.add(sql(true), BorderLayout.SOUTH);
        body.add(lower, BorderLayout.CENTER);
        return shell(body, "Apply changes");
    }

    /** Concept 03: a task rail mirrors the connection dialog's source picker. */
    private static JComponent studio() {
        JPanel body = panel(new BorderLayout());
        JPanel rail = panel(new BorderLayout());
        rail.setPreferredSize(new Dimension(228, 100));
        rail.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, line), pad(21, 16, 18, 16)));
        JPanel items = vertical();
        stack(items, label("Operation", 13, true, ink)); gap(items, 16);
        String[][] tasks = {{"Add column", "Extend the schema"}, {"Modify column", "Type, length and values"},
                {"Rename column", "Change a column name"}, {"Drop column", "Remove a column"},
                {"Rename table", "Change the table name"}};
        Icon[] icons = {AllIcons.General.Add, AllIcons.Actions.Edit, Icons.COLUMN,
                AllIcons.General.Remove, Icons.TABLE};
        for (int i = 0; i < tasks.length; i++) {
            boolean selected = i == 1;
            JPanel item = new RoundedPanel(new BorderLayout(10, 0), selected ? selection : null,
                    selected ? tone(0xA9C4F7, 0x45618D) : null);
            item.setBorder(pad(14, 11, 14, 9));
            item.add(new JLabel(icons[i]), BorderLayout.WEST);
            JPanel labels = vertical();
            stack(labels, label(tasks[i][0], 13, selected, selected ? accent : ink));
            gap(labels, 5); stack(labels, label(tasks[i][1], 11, false, muted));
            item.add(labels, BorderLayout.CENTER);
            stack(items, item); gap(items, 7);
        }
        rail.add(items, BorderLayout.NORTH);
        rail.add(note("5 columns · shop.customers"), BorderLayout.SOUTH);
        body.add(rail, BorderLayout.WEST);

        JPanel workspace = panel(new BorderLayout(0, 17)); workspace.setBorder(pad(22, 24, 20, 24));
        JPanel contents = vertical();
        stack(contents, label("Modify column", 18, true, ink)); gap(contents, 7);
        stack(contents, note("Adjust the type and values of an existing column.")); gap(contents, 22);
        JComboBox<String> column = DatabaseInputs.comboBox(new String[]{"email", "id", "name", "status", "balance"});
        stack(contents, field("Column", column)); gap(contents, 12);
        JPanel columns = panel(new BorderLayout(22, 0));
        JPanel properties = vertical();
        stack(properties, section("Definition"));
        stack(properties, pair(field("Data type", type()), field("Length", input("255")), 85)); gap(properties, 12);
        stack(properties, nullable()); gap(properties, 15);
        stack(properties, field("Default value", placeholder("No default")));
        columns.add(properties, BorderLayout.CENTER);
        JPanel change = new RoundedPanel(new BorderLayout(), surface, line);
        change.setPreferredSize(new Dimension(238, 226)); change.setBorder(pad(15, 16, 15, 16));
        JPanel comparison = vertical();
        stack(comparison, label("Change preview", 12, true, ink)); gap(comparison, 18);
        stack(comparison, note("CURRENT")); gap(comparison, 8);
        stack(comparison, mono("email  VARCHAR(180)", 12, muted)); gap(comparison, 5);
        stack(comparison, note("NULL allowed · No default")); gap(comparison, 16);
        stack(comparison, rule()); gap(comparison, 14);
        stack(comparison, label("PROPOSED", 11, true, accent)); gap(comparison, 8);
        stack(comparison, mono("email  VARCHAR(255)", 12, ink)); gap(comparison, 5);
        stack(comparison, note("NULL allowed · No default"));
        change.add(comparison, BorderLayout.NORTH);
        columns.add(change, BorderLayout.EAST);
        stack(contents, columns);
        workspace.add(contents, BorderLayout.NORTH);
        workspace.add(sql(false), BorderLayout.SOUTH);
        body.add(workspace, BorderLayout.CENTER);
        return shell(body, "Apply column changes");
    }

    private static JComponent baseline() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "Local development");
        config.setDatabaseName("shop");
        TableMetadata table = new TableMetadata("shop", null, "customers", "TABLE");
        table.addColumn(new ColumnMetadata("id", "BIGINT", java.sql.Types.BIGINT, 0, 0, false, true, true, null));
        table.addColumn(new ColumnMetadata("name", "VARCHAR", java.sql.Types.VARCHAR, 120, 0, false, false, false, null));
        table.addColumn(new ColumnMetadata("email", "VARCHAR", java.sql.Types.VARCHAR, 180, 0, true, false, false, null));
        table.addColumn(new ColumnMetadata("status", "VARCHAR", java.sql.Types.VARCHAR, 20, 0, false, false, false, "'active'"));
        table.addColumn(new ColumnMetadata("balance", "DECIMAL", java.sql.Types.DECIMAL, 10, 2, false, false, false, "0"));
        return new AlterTableDialog(null, config, "shop", table).previewPanel();
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
        String[] files = {"01-column-inspector", "02-structure-grid", "03-operation-studio"};
        String[] titles = {"01 · Column Inspector", "02 · Structure Grid", "03 · Operation Studio"};
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
                    render(inspector(), output.resolve("01-column-inspector-"+args[1]+".png"), WIDTH, HEIGHT);
                    render(grid(), output.resolve("02-structure-grid-"+args[1]+".png"), WIDTH, HEIGHT);
                    render(studio(), output.resolve("03-operation-studio-"+args[1]+".png"), WIDTH, HEIGHT);
                    render(baseline(), output.resolve("00-current-dialog-"+args[1]+".png"), 800, 540);
                    overview(output, args[1]);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
