package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.state.DatabaseSettingsListener;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.RecentItemsState;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The explorer's Recent section: the last tables, views, consoles and scripts opened in this project, above the connection tree. */
final class ExplorerRecentSection extends JPanel {
    static final int VISIBLE = 4;
    private static final String COLLAPSED = "lattice.explorer.recentCollapsed";

    /** A recent item whose connection still exists. */
    record Entry(RecentItemsState.Item item, ConnectionConfig connection) {
        String title() { return com.segfault03.ideadb.search.LatticeSearch.recentTitle(item); }
        Icon icon() {
            if (item.kind == RecentItemsState.Kind.CONSOLE) return Icons.CONSOLE;
            if (item.kind == RecentItemsState.Kind.SCRIPT) return Icons.SQL_FILE;
            return new TableMetadata(null, null, item.name, item.tableType).isView() ? Icons.VIEW : Icons.TABLE;
        }
        String detail() {
            String where = item.database == null || item.database.isBlank() ? connection.getName() : connection.getName() + " · " + item.database;
            return item.kind == RecentItemsState.Kind.SCRIPT ? item.path + " on " + where : title() + " in " + where;
        }
    }

    private final Project project;
    private final PropertiesComponent preferences;
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JBList<Entry> list = new JBList<>(model);
    private final JBLabel header = new JBLabel("Recent");
    private final ActionLink more = new ActionLink("");
    private final JPanel body = new JPanel(new BorderLayout());
    private List<Entry> entries = List.of();
    private final List<Entry> fixture;
    private boolean suppressed;
    private boolean collapsed;

    ExplorerRecentSection(Project project, Disposable parent) {
        this(project, PropertiesComponent.getInstance(project), null);
        project.getMessageBus().connect(parent).subscribe(RecentItemsState.TOPIC,
                () -> SwingUtilities.invokeLater(() -> { if (!project.isDisposed()) reload(); }));
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect(parent)
                .subscribe(DatabaseSettingsListener.TOPIC, () -> SwingUtilities.invokeLater(() -> { if (!project.isDisposed()) reload(); }));
    }

    /** Previews: fixed entries, no project services. */
    static ExplorerRecentSection preview(List<Entry> entries, boolean collapsed) {
        ExplorerRecentSection section = new ExplorerRecentSection(null, null, entries);
        section.collapsed = collapsed;
        section.updateVisibility();
        return section;
    }

