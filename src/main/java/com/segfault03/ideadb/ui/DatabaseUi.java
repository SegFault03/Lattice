package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/** Shared spacing and quiet native action styling for database editor controls. */
public final class DatabaseUi {
    private DatabaseUi() {}

    public static JButton action(String text, Icon icon, String tooltip) {
        JButton button = new JButton(text, icon) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                size.height = Math.max(size.height, JBUI.scale(28));
                size.width = Math.max(size.width, JBUI.scale(28));
                return size;
            }
            @Override protected void paintComponent(Graphics graphics) {
                ButtonModel model = getModel();
                if (isEnabled() && (model.isRollover() || model.isPressed() && model.isArmed() || hasFocus())) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(model.isPressed() && model.isArmed()
                                ? JBUI.CurrentTheme.ActionButton.pressedBackground()
                                : JBUI.CurrentTheme.ActionButton.hoverBackground());
                        g.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(6), JBUI.scale(6));
                        if (hasFocus()) {
                            g.setColor(JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x548AF7)));
                            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, JBUI.scale(6), JBUI.scale(6));
                        }
                    } finally { g.dispose(); }
                }
                super.paintComponent(graphics);
            }
        };
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setBorder(JBUI.Borders.empty(4, 6));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false); // The focus cue is painted above with the native theme color.
        button.setRolloverEnabled(true);
        button.setIconTextGap(JBUI.scale(5));
        return button;
    }

    /** An indivisible group so wrapping never separates a field from its label. */
    public static JPanel group(Component... controls) {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        group.setOpaque(false);
        for (Component control : controls) group.add(control);
        return group;
    }

    public static JComponent separator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL) {
            @Override protected void paintComponent(Graphics graphics) {
                graphics.setColor(getForeground());
                graphics.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        separator.setPreferredSize(JBUI.size(1, 20));
        separator.setForeground(JBUI.CurrentTheme.ActionButton.SEPARATOR_COLOR);
        return separator;
    }

    public static JPanel section(String text) {
        JPanel section = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        section.setOpaque(false);
        section.setBorder(JBUI.Borders.empty(8, 0, 6, 0));
        JLabel title = new JLabel(text);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        section.add(title, BorderLayout.WEST);
        JPanel rule = new JPanel(new GridBagLayout());
        rule.setOpaque(false);
        JSeparator line = new JSeparator();
        GridBagConstraints fill = new GridBagConstraints();
        fill.fill = GridBagConstraints.HORIZONTAL;
        fill.weightx = 1;
        rule.add(line, fill);
        section.add(rule, BorderLayout.CENTER);
        return section;
    }
}
