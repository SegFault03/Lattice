import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.ui.DatabaseUi;
import com.segfault03.ideadb.ui.Icons;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

/**
 * Presentation-only concepts for the welcome editor tab as a home screen. No listeners, no services, no database
 * access: every control shows fixed values from one synthetic fixture. See design/README.md.
 */
public final class WelcomeConcepts {
    private static final int WIDTH = 900, HEIGHT = 640, NARROW = 520;
    private static boolean dark;

    // ---- shared fixture (synthetic) -------------------------------------------------------------------------------
    private record Connection(String name, DatabaseType type, String where, Status status, String tag) {}
    private enum Status { CONNECTED, CACHED, UNREACHABLE }
    private record Recent(Icon icon, String title, String connection, String when) {}

    private static final List<Connection> CONNECTIONS = List.of(
            new Connection("Local development", DatabaseType.HSQLDB, "In-memory · lattice_dev", Status.CONNECTED, "local"),
            new Connection("Orders fixture", DatabaseType.H2, "File · ~/fixtures/orders.mv.db", Status.CACHED, "local"),
            new Connection("Analytics server", DatabaseType.MYSQL, "analytics.example.internal:3306", Status.UNREACHABLE, "development"),
            new Connection("Staging", DatabaseType.POSTGRESQL, "staging-db.example.internal:5432", Status.CONNECTED, "staging"));
    private static List<Recent> recents() {
        return List.of(
                new Recent(Icons.TABLE, "PUBLIC.CUSTOMERS", "Local development", "2 min ago"),
                new Recent(Icons.TABLE, "ORDERS", "Orders fixture", "Yesterday"),
                new Recent(Icons.CONSOLE, "Console", "Analytics server", "3 days ago"),
                new Recent(Icons.SQL_FILE, "seed.sql", "Local development", "Last week"));
    }

    // ---- shared parts --------------------------------------------------------------------------------------------
    private static JBLabel text(String value) { return new JBLabel(value); }
    private static JBLabel muted(String value) { JBLabel label = new JBLabel(value); label.setForeground(DatabaseUi.SECONDARY_TEXT); return label; }
    private static JBLabel heading(String value) { JBLabel label = new JBLabel(value); label.setFont(JBFont.label().asBold()); return label; }

