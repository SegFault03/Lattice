package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.ui.Icons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/** Independently scrollable source picker shared by the dialog and Swing previews. */
final class DataSourcePanel extends JPanel {
    private final Map<DatabaseType, JToggleButton> choices = new EnumMap<>(DatabaseType.class);

    DataSourcePanel(Consumer<DatabaseType> select) {
        super(new BorderLayout(0, JBUI.scale(12)));
        setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLineRight(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR),
                JBUI.Borders.empty(16, 8, 8, 12)));
        JLabel title = new JLabel("Data source");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);
        JPanel rows = new JPanel(new GridBagLayout());
        ButtonGroup group = new ButtonGroup();
        int row = 0;
        for (DatabaseType type : DatabaseType.values()) {
            JToggleButton button = new JToggleButton(type.getDisplayName(),
                    Icons.dataSource(type, 24)) {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(getBackground());
                    g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(8), JBUI.scale(8));
                    g.setColor(isSelected() ? new JBColor(0x5885C8, 0x739DD8)
                            : JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(8), JBUI.scale(8));
                    g.dispose();
                    super.paintComponent(graphics);
                }
            };
            button.setContentAreaFilled(false);
            button.setOpaque(false);
            button.setBorder(JBUI.Borders.empty(8));
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setIconTextGap(JBUI.scale(8));
            button.setMargin(JBUI.insets(10, 10));
            button.setPreferredSize(JBUI.size(136, 52));
            button.getAccessibleContext().setAccessibleName(type.getDisplayName());
            button.setToolTipText("Connect to " + type.getDisplayName());
            button.addActionListener(e -> select.accept(type));
            group.add(button);
            choices.put(type, button);
            GridBagConstraints cell = new GridBagConstraints();
            cell.gridx = 0;
            cell.gridy = row++;
            cell.weightx = 1;
            cell.fill = GridBagConstraints.HORIZONTAL;
            cell.insets = JBUI.insetsBottom(8);
            rows.add(button, cell);
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        rows.add(Box.createVerticalGlue(), filler);
        JBScrollPane scroll = new JBScrollPane(rows,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setPreferredSize(JBUI.size(144, 240));
        scroll.setMinimumSize(JBUI.size(144, 80));
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(24));
        scroll.getAccessibleContext().setAccessibleName("Data source");
        add(scroll, BorderLayout.CENTER);
    }

    void setSelected(DatabaseType type) {
        choices.forEach((source, button) -> {
            boolean selected = source == type;
            button.setSelected(selected);
            button.setBackground(selected
                    ? new JBColor(0xE8F0FE, 0x2D4263)
                    : javax.swing.UIManager.getColor("Panel.background"));
            button.setForeground(selected
                    ? new JBColor(0x174A8B, 0xFFFFFF)
                    : javax.swing.UIManager.getColor("Label.foreground"));
        });
    }
}