    private ExplorerRecentSection(Project project, PropertiesComponent preferences, List<Entry> fixture) {
        super(new BorderLayout());
        this.project = project;
        this.preferences = preferences;
        this.fixture = fixture;
        this.collapsed = preferences != null && preferences.getBoolean(COLLAPSED, false);
        setOpaque(false);

        header.setFont(JBFont.small().asBold());
        header.setForeground(DatabaseUi.SECONDARY_TEXT);
        header.setBorder(JBUI.Borders.empty(4, 8));
        header.setFocusable(true);
        header.getAccessibleContext().setAccessibleName("Recent, toggle");
        header.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { toggle(); }
        });
        bind(header, KeyEvent.VK_SPACE, this::toggle);
        bind(header, KeyEvent.VK_ENTER, this::toggle);
        add(header, BorderLayout.NORTH);

        list.setCellRenderer(new EntryRenderer());
        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Recent tables and consoles");
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && UIUtil.isActionClick(event)) openSelected();
            }
            @Override public void mousePressed(MouseEvent event) { maybeShowMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { maybeShowMenu(event); }
        });
        bind(list, KeyEvent.VK_ENTER, this::openSelected);
        bind(list, KeyEvent.VK_DELETE, () -> { Entry entry = list.getSelectedValue(); if (entry != null) forget(entry); });
        body.setOpaque(false);
        body.add(list, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        // Line the link up with the item titles: row inset plus icon and gap.
        footer.setBorder(JBUI.Borders.empty(0, 8 + 16 + 8, 0, 8));
        more.addActionListener(event -> showAll());
        footer.add(more, BorderLayout.WEST);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(footer, BorderLayout.NORTH);
        JSeparator divider = new JSeparator();
        JPanel dividerHolder = new JPanel(new BorderLayout());
        dividerHolder.setOpaque(false);
        dividerHolder.setBorder(JBUI.Borders.emptyTop(4));
        dividerHolder.add(divider);
        bottom.add(dividerHolder, BorderLayout.SOUTH);
        body.add(bottom, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);
        reload();
    }

    /** Search narrows the tree; the Recent section steps aside while it is open. */
    void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        updateVisibility();
    }

    List<Entry> entries() { return entries; }

    void reload() {
        entries = fixture != null ? fixture : resolve();
        Entry selected = list.getSelectedValue();
        model.clear();
        for (Entry entry : entries.subList(0, Math.min(VISIBLE, entries.size()))) model.addElement(entry);
        if (selected != null) for (int i = 0; i < model.size(); i++)
            if (model.get(i).item() == selected.item()) list.setSelectedIndex(i);
        int hidden = entries.size() - model.size();
        more.setText(hidden + " more…");
        more.setVisible(hidden > 0);
        updateVisibility();
    }

    private List<Entry> resolve() {
        Map<String, ConnectionConfig> connections = DatabaseSettingsState.getInstance().getConnections().stream()
                .collect(Collectors.toMap(ConnectionConfig::getId, Function.identity(), (first, second) -> first));
        List<Entry> resolved = new ArrayList<>();
        for (RecentItemsState.Item item : RecentItemsState.getInstance(project).items()) {
            ConnectionConfig connection = connections.get(item.connectionId);
            if (connection != null) resolved.add(new Entry(item, connection));
        }
        return List.copyOf(resolved);
    }

    private void updateVisibility() {
        header.setIcon(collapsed ? AllIcons.General.ArrowRight : AllIcons.General.ArrowDown);
        body.setVisible(!collapsed);
        setVisible(!suppressed && !entries.isEmpty());
        revalidate();
        repaint();
    }

    private void toggle() {
        collapsed = !collapsed;
        if (preferences != null) preferences.setValue(COLLAPSED, collapsed, false);
        updateVisibility();
    }

    JBList<Entry> recentList() { return list; }

    private void openSelected() {
        Entry entry = list.getSelectedValue();
        if (entry != null) open(entry);
    }

    private void open(Entry entry) {
        if (project == null) return;
        RecentItemsState.Item item = entry.item();
        DatabaseEditorManager editors = DatabaseEditorManager.getInstance(project);
        if (item.kind == RecentItemsState.Kind.CONSOLE) editors.openConsole(entry.connection(), item.database, null);
        else if (item.kind == RecentItemsState.Kind.SCRIPT)
            SwingUtilities.invokeLater(() -> com.segfault03.ideadb.dialog.SqlScriptDialog.show(project, entry.connection(), item.database, item.path));
        else editors.openTableData(entry.connection(), item.database, new TableMetadata(item.catalog, item.schema, item.name, item.tableType));
    }

    private void forget(Entry entry) { if (project != null) RecentItemsState.getInstance(project).remove(entry.item()); }

    private void maybeShowMenu(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        int row = list.locationToIndex(event.getPoint());
        if (row < 0 || !list.getCellBounds(row, row).contains(event.getPoint())) return;
        list.setSelectedIndex(row);
        Entry entry = list.getSelectedValue();
        JPopupMenu menu = DatabaseUi.popupMenu();
        JMenuItem open = new JMenuItem("Open");
        open.addActionListener(ignored -> open(entry));
        JMenuItem remove = new JMenuItem("Remove from Recent");
        remove.addActionListener(ignored -> forget(entry));
        JMenuItem clear = new JMenuItem("Clear Recent");
        clear.addActionListener(ignored -> { if (project != null) RecentItemsState.getInstance(project).clear(); });
        menu.add(open);
        menu.addSeparator();
        menu.add(remove);
        menu.add(clear);
        menu.show(list, event.getX(), event.getY());
    }

    private void showAll() {
        JBPopupFactory.getInstance().createPopupChooserBuilder(entries)
                .setTitle("Recent")
                .setRenderer(new EntryRenderer())
                .setNamerForFiltering(entry -> entry.title() + " " + entry.connection().getName())
                .setItemChosenCallback(this::open)
                .setVisibleRowCount(12)
                .createPopup()
                .showUnderneathOf(more);
    }

    private static void bind(JComponent component, int key, Runnable action) {
        String name = "lattice.recent." + key;
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        });
    }

    /** Icon and title on the left; the connection, muted and right-aligned, takes the rest and truncates first. */
    private static final class EntryRenderer extends JPanel implements ListCellRenderer<Entry> {
        private final com.intellij.ui.SimpleColoredComponent title = new com.intellij.ui.SimpleColoredComponent();
        private final Context context = new Context();

        EntryRenderer() {
            super(new BorderLayout(JBUI.scale(8), 0));
            setBorder(JBUI.Borders.empty(4, 8));
            title.setIconTextGap(JBUI.scale(8));
            title.setOpaque(false);
            title.setIpad(JBUI.emptyInsets());
            add(title, BorderLayout.WEST);
            add(context, BorderLayout.CENTER);
        }

        @Override public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry, int index,
                                                                boolean selected, boolean focused) {
            boolean focus = list.hasFocus();
            Color foreground = UIUtil.getListForeground(selected, focus);
            title.clear();
            title.setIcon(entry.icon());
            title.append(entry.title(), new com.intellij.ui.SimpleTextAttributes(com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN, foreground));
            title.setFont(list.getFont());
            context.text = entry.connection().getName();
            context.setFont(list.getFont());
            context.setForeground(selected && focus ? foreground : DatabaseUi.SECONDARY_TEXT);
            setOpaque(selected);
            setBackground(UIUtil.getListBackground(selected, focus));
            setToolTipText(entry.detail());
            getAccessibleContext().setAccessibleName(entry.detail());
            return this;
        }

        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            // The context truncates instead of widening the tool window.
            size.width = getInsets().left + title.getPreferredSize().width + getInsets().right;
            return size;
        }
    }

    /** Muted text that right-aligns and ellipsizes with the metrics of the graphics it paints on. */
    private static final class Context extends JComponent {
        String text = "";

        @Override public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            return new Dimension(metrics.stringWidth(text), metrics.getHeight());
        }

        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                UISettings.setupAntialiasing(g);
                g.setFont(getFont());
                g.setColor(getForeground());
                FontMetrics metrics = g.getFontMetrics();
                String shown = text;
                if (metrics.stringWidth(shown) > getWidth()) {
                    String ellipsis = "\u2026";
                    int end = shown.length();
                    while (end > 0 && metrics.stringWidth(shown.substring(0, end) + ellipsis) > getWidth()) end--;
                    shown = end == 0 ? "" : shown.substring(0, end).stripTrailing() + ellipsis;
                }
                int x = getWidth() - metrics.stringWidth(shown);
                int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(shown, x, y);
            } finally {
                g.dispose();
            }
        }
    }
}