    private static JPanel vertical(int gap) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty());
        panel.putClientProperty("gap", gap);
        return panel;
    }
    private static void add(JPanel column, JComponent child) {
        if (column.getComponentCount() > 0) column.add(Box.createVerticalStrut(JBUI.scale((Integer) column.getClientProperty("gap"))));
        child.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(child);
    }

    /** Two-line row: icon, title, muted subline, optional trailing component. */
    private static JPanel row(Icon icon, String title, String subline, JComponent trailing) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        row.setOpaque(false);
        row.setBorder(JBUI.Borders.empty(6, 8));
        row.add(new JBLabel(icon), BorderLayout.WEST);
        JPanel lines = vertical(2);
        add(lines, text(title));
        if (subline != null) add(lines, muted(subline));
        row.add(lines, BorderLayout.CENTER);
        if (trailing != null) row.add(trailing, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static Color statusColor(Status status) {
        return switch (status) {
            case CONNECTED -> new JBColor(0x2E7D32, 0x57A64A);
            case CACHED -> new JBColor(0x8A6D00, 0xD9A343);
            case UNREACHABLE -> new JBColor(0xC62828, 0xE06C75);
        };
    }
    private static String statusText(Status status) {
        return switch (status) { case CONNECTED -> "Connected"; case CACHED -> "Cached"; case UNREACHABLE -> "Unreachable"; };
    }
    /** A status dot plus its word, so state never rests on color alone. */
    private static JComponent status(Status status) {
        JBLabel label = new JBLabel(statusText(status), new Icon() {
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(statusColor(status)); int size = JBUI.scale(8);
                g.fillOval(x, y + (getIconHeight() - size) / 2, size, size); g.dispose();
            }
            @Override public int getIconWidth() { return JBUI.scale(8); }
            @Override public int getIconHeight() { return JBUI.scale(14); }
        }, SwingConstants.LEFT);
        label.setForeground(DatabaseUi.SECONDARY_TEXT);
        return label;
    }
    /** Environment tag: tinted pill with text in the tag's own hue (never gray on a colored surface). */
    private static JComponent tag(String value) {
        Color hue = switch (value) {
            case "staging" -> new JBColor(0x9A5B00, 0xE5A94F);
            case "development" -> new JBColor(0x2F5FB3, 0x7FA7F0);
            default -> new JBColor(0x4E6B3A, 0x9CC48A);
        };
        JBLabel label = new JBLabel(value) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(hue.getRed(), hue.getGreen(), hue.getBlue(), 38));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight()); g.dispose();
                super.paintComponent(graphics);
            }
        };
        label.setForeground(hue);
        label.setFont(JBFont.small());
        label.setBorder(JBUI.Borders.empty(1, 8));
        return label;
    }
    private static JComponent divider() {
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(1)));
        return separator;
    }
    private static JPanel page(JComponent body) {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(JBColor.namedColor("Panel.background", JBColor.background()));
        page.add(body, BorderLayout.CENTER);
        return page;
    }
    private static JComponent brandLine() {
        JBLabel brand = new JBLabel("Lattice", Icons.LATTICE, SwingConstants.LEFT);
        brand.setFont(JBFont.h4().asBold());
        brand.setIconTextGap(JBUI.scale(8));
        return brand;
    }
    private static JComponent sourcesList() {
        JPanel list = vertical(0);
        for (DatabaseType type : List.of(DatabaseType.MYSQL, DatabaseType.POSTGRESQL, DatabaseType.H2, DatabaseType.HSQLDB))
            add(list, row(Icons.dataSource(type, 20), type.getDisplayName(), switch (type) {
                case MYSQL -> "Server · port 3306"; case POSTGRESQL -> "Server · port 5432";
                case H2 -> "Memory, file or server"; default -> "Memory, file or server"; }, new ActionLink("Connect")));
        return list;
    }

    // ---- 01 Launchpad: Start and Recent side by side ----------------------------------------------------------------
    private static JComponent launchpad(boolean firstRun, int width) {
        JPanel body = new JPanel(new BorderLayout(0, JBUI.scale(20)));
        body.setOpaque(false);
        body.setBorder(JBUI.Borders.empty(28, width < 600 ? 16 : 40));
        body.add(brandLine(), BorderLayout.NORTH);
        JPanel start = vertical(2);
        add(start, heading("Start"));
        add(start, Box.createVerticalStrut(JBUI.scale(4)) instanceof JComponent strut ? strut : new JPanel());
        add(start, row(AllIcons.General.Add, "New connection…", "MySQL, PostgreSQL, H2, HSQLDB", null));
        add(start, row(Icons.CONSOLE, "Open SQL console", firstRun ? "Needs a connection" : "Local development", null));
        add(start, row(Icons.SQL_FILE, "Run SQL script…", "Check offline, then run", null));
        add(start, row(AllIcons.Actions.IntentionBulb, "What's new in 0.3", "Sort, copy as, find in grid", null));
        JPanel recent = vertical(0);
        add(recent, heading(firstRun ? "Connect your first database" : "Recent"));
        add(recent, Box.createVerticalStrut(JBUI.scale(6)) instanceof JComponent strut2 ? strut2 : new JPanel());
        if (firstRun) add(recent, (JComponent) sourcesList());
        else for (Recent item : recents()) add(recent, row(item.icon(), item.title(), item.connection() + "  ·  " + item.when(), null));
        JPanel columns = new JPanel(width < 600 ? new GridLayout(2, 1, 0, JBUI.scale(20)) : new GridLayout(1, 2, JBUI.scale(40), 0));
        columns.setOpaque(false);
        columns.add(top(start)); columns.add(top(recent));
        body.add(columns, BorderLayout.CENTER);
        JCheckBox show = new JCheckBox("Show on startup", true); show.setOpaque(false);
        body.add(show, BorderLayout.SOUTH);
        return page(body);
    }
    private static JComponent top(JComponent content) {
        JPanel holder = new JPanel(new BorderLayout()); holder.setOpaque(false); holder.add(content, BorderLayout.NORTH); return holder;
    }

    // ---- 02 Connection board: every connection with its state, tag and last objects ---------------------------------
    private static JComponent board(boolean firstRun, int width) {
        JPanel body = new JPanel(new BorderLayout(0, JBUI.scale(16)));
        body.setOpaque(false);
        body.setBorder(JBUI.Borders.empty(24, width < 600 ? 16 : 40));
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(12), 0)); header.setOpaque(false);
        header.add(brandLine(), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)); actions.setOpaque(false);
        if (!firstRun) {
            com.intellij.ui.SearchTextField filter = new com.intellij.ui.SearchTextField(false);
            filter.getTextEditor().getEmptyText().setText("Filter connections");
            filter.setPreferredSize(JBUI.size(width < 600 ? 150 : 220, 30));
            actions.add(filter);
        }
        JButton add = new JButton("New connection…", AllIcons.General.Add);
        actions.add(add);
        header.add(actions, BorderLayout.EAST);
        body.add(header, BorderLayout.NORTH);
        JPanel list = vertical(0);
        if (firstRun) {
            add(list, heading("No connections yet"));
            add(list, Box.createVerticalStrut(JBUI.scale(4)) instanceof JComponent strut ? strut : new JPanel());
            add(list, muted("Pick a database type, or open a project whose config files point at a database."));
            add(list, Box.createVerticalStrut(JBUI.scale(12)) instanceof JComponent strut2 ? strut2 : new JPanel());
            add(list, (JComponent) sourcesList());
        } else {
            for (Connection connection : CONNECTIONS) {
                JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)); trailing.setOpaque(false);
                trailing.add(tag(connection.tag()));
                if (width >= 600) trailing.add(status(connection.status()));
                add(list, row(Icons.dataSource(connection.type(), 20), connection.name(), connection.where(), trailing));
                JPanel objects = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0)); objects.setOpaque(false);
                objects.setBorder(JBUI.Borders.empty(0, 38, 8, 8));
                for (Recent item : recents()) if (item.connection().equals(connection.name())) {
                    ActionLink link = new ActionLink(item.title()); link.setIcon(item.icon()); objects.add(link);
                }
                if (connection.status() == Status.UNREACHABLE) { objects.add(muted("Last reached 3 days ago")); objects.add(new ActionLink("Retry")); }
                if (objects.getComponentCount() > 0) { objects.setMaximumSize(new Dimension(Integer.MAX_VALUE, objects.getPreferredSize().height)); add(list, objects); }
                add(list, divider());
            }
        }
        body.add(top(list), BorderLayout.CENTER);
        return page(body);
    }

    // ---- 03 Command home: one field, ranked results, Enter opens --------------------------------------------------
    private static JComponent command(boolean firstRun, int width) {
        JPanel body = new JPanel(new BorderLayout(0, JBUI.scale(14)));
        body.setOpaque(false);
        int side = width < 600 ? 16 : (width - 600) / 2;
        body.setBorder(JBUI.Borders.empty(56, side, 24, side));
        JPanel head = vertical(10);
        add(head, brandLine());
        com.intellij.ui.SearchTextField field = new com.intellij.ui.SearchTextField(false);
        field.getTextEditor().getEmptyText().setText(firstRun ? "Type a database to connect: mysql, postgres, h2…" : "Open a table, console, script or connection…");
        field.setPreferredSize(JBUI.size(600, 36));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(36)));
        add(head, field);
        body.add(head, BorderLayout.NORTH);
        JPanel results = vertical(0);
        if (firstRun) {
            for (DatabaseType type : List.of(DatabaseType.MYSQL, DatabaseType.POSTGRESQL, DatabaseType.H2, DatabaseType.HSQLDB))
                add(results, result(Icons.dataSource(type, 20), "Connect to " + type.getDisplayName() + "…", "New connection", type == DatabaseType.MYSQL));
        } else {
            boolean first = true;
            for (Recent item : recents()) { add(results, result(item.icon(), item.title(), item.connection() + "  ·  " + item.when(), first)); first = false; }
            add(results, result(AllIcons.General.Add, "New connection…", "MySQL, PostgreSQL, H2, HSQLDB", false));
        }
        body.add(top(results), BorderLayout.CENTER);
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(16), 0)); footer.setOpaque(false);
        footer.add(muted("Up/Down choose  ·  Enter open  ·  Alt+Enter open in console"));
        footer.add(new ActionLink("Run SQL script…"));
        footer.add(new ActionLink("What's new"));
        body.add(footer, BorderLayout.SOUTH);
        return page(body);
    }
    private static JComponent result(Icon icon, String title, String subline, boolean selected) {
        JPanel row = row(icon, title, subline, selected ? muted("Enter") : null);
        if (selected) {
            row.setOpaque(true);
            row.setBackground(JBColor.namedColor("List.selectionInactiveBackground", new JBColor(0xDFE1E5, 0x43454A)));
        }
        return row;
    }

    // ---- 00 production baseline -----------------------------------------------------------------------------------
    private static JComponent baseline(boolean firstRun) throws Exception {
        var settings = new DatabaseSettingsState();
        if (!firstRun) {
            var field = DatabaseSettingsState.class.getDeclaredField("state"); field.setAccessible(true);
            var state = (DatabaseSettingsState.State) field.get(settings);
            state.connections.add(new ConnectionConfig(DatabaseType.HSQLDB, "Local development"));
        }
        var constructor = com.segfault03.ideadb.ui.WelcomePanel.class.getDeclaredConstructor(
                com.intellij.openapi.project.Project.class, DatabaseSettingsState.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, settings);
    }

    // ---- rendering ------------------------------------------------------------------------------------------------
    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    /** Text measured at 100% is narrower than the same text painted at 2x; widen labels so no last letter is lost (as in UiPreview). */
    private static void widenLabels(Component component, java.awt.font.FontRenderContext context) {
        if (component instanceof JLabel label && label.getText() != null && !label.getText().isEmpty() && label.getFont() != null) {
            int text = (int) Math.ceil(label.getFont().getStringBounds(label.getText(), context).getWidth());
            Icon icon = label.getIcon();
            Insets insets = label.getInsets();
            int needed = text + insets.left + insets.right + (icon == null ? 0 : icon.getIconWidth() + label.getIconTextGap());
            if (needed > label.getWidth()) label.setSize(needed + 2, label.getHeight());
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) widenLabels(child, context);
    }
    private static void render(JComponent panel, Path target, int width, int height) throws Exception {
        panel.setSize(width, height);
        for (int pass = 0; pass < 5; pass++) layout(panel);
        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        widenLabels(panel, new java.awt.font.FontRenderContext(java.awt.geom.AffineTransform.getScaleInstance(2, 2), true, true));
        panel.printAll(g); g.dispose();
        ImageIO.write(image, "png", target.toFile());
        System.out.println(target.getFileName() + " · " + image.getWidth() + " × " + image.getHeight());
    }

    public static void main(String[] args) throws Exception {
        try {
            com.intellij.testFramework.common.TestApplicationKt.loadApp();
            dark = args[1].equals("dark");
            UIManager.setLookAndFeel(dark ? new FlatDarculaLaf() : new FlatIntelliJLaf());
            Font font = new javax.swing.plaf.FontUIResource("DejaVu Sans", Font.PLAIN, 13);
            UIManager.put("defaultFont", font);
            for (String key : List.of("Label.font", "Button.font", "CheckBox.font", "ComboBox.font", "TextField.font", "TextArea.font"))
                UIManager.put(key, font);
            JBColor.setDark(dark);
            com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
            IconLoader.activate(); IconLoader.setUseDarkIcons(dark);
            Path output = Path.of(args[0]); Files.createDirectories(output);
            String theme = args[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    for (boolean firstRun : new boolean[]{false, true}) {
                        String state = firstRun ? "first-run" : "returning";
                        render(baseline(firstRun), output.resolve("00-current-" + state + "-" + theme + ".png"), WIDTH, HEIGHT);
                        render(launchpad(firstRun, WIDTH), output.resolve("01-launchpad-" + state + "-" + theme + ".png"), WIDTH, HEIGHT);
                        render(board(firstRun, WIDTH), output.resolve("02-board-" + state + "-" + theme + ".png"), WIDTH, HEIGHT);
                        render(command(firstRun, WIDTH), output.resolve("03-command-" + state + "-" + theme + ".png"), WIDTH, HEIGHT);
                    }
                    render(launchpad(false, NARROW), output.resolve("01-launchpad-narrow-" + theme + ".png"), NARROW, HEIGHT);
                    render(board(false, NARROW), output.resolve("02-board-narrow-" + theme + ".png"), NARROW, HEIGHT);
                    render(command(false, NARROW), output.resolve("03-command-narrow-" + theme + ".png"), NARROW, HEIGHT);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
