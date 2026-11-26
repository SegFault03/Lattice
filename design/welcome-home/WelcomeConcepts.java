import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.InlineBanner;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.ui.DatabaseUi;
import com.segfault03.ideadb.ui.Icons;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Presentation-only concepts for the welcome editor tab as a home screen (roadmap R7). No listeners, no services, no
 * database access: every control shows fixed values from one synthetic fixture. See design/README.md and
 * design/welcome-home/README.md.
 *
 * Writes raw/{concept}-{state}-{theme}-f{1|2}.png (f2 = UI font doubled, IDE scale unchanged) and
 * raw/{concept}-m{n}-{0|1|2}-{theme}-f1.png motion frames (start, mid, end).
 */
public final class WelcomeConcepts {
    private static final int WIDTH = 900, HEIGHT = 640, NARROW = 520, TOOL_WIDTH = 360, TOOL_NARROW = 280, TOOL_HEIGHT = 720;
    private static final int BASE_FONT = 13;

    // ---- shared fixture (synthetic) -------------------------------------------------------------------------------
    private enum Status { CONNECTED, READ_ONLY, CHECKING, UNREACHABLE }
    private enum State { RETURNING, FIRST_RUN, FILTERED, NO_MATCH, LOADING, ERROR, LONG, JUST_ADDED }
    /** MariaDB and SQLite have no DatabaseType or icon yet; they use the generic database icon as a labeled placeholder. */
    private record Engine(String name, Icon icon, String hint) {}
    private record Connection(String name, Engine engine, String where, Status status, String tag) {}
    private record Recent(Icon icon, String title, String connection, String when) {}
    /** One frame: state, logical width, focal-motion progress (1 = settled) and status-crossfade progress. */
    private record Frame(State state, int width, float arrival, float checked) {
        Frame(State state, int width) { this(state, width, 1f, 1f); }
    }

    private static Engine mysql, mariadb, postgresql, sqlite, h2, hsqldb;
    private static void engines() {
        mysql = new Engine("MySQL", Icons.dataSource(DatabaseType.MYSQL, 20), "Server · port 3306");
        mariadb = new Engine("MariaDB", Icons.DATABASE, "Server · port 3306");
        postgresql = new Engine("PostgreSQL", Icons.dataSource(DatabaseType.POSTGRESQL, 20), "Server · port 5432");
        sqlite = new Engine("SQLite", Icons.DATABASE, "Local file");
        h2 = new Engine("H2", Icons.dataSource(DatabaseType.H2, 20), "Memory, file or server");
        hsqldb = new Engine("HSQLDB", Icons.dataSource(DatabaseType.HSQLDB, 20), "Memory, file or server");
    }
    private static List<Engine> allEngines() { return List.of(mysql, mariadb, postgresql, sqlite, h2, hsqldb); }
    private static final String ENGINE_LIST = "MySQL, MariaDB, PostgreSQL, SQLite, H2, HSQLDB";

    private static List<Connection> connections(State state) {
        if (state == State.JUST_ADDED) return List.of(new Connection("Local development", hsqldb, "In-memory · lattice_dev", Status.CONNECTED, "local"));
        boolean checking = state == State.LOADING;
        List<Connection> list = new ArrayList<>(List.of(
                new Connection("Local development", hsqldb, "In-memory · lattice_dev", Status.CONNECTED, "local"),
                new Connection("Orders fixture", h2, "File · ~/fixtures/orders.mv.db", Status.CONNECTED, "local"),
                new Connection("app.db", sqlite, "File · ~/projects/notes/app.db", Status.CONNECTED, "local"),
                new Connection("Analytics server", mysql, "analytics.example.internal:3306", Status.UNREACHABLE, "development"),
                new Connection("Catalog", mariadb, "catalog.example.internal:3306", Status.CONNECTED, "development"),
                new Connection("Staging", postgresql, "staging-db.example.internal:5432", Status.CONNECTED, "staging"),
                new Connection("Reporting replica", postgresql, "reporting.example.internal:5432", Status.READ_ONLY, "production")));
        if (state == State.LONG) for (int i = 1; i <= 8; i++)
            list.add(new Connection("Tenant " + String.format("%02d", i), postgresql, "tenant-" + i + ".example.internal:5432", Status.CONNECTED, i % 3 == 0 ? "production" : "staging"));
        if (checking) list.replaceAll(c -> c.engine() == sqlite || c.engine() == h2 && c.where().startsWith("File") ? c
                : new Connection(c.name(), c.engine(), c.where(), Status.CHECKING, c.tag()));
        return list;
    }
    private static List<Recent> recents(State state) {
        List<Recent> list = new ArrayList<>(List.of(
                new Recent(Icons.TABLE, "PUBLIC.CUSTOMERS", "Local development", "2 min ago"),
                new Recent(Icons.TABLE, "ORDERS", "Orders fixture", "Yesterday"),
                new Recent(Icons.TABLE, "notes", "app.db", "Yesterday"),
                new Recent(Icons.CONSOLE, "Console", "Analytics server", "3 days ago"),
                new Recent(Icons.SQL_FILE, "seed.sql", "Local development", "Last week"),
                new Recent(Icons.VIEW, "daily_revenue", "Reporting replica", "Last week")));
        if (state == State.LONG) for (int i = 1; i <= 34; i++)
            list.add(new Recent(Icons.TABLE, "tenant_" + String.format("%02d", (i % 8) + 1) + ".invoices_" + i, "Tenant " + String.format("%02d", (i % 8) + 1), i + 1 + " weeks ago"));
        return list;
    }

