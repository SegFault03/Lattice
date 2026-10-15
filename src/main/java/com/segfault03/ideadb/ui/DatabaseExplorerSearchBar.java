package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** A search drawer with an exclusive All choice and combinable name filters. */
final class DatabaseExplorerSearchBar extends JPanel {
    private final SearchTextField input = new SearchTextField();
    private final JButton filters = new JButton("All ▾");
    private final JLabel results = new JBLabel("Type to search");
    private final JButton previous, next, close;
    private final Set<DatabaseExplorerSearch.Category> selected = EnumSet.noneOf(DatabaseExplorerSearch.Category.class);
    private final Consumer<DatabaseExplorerSearch> changed;

    DatabaseExplorerSearchBar(Consumer<DatabaseExplorerSearch> changed, Runnable onClose,
                              Runnable onPrevious, Runnable onNext) {
        super(null);
        this.changed = changed;
        setBorder(JBUI.Borders.empty(4, 8));
        getAccessibleContext().setAccessibleName("Explorer search drawer");
        input.getTextEditor().setColumns(16);
        input.getTextEditor().getAccessibleContext().setAccessibleName("Search explorer names");
        input.getTextEditor().putClientProperty("JTextField.placeholderText", "Search names…");
        input.getTextEditor().setToolTipText("Search connected data sources in the current view");
        filters.setFocusable(true);
        filters.setToolTipText("Choose which object names to search");
        filters.getAccessibleContext().setAccessibleName("Search name filters");
        filters.addActionListener(event -> JBPopupFactory.getInstance()
                .createComponentPopupBuilder(createFilters(), null).setTitle("Search in")
                .setRequestFocus(true).setCancelOnClickOutside(true).createPopup().showUnderneathOf(filters));
        results.getAccessibleContext().setAccessibleName("Explorer search results");
        previous = DatabaseUi.action("", AllIcons.Actions.PreviousOccurence, "Previous search match");
        next = DatabaseUi.action("", AllIcons.Actions.NextOccurence, "Next search match");
        close = DatabaseUi.action("", AllIcons.Actions.Close, "Close explorer search");
        previous.addActionListener(event -> onPrevious.run());
        next.addActionListener(event -> onNext.run());
        close.addActionListener(event -> onClose.run());
        for (Component component : new Component[]{input, filters, results, previous, next, close}) add(component);
        DatabaseUi.onTextChange(input.getTextEditor(), this::notifyChange);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", onClose);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "next", onNext);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "previous", onPrevious);
        setResults(0, false, null);
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        input.getTextEditor().getInputMap().put(key, name);
        input.getTextEditor().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    JPanel createFilters() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.getAccessibleContext().setAccessibleName("Explorer search filters");
        panel.setBorder(JBUI.Borders.empty(8, 12));
        JCheckBox all = new JBCheckBox("All", selected.isEmpty());
        panel.add(all);
        Map<DatabaseExplorerSearch.Category, JCheckBox> choices = new EnumMap<>(DatabaseExplorerSearch.Category.class);
        for (DatabaseExplorerSearch.Category category : DatabaseExplorerSearch.Category.values()) {
            JCheckBox choice = new JBCheckBox(category.label, selected.contains(category));
            choices.put(category, choice);
            panel.add(choice);
            choice.addActionListener(event -> {
                if (choice.isSelected()) selected.add(category); else selected.remove(category);
                all.setSelected(selected.isEmpty());
                notifyChange();
            });
        }
        all.addActionListener(event -> {
            selected.clear();
            choices.values().forEach(choice -> choice.setSelected(false));
            all.setSelected(true);
            notifyChange();
        });
        return panel;
    }

    private void notifyChange() {
        filters.setText(selected.isEmpty() ? "All ▾" : selected.size() == 1 ? switch (selected.iterator().next()) {
            case DATA_SOURCE -> "Sources ▾";
            case KEY -> "Keys ▾";
            default -> selected.iterator().next().label + " ▾";
        }
                : selected.size() + " filters ▾");
        filters.setToolTipText(selected.isEmpty() ? "Search all names" : selected.stream().map(category -> category.label)
                .collect(Collectors.joining(", ")));
        revalidate();
        changed.accept(query());
    }

    DatabaseExplorerSearch query() { return new DatabaseExplorerSearch(input.getText(), selected); }
    void setFilters(Set<DatabaseExplorerSearch.Category> categories) {
        selected.clear();
        selected.addAll(categories);
        notifyChange();
    }
    void setText(String text) { input.setText(text); }
    void focusInput() { input.getTextEditor().requestFocusInWindow(); }

    void setResults(int count, boolean loading, String detail) {
        results.setText(query().isActive() ? count + (count == 1 ? " match" : " matches") : "Type to search");
        results.setIcon(loading ? com.intellij.ui.AnimatedIcon.Default.INSTANCE : detail == null ? null : AllIcons.General.Warning);
        results.setToolTipText(detail == null ? (loading ? "Loading metadata from connected sources…" : "Connected sources only")
                : "Some names could not be searched. Refresh the source to retry. " + detail);
        previous.setEnabled(count > 0);
        next.setEnabled(count > 0);
    }

    private int rowHeight() {
        return Math.max(input.getPreferredSize().height, Math.max(filters.getPreferredSize().height, close.getPreferredSize().height));
    }

    int heightForWidth(int width) { return getInsets().top + getInsets().bottom + rowHeight() * (width < JBUI.scale(510) ? 2 : 1); }
    @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(540), heightForWidth(getWidth())); }
    @Override public Dimension getMinimumSize() { return new Dimension(JBUI.scale(220), heightForWidth(getWidth())); }

    @Override public void doLayout() {
        Insets insets = getInsets();
        int x = insets.left, y = insets.top, width = Math.max(0, getWidth() - insets.left - insets.right);
        int gap = JBUI.scale(4), height = rowHeight(), button = close.getPreferredSize().width;
        boolean narrow = getWidth() < JBUI.scale(510);
        if (narrow) {
            input.setBounds(x, y, Math.max(0, width - button - gap), height);
            close.setBounds(x + width - button, y, button, height);
            y += height;
        }
        int filterWidth = Math.min(JBUI.scale(128), filters.getPreferredSize().width);
        int resultWidth = JBUI.scale(86);
        if (!narrow) {
            int inputWidth = Math.max(JBUI.scale(120), width - filterWidth - resultWidth - 3 * button - 5 * gap);
            input.setBounds(x, y, inputWidth, height);
            x += inputWidth + gap;
        }
        filters.setBounds(x, y, filterWidth, height);
        x += filterWidth + gap;
        int right = insets.left + width;
        int navigationWidth = 2 * button + gap + (narrow ? 0 : button + gap);
        results.setBounds(x, y, Math.max(0, right - x - navigationWidth - gap), height);
        x = right - navigationWidth;
        previous.setBounds(x, y, button, height);
        next.setBounds(x + button + gap, y, button, height);
        if (!narrow) close.setBounds(right - button, y, button, height);
    }
}
