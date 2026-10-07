package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Shared spacing and quiet native action styling for database editor controls. */
public final class DatabaseUi {
    private DatabaseUi() {}

    /** Every connection entry point offers the same database choice. */
    public static JPopupMenu connectionMenu(Consumer<DatabaseType> choose) {
        JPopupMenu menu = new JPopupMenu();
        for (DatabaseType type : DatabaseType.values()) {
            JMenuItem item = new JMenuItem(type.getDisplayName() + "…");
            item.addActionListener(event -> choose.accept(type));
            menu.add(item);
        }
        return menu;
    }

    public enum Tone { NORMAL, BUSY, SUCCESS, WARNING, ERROR }

    /** Text and native glyphs keep status meaning clear without relying on color. */
    public static void status(JLabel label, String text, Tone tone) {
        label.setText(text);
        label.setToolTipText(text);
        label.getAccessibleContext().setAccessibleDescription(text);
        label.setIcon(switch (tone) {
            case NORMAL -> null;
            case BUSY -> com.intellij.icons.AllIcons.Process.Step_passive;
            case SUCCESS -> com.intellij.icons.AllIcons.General.GreenCheckmark;
            case WARNING -> com.intellij.icons.AllIcons.General.Warning;
            case ERROR -> com.intellij.icons.AllIcons.General.Error;
        });
        label.setForeground(switch (tone) {
            case ERROR -> JBColor.namedColor("Label.errorForeground", new JBColor(0xB83232, 0xFF8282));
            case WARNING -> JBColor.namedColor("Label.warningForeground", new JBColor(0x88600C, 0xE5BA6A));
            default -> javax.swing.UIManager.getColor("Label.foreground");
        });
    }

    public static boolean confirmDestructive(com.intellij.openapi.project.Project project,
                                             String title, String message, String action) {
        return com.intellij.openapi.ui.Messages.showYesNoDialog(project, message, title, action, "Cancel",
                com.intellij.openapi.ui.Messages.getWarningIcon()) == com.intellij.openapi.ui.Messages.YES;
    }

    public static JButton action(String text, Icon icon, String tooltip) {
        JButton button = new JButton(text, icon) {
            @Override public Dimension getPreferredSize() {
                // Size toolbar actions to content, bypassing native dialog-button minimum widths.
                Insets padding = getInsets();
                FontMetrics font = getFontMetrics(getFont());
                String label = getText();
                Icon glyph = getIcon();
                int textWidth = label == null || label.isEmpty() ? 0 : font.stringWidth(label);
                int iconWidth = glyph == null ? 0 : glyph.getIconWidth();
                int gap = textWidth > 0 && iconWidth > 0 ? getIconTextGap() : 0;
                return new Dimension(Math.max(JBUI.scale(28), padding.left + padding.right + textWidth + iconWidth + gap),
                        Math.max(JBUI.scale(28), padding.top + padding.bottom
                                + Math.max(textWidth > 0 ? font.getHeight() : 0, glyph == null ? 0 : glyph.getIconHeight())));
            }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
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
        button.setOpaque(false);
        button.setDefaultCapable(false);
        button.setFocusPainted(false); // The focus cue is painted above with the native theme color.
        button.setRolloverEnabled(true);
        button.setIconTextGap(JBUI.scale(5));
        return button;
    }

    /** An indivisible group so wrapping never separates a field from its label. */
    public static JPanel group(Component... controls) {
        FlowLayout layout = new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0);
        layout.setAlignOnBaseline(true);
        JPanel group = new JPanel(layout);
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
