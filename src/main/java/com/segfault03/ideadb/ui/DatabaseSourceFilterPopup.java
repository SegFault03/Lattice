package com.segfault03.ideadb.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Draft checkbox selection; only Apply changes the explorer's view. */
final class DatabaseSourceFilterPopup extends JPanel {
    private final JCheckBox all = new JBCheckBox("All data sources");
    private final Map<DatabaseType, JCheckBox> choices = new EnumMap<>(DatabaseType.class);
    private final JButton apply = new JButton("Apply");

    DatabaseSourceFilterPopup(List<DatabaseType> types, Set<DatabaseType> visibleTypes,
                              Consumer<Set<DatabaseType>> onApply, Runnable onCancel) {
        super(new BorderLayout(0, JBUI.scale(8)));
        setBorder(JBUI.Borders.empty(10, 12));
        add(all, BorderLayout.NORTH);
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        for (DatabaseType type : types) {
            JCheckBox checkbox = new JBCheckBox();
            checkbox.setSelected(visibleTypes.contains(type));
            checkbox.getAccessibleContext().setAccessibleName(type.getDisplayName());
            choices.put(type, checkbox);
            JLabel label = new JBLabel(type.getDisplayName(), Icons.dataSource(type, 16), SwingConstants.LEADING);
            label.setLabelFor(checkbox);
            label.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) checkbox.doClick();
                }
            });
            JPanel row = new JPanel(new BorderLayout(JBUI.scale(4), 0));
            row.setBorder(JBUI.Borders.empty(3, 0));
            row.add(checkbox, BorderLayout.WEST);
            row.add(label, BorderLayout.CENTER);
            rows.add(row);
            checkbox.addActionListener(event -> updateSelection());
        }
        JBScrollPane scroll = new JBScrollPane(rows);
        scroll.setBorder(JBUI.Borders.customLine(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR, 1, 0, 1, 0));
        scroll.setPreferredSize(new Dimension(JBUI.scale(240),
                Math.min(JBUI.scale(240), rows.getPreferredSize().height + JBUI.scale(8))));
        add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> onCancel.run());
        actions.add(cancel);
        actions.add(apply);
        add(actions, BorderLayout.SOUTH);
        all.addActionListener(event -> {
            choices.values().forEach(checkbox -> checkbox.setSelected(all.isSelected()));
            updateSelection();
        });
        apply.addActionListener(event -> {
            Set<DatabaseType> selected = EnumSet.noneOf(DatabaseType.class);
            choices.forEach((type, checkbox) -> { if (checkbox.isSelected()) selected.add(type); });
            if (!selected.isEmpty()) onApply.accept(all.isSelected() ? EnumSet.allOf(DatabaseType.class) : selected);
        });
        updateSelection();
    }

    private void updateSelection() {
        all.setSelected(!choices.isEmpty() && choices.values().stream().allMatch(AbstractButton::isSelected));
        apply.setEnabled(choices.values().stream().anyMatch(AbstractButton::isSelected));
    }

    static void show(Component anchor, List<DatabaseType> types, Set<DatabaseType> visibleTypes,
                     Consumer<Set<DatabaseType>> onApply, Disposable parent) {
        JBPopup[] popup = new JBPopup[1];
        DatabaseSourceFilterPopup content = new DatabaseSourceFilterPopup(types, visibleTypes, selected -> {
            popup[0].closeOk(null);
            onApply.accept(selected);
        }, () -> popup[0].cancel());
        popup[0] = JBPopupFactory.getInstance().createComponentPopupBuilder(content, content.all)
                .setTitle("View only").setRequestFocus(true).setCancelOnClickOutside(true)
                .setCancelOnWindowDeactivation(true).createPopup();
        Disposer.register(parent, popup[0]);
        popup[0].showUnderneathOf(anchor);
    }
}