    // ---- tokens: 4-based spacing, colors with >= 4.5:1 text contrast on both panel backgrounds -------------------
    private static final int S4 = 4, S8 = 8, S12 = 12, S16 = 16, S24 = 24, S32 = 32, S48 = 48;
    private static Color tagColor(String tag) {
        return switch (tag) {
            case "production" -> new JBColor(0xAD241D, 0xF2B8B5);
            case "staging" -> new JBColor(0x855000, 0xF0BE6E);
            case "development" -> new JBColor(0x2A56A3, 0xB4CEFB);
            default -> new JBColor(0x3D6829, 0xB4D9A3);
        };
    }
    private static Color statusColor(Status status) {
        return switch (status) {
            case CONNECTED -> new JBColor(0x2E7D32, 0x6BBF5E);
            case READ_ONLY -> new JBColor(0x2A56A3, 0xB4CEFB);
            case CHECKING -> new JBColor(0x6C707E, 0x9DA0A8);
            case UNREACHABLE -> new JBColor(0xC62828, 0xF28B82);
        };
    }
    private static String statusText(Status status) {
        return switch (status) { case CONNECTED -> "Connected"; case READ_ONLY -> "Read-only"; case CHECKING -> "Checking…"; case UNREACHABLE -> "Unreachable"; };
    }
    /** Approximates the New UI list selection (light D5E1FF / dark 2E436E); FlatLaf's own value is the old strong blue. */
    private static final Color SELECTION = new JBColor(0xD5E1FF, 0x2E436E);
    private static final Color ARRIVAL = new JBColor(new Color(0x3574F0), new Color(0x548AF7));

    // ---- shared parts --------------------------------------------------------------------------------------------
    private static JBLabel text(String value) { return new JBLabel(value); }
    private static JBLabel muted(String value) { JBLabel label = new JBLabel(value); label.setForeground(DatabaseUi.SECONDARY_TEXT); return label; }
    private static JBLabel heading(String value) { JBLabel label = new JBLabel(value); label.setFont(JBFont.label().asBold()); return label; }
    private static JBLabel section(String value) { JBLabel label = muted(value); label.setFont(JBFont.small().asBold()); label.setBorder(JBUI.Borders.empty(S12, S8, S4, S8)); return label; }

    private static JPanel column(int gap) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.putClientProperty("gap", gap);
        return panel;
    }
    private static void add(JPanel column, Component child) {
        if (column.getComponentCount() > 0 && (Integer) column.getClientProperty("gap") > 0)
            column.add(Box.createVerticalStrut(JBUI.scale((Integer) column.getClientProperty("gap"))));
        if (child instanceof JComponent component) component.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(child);
    }
    private static void space(JPanel column, int height) { column.add(Box.createVerticalStrut(JBUI.scale(height))); }
    private static JPanel top(JComponent content) {
        JPanel holder = new JPanel(new BorderLayout()); holder.setOpaque(false); holder.add(content, BorderLayout.NORTH); return holder;
    }
    private static JPanel flow(int align, int gap) {
        JPanel panel = new JPanel(new FlowLayout(align, JBUI.scale(gap), 0)); panel.setOpaque(false); return panel;
    }
    private static JComponent fill(JComponent component) {
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        return component;
    }

    /** Two-line row: icon, title, muted subline, optional trailing component. */
    private static JPanel row(Icon icon, String title, String subline, JComponent trailing) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(S12), 0));
        row.setOpaque(false);
        row.setBorder(JBUI.Borders.empty(S8));
        row.add(top(new JBLabel(icon)), BorderLayout.WEST);
        JPanel lines = column(2);
        add(lines, text(title));
        if (subline != null) add(lines, muted(subline));
        row.add(lines, BorderLayout.CENTER);
        if (trailing != null) row.add(trailing, BorderLayout.EAST);
        return (JPanel) fill(row);
    }
    /** One-line row for dense lists: icon, title, muted context. */
    private static JPanel line(Icon icon, String title, String context, boolean selected) {
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(S8), 0));
        row.setOpaque(selected);
        if (selected) row.setBackground(SELECTION);
        row.setBorder(JBUI.Borders.empty(S4, S8));
        JBLabel name = new JBLabel(title, icon, SwingConstants.LEFT);
        name.setIconTextGap(JBUI.scale(S8));
        row.add(name, BorderLayout.WEST);
        if (context != null) {
            JBLabel side = muted(context); side.setHorizontalAlignment(SwingConstants.RIGHT); side.putClientProperty("fit", true); side.setBorder(JBUI.Borders.emptyRight(S8));
            side.setMinimumSize(new Dimension(0, 0)); side.setPreferredSize(new Dimension(0, side.getPreferredSize().height));
            row.add(side, BorderLayout.CENTER);
        }
        return (JPanel) fill(row);
    }
    private static JPanel selected(JPanel row) { row.setOpaque(true); row.setBackground(SELECTION); return row; }

    /** A status dot plus its word, so state never rests on color alone. */
    private static Icon dot(Status status) {
        return new Icon() {
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(statusColor(status)); int size = JBUI.scale(8);
                if (status == Status.CHECKING) { g.setStroke(new BasicStroke(JBUI.scale(1.5f))); g.drawOval(x, y + (getIconHeight() - size) / 2, size - 1, size - 1); }
                else g.fillOval(x, y + (getIconHeight() - size) / 2, size, size);
                g.dispose();
            }
            @Override public int getIconWidth() { return JBUI.scale(8); }
            @Override public int getIconHeight() { return JBUI.scale(14); }
        };
    }
    private static JComponent status(Status status, float alpha) {
        JBLabel label = new JBLabel(statusText(status), dot(status), SwingConstants.LEFT);
        label.setIconTextGap(JBUI.scale(6));
        label.setForeground(DatabaseUi.SECONDARY_TEXT);
        return alpha >= 1f ? label : fade(label, alpha, 0);
    }
    /** Environment tag: tinted pill with text in the tag's own hue (never gray on a colored surface). */
    private static JComponent tag(String value) {
        Color hue = tagColor(value);
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
        label.setBorder(JBUI.Borders.empty(1, S8));
        return label;
    }
    /** Paints its child with an opacity and a vertical offset: one frame of a transition. */
    private static JComponent fade(JComponent child, float alpha, int dy) {
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override protected void paintChildren(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
                g.translate(0, JBUI.scale(dy));
                super.paintChildren(g); g.dispose();
            }
        };
        panel.setOpaque(false); panel.add(child); panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }
    /** The focal moment: a newly added connection rises 8 px into place and its accent tint fades out. */
    private static JComponent arriving(JPanel row, float progress) {
        if (progress >= 1f) return row;
        float eased = 1f - (float) Math.pow(1f - progress, 4); // ease-out-quart
        float tint = progress < 0.5f ? 0.22f : 0.22f * (1f - (progress - 0.5f) / 0.5f);
        row.setOpaque(true);
        Color base = JBColor.namedColor("Panel.background", JBColor.background());
        row.setBackground(blend(ARRIVAL, base, tint));
        return fill(fade(row, eased, Math.round(8 * (1f - eased))));
    }
    private static Color blend(Color top, Color base, float amount) {
        return new Color(Math.round(top.getRed() * amount + base.getRed() * (1 - amount)),
                Math.round(top.getGreen() * amount + base.getGreen() * (1 - amount)),
                Math.round(top.getBlue() * amount + base.getBlue() * (1 - amount)));
    }
    private static JComponent divider() {
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(1)));
        return separator;
    }
    private static JComponent banner(String message, String... actions) {
        InlineBanner banner = new InlineBanner(message, EditorNotificationPanel.Status.Error);
        for (String action : actions) banner.addAction(action, () -> {});
        banner.showCloseButton(false);
        return fill(banner);
    }
    private static SearchTextField search(String placeholder, String value, int width) {
        SearchTextField field = new SearchTextField(false);
        field.getTextEditor().getEmptyText().setText(placeholder);
        if (value != null) field.setText(value);
        field.setPreferredSize(new Dimension(JBUI.scale(width), field.getPreferredSize().height));
        return field;
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
        brand.setIconTextGap(JBUI.scale(S8));
        return brand;
    }
    private static JComponent scroll(JComponent content) {
        JBScrollPane pane = new JBScrollPane(top(content), ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setBorder(JBUI.Borders.empty()); pane.setOpaque(false); pane.getViewport().setOpaque(false);
        return pane;
    }
    private static String highlight(String value, String query) {
        int at = value.toLowerCase().indexOf(query.toLowerCase());
        if (at < 0) return value;
        return "<html>" + value.substring(0, at) + "<b>" + value.substring(at, at + query.length()) + "</b>" + value.substring(at + query.length()) + "</html>";
    }

    // ---- 02 Connection board: every connection with its environment, status and last objects ---------------------
    private static JComponent board(Frame frame) {
        State state = frame.state();
        boolean narrow = frame.width() < 600;
        JPanel body = new JPanel(new BorderLayout(0, JBUI.scale(S16)));
        body.setOpaque(false);
        body.setBorder(JBUI.Borders.empty(S24, narrow ? S16 : S32, 0, narrow ? S16 : S32));
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(S12), 0)); header.setOpaque(false);
        header.add(brandLine(), BorderLayout.WEST);
        JPanel actions = flow(FlowLayout.RIGHT, S8);
        if (state != State.FIRST_RUN && state != State.JUST_ADDED) {
            String query = state == State.FILTERED ? "stag" : state == State.NO_MATCH ? "prod-eu" : null;
            actions.add(search(narrow ? "Filter" : "Filter connections", query, narrow ? 140 : 220));
        }
        actions.add(new JButton(narrow ? "New…" : "New connection…", AllIcons.General.Add));
        header.add(actions, BorderLayout.EAST);
        body.add(header, BorderLayout.NORTH);
        JPanel list = column(0);
        if (state == State.FIRST_RUN) {
            add(list, heading("No connections yet"));
            space(list, S4);
            add(list, muted("Choose a database to create your first connection."));
            space(list, S12);
            for (Engine engine : allEngines()) add(list, row(engine.icon(), engine.name(), engine.hint(), new ActionLink("Connect")));
        } else if (state == State.NO_MATCH) {
            add(list, heading("No connection matches “prod-eu”"));
            space(list, S4);
            add(list, muted("Filters match names, hosts and environment tags."));
            space(list, S8);
            add(list, new ActionLink("Clear filter"));
        } else {
            List<Recent> recents = recents(State.RETURNING);
            for (Connection connection : connections(state)) {
                if (state == State.FILTERED && !connection.name().toLowerCase().contains("stag") && !connection.tag().contains("stag")) continue;
                JPanel trailing = flow(FlowLayout.RIGHT, S12);
                trailing.add(tag(connection.tag()));
                float checked = state == State.LOADING ? 1f : frame.checked();
                Status shown = frame.checked() < 1f ? (frame.checked() < 0.5f ? Status.CHECKING : connection.status()) : connection.status();
                if (!narrow) trailing.add(status(shown, frame.checked() < 1f ? Math.abs(frame.checked() - 0.5f) * 2 : checked));
                String subline = narrow ? statusText(connection.status()) + " · " + connection.where() : connection.where();
                String title = state == State.FILTERED ? highlight(connection.name(), "stag") : connection.name();
                JPanel item = row(connection.engine().icon(), title, subline, trailing);
                add(list, state == State.JUST_ADDED ? arriving(item, frame.arrival()) : item);
                JPanel objects = flow(FlowLayout.LEFT, S16);
                objects.setBorder(JBUI.Borders.empty(0, 40, S8, S8));
                for (Recent recent : recents) if (recent.connection().equals(connection.name())) {
                    ActionLink link = new ActionLink(recent.title()); link.setIcon(recent.icon()); objects.add(link);
                }
                if (connection.status() == Status.UNREACHABLE && state != State.LOADING) {
                    objects.add(muted("Last reached 3 days ago")); objects.add(new ActionLink("Retry"));
                }
                if (state == State.JUST_ADDED) { objects.add(new ActionLink("Open SQL console")); objects.add(new ActionLink("Browse tables")); }
                if (objects.getComponentCount() > 0) add(list, state == State.JUST_ADDED ? fade(fill(objects), frame.arrival(), 0) : fill(objects));
                if (state == State.ERROR && connection.name().equals("Staging")) {
                    add(list, banner("Could not open PUBLIC.ORDERS on Staging: connection refused by staging-db.example.internal:5432.", "Retry", "Edit connection"));
                    space(list, S8);
                }
                add(list, divider());
            }
        }
        body.add(scroll(list), BorderLayout.CENTER);
        return page(body);
    }

    // ---- 03 Command home: one field, ranked results, Enter opens --------------------------------------------------
    private static JComponent command(Frame frame) {
        State state = frame.state();
        boolean narrow = frame.width() < 600;
        JPanel body = new JPanel(new BorderLayout(0, JBUI.scale(S12)));
        body.setOpaque(false);
        int side = narrow ? S16 : (frame.width() - 600) / 2;
        body.setBorder(JBUI.Borders.empty(S48, side, S24, side));
        JPanel head = column(S12);
        add(head, brandLine());
        String query = state == State.FILTERED ? "ord" : state == State.NO_MATCH ? "orderz" : null;
        SearchTextField field = search(state == State.FIRST_RUN ? "Type a database to connect: MySQL, MariaDB, PostgreSQL…"
                : "Open a table, console, script or connection…", query, 600);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        add(head, field);
        if (state == State.ERROR) add(head, banner("Analytics server timed out after 10 s (analytics.example.internal:3306).", "Retry", "Edit connection"));
        body.add(head, BorderLayout.NORTH);
        JPanel results = column(0);
        switch (state) {
            case FIRST_RUN -> {
                add(results, section("New connection"));
                boolean first = true;
                for (Engine engine : allEngines()) {
                    JPanel item = row(engine.icon(), "Connect to " + engine.name() + "…", engine.hint(), first ? muted("Enter") : null);
                    add(results, first ? selected(item) : item); first = false;
                }
            }
            case FILTERED -> {
                add(results, section("Recent"));
                add(results, selected(row(Icons.TABLE, highlight("ORDERS", "ord"), "Orders fixture · Yesterday", muted("Enter"))));
                add(results, section("Tables in loaded schemas"));
                add(results, row(Icons.TABLE, highlight("PUBLIC.ORDERS", "ord"), "Staging", null));
                add(results, row(Icons.TABLE, highlight("PUBLIC.ORDER_ITEMS", "ord"), "Local development", null));
                add(results, section("Connections"));
                add(results, row(h2.icon(), highlight("Orders fixture", "ord"), "H2 · Connected", null));
            }
            case NO_MATCH -> {
                space(results, S16);
                add(results, heading("Nothing matches “orderz”"));
                space(results, S4);
                add(results, muted(narrow ? "Search covers loaded schemas only." : "Search covers recent items and the schemas Lattice has already loaded."));
                space(results, S8);
                JPanel links = flow(FlowLayout.LEFT, 0);
                ActionLink refresh = new ActionLink("Refresh schemas"); links.add(refresh);
                links.add(Box.createHorizontalStrut(JBUI.scale(S16))); links.add(new ActionLink("New connection…"));
                add(results, fill(links));
            }
            case JUST_ADDED -> {
                add(results, section("New"));
                add(results, arriving(row(hsqldb.icon(), "Local development", "HSQLDB · Connected · just now", null), frame.arrival()));
                add(results, fade(fill(row(Icons.CONSOLE, "Open SQL console", "Local development", null)), frame.arrival(), 0));
                add(results, fade(fill(row(Icons.TABLE, "Browse tables", "Local development · 4 tables", null)), frame.arrival(), 0));
                add(results, row(AllIcons.General.Add, "New connection…", ENGINE_LIST, null));
            }
            default -> {
                add(results, section("Recent"));
                List<Recent> items = recents(state);
                int shown = state == State.LONG ? 8 : items.size();
                for (int i = 0; i < shown; i++) {
                    Recent item = items.get(i);
                    boolean pick = state == State.ERROR ? item.title().equals("Console") : i == 0;
                    JPanel line = row(item.icon(), item.title(), item.connection() + "  ·  " + item.when(), pick ? muted("Enter") : null);
                    add(results, pick ? selected(line) : line);
                }
                if (state == State.LONG) { space(results, S4); add(results, muted("   " + (items.size() - shown) + " more recent items · type to narrow")); }
                if (state == State.LOADING) {
                    add(results, section("Tables in loaded schemas"));
                    JBLabel loading = muted("Loading schemas from 5 connections…"); loading.setIcon(AllIcons.Process.Step_1);
                    loading.setBorder(JBUI.Borders.empty(S8)); add(results, loading);
                }
                if (state != State.LONG) add(results, row(AllIcons.General.Add, "New connection…", ENGINE_LIST, null));
            }
        }
        body.add(scroll(results), BorderLayout.CENTER);
        JPanel footer = flow(FlowLayout.LEFT, S16);
        if (!narrow) footer.add(muted("Up/Down choose  ·  Enter open  ·  Alt+Enter open in console"));
        footer.add(new ActionLink("Run SQL script…"));
        footer.add(new ActionLink("What's new"));
        body.add(footer, BorderLayout.SOUTH);
        return page(body);
    }

    // ---- 04 Explorer home (radical): no welcome tab; home is the tool window plus a Search Everywhere tab ---------
    private record Node(Icon icon, String name, String context, Status status, String tag, boolean error, boolean match) {}
    private static DefaultMutableTreeNode node(Icon icon, String name, String context) { return new DefaultMutableTreeNode(new Node(icon, name, context, null, null, false, false)); }

    private static JComponent explorer(Frame frame) {
        State state = frame.state();
        JPanel window = new JPanel(new BorderLayout());
        window.setBackground(JBColor.namedColor("ToolWindow.background", JBColor.namedColor("Panel.background", JBColor.background())));
        // Tool window header and toolbar: painted labels approximating the platform ActionToolbar (no action system here).
        JPanel header = new JPanel(new BorderLayout()); header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(S8, S12, S4, S8));
        header.add(heading("Lattice"), BorderLayout.WEST);
        JPanel toolbar = flow(FlowLayout.RIGHT, S8);
        for (Icon icon : List.of(AllIcons.General.Add, AllIcons.Actions.Refresh, Icons.CONSOLE, AllIcons.Actions.Search, AllIcons.Actions.More))
            toolbar.add(new JBLabel(icon));
        header.add(toolbar, BorderLayout.EAST);
        JPanel north = column(0);
        add(north, fill(header));
        if (state == State.FILTERED || state == State.NO_MATCH) {
            SearchTextField field = search("Search connections and tables", state == State.FILTERED ? "ord" : "orderz", frame.width() - 24);
            JPanel holder = new JPanel(new BorderLayout()); holder.setOpaque(false); holder.setBorder(JBUI.Borders.empty(0, S8, S8, S8));
            holder.add(field); add(north, fill(holder));
        }
        if (state == State.ERROR) {
            JPanel holder = new JPanel(new BorderLayout()); holder.setOpaque(false); holder.setBorder(JBUI.Borders.empty(0, S8, S8, S8));
            holder.add(banner("Analytics server is unreachable: timed out after 10 s.", "Retry", "Edit")); add(north, fill(holder));
        }
        window.add(north, BorderLayout.NORTH);
        JPanel content = column(0);
        if (state == State.FIRST_RUN) {
            content.setBorder(JBUI.Borders.empty(S24, S16));
            add(content, heading("No connections yet"));
            space(content, S4);
            add(content, muted("Recent tables, consoles and scripts"));
            add(content, muted("will appear here and in Search Everywhere."));
            space(content, S12);
            for (Engine engine : allEngines()) { ActionLink link = new ActionLink(engine.name() + "…"); link.setIcon(engine.icon()); add(content, link); space(content, S8); }
            window.add(top(content), BorderLayout.CENTER);
            return window;
        }
        if (state == State.NO_MATCH) {
            content.setBorder(JBUI.Borders.empty(S16));
            add(content, text("Nothing matches “orderz”"));
            space(content, S4);
            add(content, muted("Searches loaded schemas only."));
            space(content, S8);
            add(content, new ActionLink("Refresh schemas"));
            window.add(top(content), BorderLayout.CENTER);
            return window;
        }
        if (state != State.JUST_ADDED && state != State.FILTERED) {
            JPanel recentHead = new JPanel(new BorderLayout()); recentHead.setOpaque(false);
            JBLabel label = muted("Recent"); label.setFont(JBFont.small().asBold()); label.setIcon(AllIcons.General.ArrowDown);
            label.setBorder(JBUI.Borders.empty(S4, S8)); recentHead.add(label, BorderLayout.WEST);
            add(content, fill(recentHead));
            List<Recent> items = recents(state);
            for (int i = 0; i < 4; i++) { Recent item = items.get(i); add(content, line(item.icon(), item.title(), item.connection(), false)); }
            if (state == State.LONG) add(content, fill(new ActionLink("    " + (items.size() - 4) + " more…")));
            space(content, S4);
            add(content, divider());
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        List<Integer> expand = new ArrayList<>();
        for (Connection connection : connections(state)) {
            boolean match = state == State.FILTERED;
            if (match && !List.of("Local development", "Orders fixture", "Staging").contains(connection.name())) continue;
            DefaultMutableTreeNode item = new DefaultMutableTreeNode(new Node(connection.engine().icon(), connection.name(), null,
                    connection.status(), connection.tag(), state == State.ERROR && connection.status() == Status.UNREACHABLE, false));
            if (state == State.FILTERED) {
                String table = connection.name().equals("Local development") ? "ORDER_ITEMS" : "ORDERS";
                DefaultMutableTreeNode schema = node(AllIcons.Nodes.Folder, connection.name().equals("Orders fixture") ? "PUBLIC" : "PUBLIC", null);
                schema.add(new DefaultMutableTreeNode(new Node(Icons.TABLE, table, null, null, null, false, true)));
                item.add(schema);
            } else if (connection.name().equals("Local development") && state != State.LONG) {
                DefaultMutableTreeNode schema = node(AllIcons.Nodes.Folder, "PUBLIC", "4 tables");
                for (String table : List.of("CUSTOMERS", "ORDER_ITEMS", "ORDERS", "PRODUCTS")) schema.add(node(Icons.TABLE, table, null));
                item.add(schema);
            }
            root.add(item);
        }
        Tree tree = new Tree(new DefaultTreeModel(root));
        tree.setRootVisible(false); tree.setShowsRootHandles(true);
        tree.setOpaque(true); tree.setBackground(window.getBackground());
        tree.setCellRenderer(new ColoredTreeCellRenderer() {
            @Override public void customizeCellRenderer(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                if (!(((DefaultMutableTreeNode) value).getUserObject() instanceof Node node)) return;
                setIcon(node.icon());
                if (node.match()) {
                    int at = node.name().toLowerCase().indexOf("ord");
                    append(node.name().substring(0, at));
                    append(node.name().substring(at, at + 3), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append(node.name().substring(at + 3));
                } else append(node.name());
                if (node.context() != null) append("  " + node.context(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (node.status() != null) {
                    if (node.error()) append("  Unreachable", SimpleTextAttributes.ERROR_ATTRIBUTES);
                    else if (node.status() == Status.UNREACHABLE) append("  Unreachable", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    else if (node.status() == Status.CHECKING) append("  Checking…", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    else if (node.status() == Status.READ_ONLY) append("  read-only", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (node.tag() != null && !node.tag().equals("local"))
                        append("  " + node.tag(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, tagColor(node.tag())));
                }
            }
        });
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        if (state == State.LONG) tree.collapseRow(0);
        JPanel treeHolder = new JPanel(new BorderLayout()); treeHolder.setOpaque(false); treeHolder.setBorder(JBUI.Borders.empty(S4, S4));
        treeHolder.add(tree);
        JComponent treePart = state == State.JUST_ADDED ? arrivingTree(treeHolder, frame.arrival()) : treeHolder;
        add(content, fill(treePart));
        if (state == State.JUST_ADDED) {
            JPanel next = column(S8); next.setBorder(JBUI.Borders.empty(S12, S16));
            add(next, new ActionLink("Open SQL console")); add(next, new ActionLink("Browse tables"));
            add(content, fade(next, frame.arrival(), 0));
        }
        window.add(scroll(content), BorderLayout.CENTER);
        return window;
    }
    private static JComponent arrivingTree(JPanel holder, float progress) {
        JPanel wrapper = new JPanel(new BorderLayout()); wrapper.setOpaque(false); wrapper.add(holder);
        return arriving(wrapper, progress);
    }

    /** Search Everywhere with a Lattice tab: an approximation painted from labels, not the platform popup. */
    private static JComponent searchEverywhere(Frame frame) {
        JPanel popup = new JPanel(new BorderLayout());
        popup.setBackground(JBColor.namedColor("SearchEverywhere.Header.background", new JBColor(0xF7F8FA, 0x2B2D30)));
        popup.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));
        JPanel tabs = flow(FlowLayout.LEFT, S16); tabs.setBorder(JBUI.Borders.empty(S8, S8, S4, S8));
        for (String name : List.of("All", "Classes", "Files", "Symbols", "Actions", "Lattice")) {
            JBLabel tab = name.equals("Lattice") ? heading(name) : muted(name);
            if (name.equals("Lattice")) { tab.setOpaque(true); tab.setBackground(SELECTION); tab.setBorder(JBUI.Borders.empty(2, S8)); }
            tabs.add(tab);
        }
        JPanel north = column(0); add(north, fill(tabs));
        SearchTextField field = search("", "ord", frame.width() - 16);
        JPanel holder = new JPanel(new BorderLayout()); holder.setOpaque(false); holder.setBorder(JBUI.Borders.empty(0, S8, S8, S8)); holder.add(field);
        add(north, fill(holder));
        popup.add(north, BorderLayout.NORTH);
        JPanel results = column(0); results.setBorder(JBUI.Borders.empty(0, 0, S8, 0));
        add(results, line(Icons.TABLE, highlight("ORDERS", "ord"), "Orders fixture · recent", true));
        add(results, line(Icons.TABLE, highlight("PUBLIC.ORDERS", "ord"), "Staging", false));
        add(results, line(Icons.TABLE, highlight("PUBLIC.ORDER_ITEMS", "ord"), "Local development", false));
        add(results, line(h2.icon(), highlight("Orders fixture", "ord"), "H2 connection", false));
        add(results, line(Icons.CONSOLE, "Open SQL console on Orders fixture", "action", false));
        popup.add(top(results), BorderLayout.CENTER);
        JPanel hint = flow(FlowLayout.LEFT, S8); hint.setBorder(JBUI.Borders.empty(S4, S8));
        hint.add(muted("Enter open  ·  Alt+Enter open in console  ·  Double Shift, then Tab to Lattice"));
        popup.add(hint, BorderLayout.SOUTH);
        JPanel stage = page(top(popup)); stage.setBorder(JBUI.Borders.empty(S24));
        return stage;
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
        String value = component instanceof JLabel label ? label.getText() : component instanceof AbstractButton button ? button.getText() : null;
        if (value != null && !value.isEmpty() && !value.startsWith("<html>") && component.getFont() != null
                && !Boolean.TRUE.equals(((JComponent) component).getClientProperty("fit"))) {
            JComponent target = (JComponent) component;
            int text = (int) Math.ceil(target.getFont().getStringBounds(value, context).getWidth());
            Icon icon = target instanceof JLabel label ? label.getIcon() : ((AbstractButton) target).getIcon();
            int gap = target instanceof JLabel label ? label.getIconTextGap() : ((AbstractButton) target).getIconTextGap();
            Insets insets = target.getInsets();
            int needed = text + insets.left + insets.right + (icon == null ? 0 : icon.getIconWidth() + gap);
            if (needed > target.getWidth()) target.setSize(needed + 2, target.getHeight());
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
    private static void fonts(int size) {
        Font font = new javax.swing.plaf.FontUIResource("DejaVu Sans", Font.PLAIN, size);
        UIManager.put("defaultFont", font);
        for (String key : List.of("Label.font", "Button.font", "CheckBox.font", "ComboBox.font", "TextField.font", "TextArea.font",
                "Tree.font", "List.font", "TextPane.font", "EditorPane.font"))
            UIManager.put(key, font);
    }

    private interface Painter { JComponent paint(Frame frame) throws Exception; }

    public static void main(String[] args) throws Exception {
        try {
            com.intellij.testFramework.common.TestApplicationKt.loadApp();
            boolean dark = args[1].equals("dark");
            UIManager.setLookAndFeel(dark ? new FlatDarculaLaf() : new FlatIntelliJLaf());
            JBColor.setDark(dark);
            com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
            IconLoader.activate(); IconLoader.setUseDarkIcons(dark);
            Path output = Path.of(args[0]); Files.createDirectories(output);
            String theme = args[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    engines();
                    for (int scale : new int[]{1, 2}) {
                        fonts(BASE_FONT * scale);
                        String suffix = "-" + theme + "-f" + scale + ".png";
                        render(baseline(false), output.resolve("00-returning" + suffix), WIDTH, HEIGHT);
                        render(baseline(true), output.resolve("00-first-run" + suffix), WIDTH, HEIGHT);
                        render(baseline(false), output.resolve("00-narrow" + suffix), NARROW, HEIGHT);
                        record Concept(String id, Painter painter, int width, int narrow, int height) {}
                        for (Concept concept : List.of(new Concept("02", WelcomeConcepts::board, WIDTH, NARROW, HEIGHT),
                                new Concept("03", WelcomeConcepts::command, WIDTH, NARROW, HEIGHT),
                                new Concept("04", WelcomeConcepts::explorer, TOOL_WIDTH, TOOL_NARROW, TOOL_HEIGHT))) {
                            for (State state : State.values()) {
                                if (state == State.JUST_ADDED) continue;
                                String key = state.name().toLowerCase().replace('_', '-');
                                render(concept.painter().paint(new Frame(state, concept.width())), output.resolve(concept.id() + "-" + key + suffix), concept.width(), concept.height());
                            }
                            render(concept.painter().paint(new Frame(State.RETURNING, concept.narrow())), output.resolve(concept.id() + "-narrow" + suffix), concept.narrow(), concept.height());
                            if (scale == 1) {
                                // Motion 1, the focal moment: first connection arrives (start = first run, mid = 40%, end = settled).
                                render(concept.painter().paint(new Frame(State.FIRST_RUN, concept.width())), output.resolve(concept.id() + "-m1-0" + suffix), concept.width(), concept.height());
                                render(concept.painter().paint(new Frame(State.JUST_ADDED, concept.width(), 0.4f, 1f)), output.resolve(concept.id() + "-m1-1" + suffix), concept.width(), concept.height());
                                render(concept.painter().paint(new Frame(State.JUST_ADDED, concept.width(), 1f, 1f)), output.resolve(concept.id() + "-m1-2" + suffix), concept.width(), concept.height());
                            }
                        }
                        if (scale == 1) {
                            // Motion 2 on the board: status check resolves (Checking… crossfades to the result).
                            render(board(new Frame(State.RETURNING, WIDTH, 1f, 0f)), output.resolve("02-m2-0" + suffix), WIDTH, HEIGHT);
                            render(board(new Frame(State.RETURNING, WIDTH, 1f, 0.6f)), output.resolve("02-m2-1" + suffix), WIDTH, HEIGHT);
                            render(board(new Frame(State.RETURNING, WIDTH, 1f, 1f)), output.resolve("02-m2-2" + suffix), WIDTH, HEIGHT);
                        }
                        render(searchEverywhere(new Frame(State.FILTERED, 640)), output.resolve("04-search-everywhere" + suffix), 688, 360);
                    }
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
