package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.DatabaseType;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.function.Consumer;
import java.beans.PropertyChangeListener;
import java.awt.event.ActionEvent;

/** Shared spacing and quiet native action styling for database editor controls. */
public final class DatabaseUi {
    public static final Color POSITIVE_ACTION_COLOR = new JBColor(new Color(0x2E7D32), new Color(0x238636));
    public static final Color DESTRUCTIVE_ACTION_COLOR = new JBColor(new Color(0xC62828), new Color(0xC43D3D));

    private DatabaseUi() {}

    /** Native IDEA popup painting, palette, borders and window handling in every theme. */
    public static JPopupMenu popupMenu() { return new com.intellij.openapi.ui.JBPopupMenu(); }

    /** Match JBPopupMenu's surface/window settings for Swing's internally owned submenus. */
    public static JMenu submenu(String text) {
        return new JMenu(text) {
            @Override public JPopupMenu getPopupMenu() {
                JPopupMenu popup = super.getPopupMenu();
                // IDEA's popup UI paints the theme surface; Swing's opaque fill can
                // cover it with a black rectangle in native light themes.
                popup.setOpaque(false);
                popup.setLightWeightPopupEnabled(false);
                return popup;
            }
        };
    }

    /** Every connection entry point offers the same database choice. */
    public static JPopupMenu connectionMenu(Consumer<DatabaseType> choose) {
        JPopupMenu menu = popupMenu();
        for (DatabaseType type : DatabaseType.values()) {
            JMenuItem item = new JMenuItem(type.getDisplayName() + "…", Icons.dataSource(type, 16));
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
            case BUSY -> com.intellij.ui.AnimatedIcon.Default.INSTANCE;
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
        JButton button = new ActionButton(text, icon);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        configureActionSizing(button);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setDefaultCapable(false);
        button.setFocusPainted(false); // The focus cue is painted with the native theme color.
        button.setRolloverEnabled(true);
        installFocusVisibility(button);
        return button;
    }

    /** Shared geometry for table toolbar and inspector actions, independent of their paint. */
    static void configureActionSizing(AbstractButton button) {
        button.setBorder(JBUI.Borders.empty(4, 6));
        // Darcula subtracts both border insets and the Swing button margin.
        // The border supplies our explicit padding; do not count that margin twice.
        button.setMargin(new Insets(0, 0, 0, 0));
        // IDEA reapplies its native four-pixel gap on every theme change.
        button.setIconTextGap(JBUI.scale(4));
    }

    static Dimension actionPreferredSize(AbstractButton button) {
        Dimension content = javax.swing.plaf.basic.BasicGraphicsUtils.getPreferredButtonSize(button, button.getIconTextGap());
        return new Dimension(Math.max(JBUI.scale(28), content.width), Math.max(JBUI.scale(28), content.height));
    }

    /** Dialog actions include all padding required by the active native button UI. */
    public static JButton dialogAction(String text, Icon icon, String tooltip) {
        JButton button = new DialogButton(text, icon);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.setIconTextGap(JBUI.scale(5));
        return button;
    }

    /** Quiet inspector actions still reserve the text area required by the native painter. */
    public static JButton inspectorAction(String text, Icon icon, String tooltip) {
        ActionButton button = (ActionButton) action(text, icon, tooltip);
        button.nativeTextSizing = true;
        return button;
    }

    /** Mouse interaction stays quiet; Tab/Shift+Tab and keyboard interaction show focus. */
    static void installFocusVisibility(JComponent component) {
        installMouseFocus(component, component);
        for (Component child : component.getComponents())
            if (child instanceof JButton button) installMouseFocus(button, component);
        if (Boolean.TRUE.equals(component.getClientProperty("lattice.focusVisibilityInstalled"))) return;
        component.putClientProperty("lattice.focusVisibilityInstalled", Boolean.TRUE);
        component.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent event) {
                if (event.getCause() == java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD
                        || event.getCause() == java.awt.event.FocusEvent.Cause.TRAVERSAL_BACKWARD
                        || event.getCause() == java.awt.event.FocusEvent.Cause.TRAVERSAL_UP
                        || event.getCause() == java.awt.event.FocusEvent.Cause.TRAVERSAL_DOWN)
                    component.putClientProperty("lattice.mouseFocus", Boolean.FALSE);
                component.repaint();
            }
            @Override public void focusLost(java.awt.event.FocusEvent event) { component.repaint(); }
        });
        component.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent event) {
                component.putClientProperty("lattice.mouseFocus", Boolean.FALSE);
                component.repaint();
            }
        });
    }

    private static void installMouseFocus(JComponent target, JComponent component) {
        if (target.getClientProperty("lattice.mouseFocusOwner") == component) return;
        target.putClientProperty("lattice.mouseFocusOwner", component);
        target.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent event) {
                component.putClientProperty("lattice.mouseFocus", Boolean.TRUE);
                component.repaint();
            }
        });
    }

    static boolean isFocusVisible(JComponent component) {
        return !Boolean.TRUE.equals(component.getClientProperty("lattice.mouseFocus"));
    }

    static void onTextChange(javax.swing.text.JTextComponent input, Runnable update) {
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent event) { update.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent event) { update.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent event) { update.run(); }
        });
    }

    /** Highlights an enabled toolbar action while leaving disabled actions in the native quiet style. */
    public static void setActionEmphasis(JButton button, Color color) {
        if (button instanceof ActionButton actionButton) actionButton.setEmphasisColor(color);
        if (button instanceof DialogButton dialogButton) dialogButton.setEmphasisColor(color);
    }

    /** Native sizing, focus ring, border and surface geometry shared with Cancel/Close. */
    private static final class DialogButton extends JButton {
        private Icon plainIcon, emphasizedIcon;
        private Color emphasisColor;

        DialogButton(String text, Icon icon) { super(text, icon); plainIcon = icon; setOpaque(false); }

        private void setEmphasisColor(Color color) {
            if (Objects.equals(emphasisColor, color)) return;
            emphasisColor = color;
            emphasizedIcon = color == null || plainIcon == null ? plainIcon : IconUtil.filterIcon(plainIcon,
                    () -> new java.awt.image.RGBImageFilter() {
                        @Override public int filterRGB(int x, int y, int rgb) { return (rgb & 0xFF000000) | 0x00FFFFFF; }
                    }, null);
            putClientProperty("lattice.action.emphasis", color);
            refreshAppearance();
        }

        @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); refreshAppearance(); }
        @Override public void updateUI() { super.updateUI(); refreshAppearance(); }
        @Override public Color getForeground() {
            return isEnabled() && emphasisColor != null ? Color.WHITE : super.getForeground();
        }

        private void refreshAppearance() {
            setOpaque(false); // The native UI paints the rounded surface inside its focus-ring bounds.
            boolean emphasized = isEnabled() && emphasisColor != null;
            // Supported native theme properties preserve the same painted bounds as Cancel.
            putClientProperty("JButton.backgroundColor", emphasized ? emphasisColor : null);
            putClientProperty("JButton.borderColor", emphasized ? emphasisColor.darker() : null);
            setBackground(emphasized ? emphasisColor : UIManager.getColor("Button.background"));
            if (plainIcon != null) setIcon(emphasized ? emphasizedIcon : plainIcon);
            repaint();
        }
    }

    /** A popup view of an existing action; state and callbacks still belong to that action. */
    static JButton mirrorAction(JButton source) {
        Icon icon = source instanceof ActionButton action ? action.defaultIcon : source.getIcon();
        ActionButton copy = (ActionButton) action(source.getText(), icon, source.getToolTipText());
        copy.source = source;
        copy.iconOnly = source.getIcon() != null && source.getText() != null && !source.getText().isEmpty();
        copy.copySourceState();
        copy.addActionListener(event -> {
            if (!source.isEnabled()) return;
            ActionEvent forwarded = new ActionEvent(copy, event.getID(), event.getActionCommand(), event.getWhen(), event.getModifiers());
            for (var listener : source.getActionListeners()) listener.actionPerformed(forwarded);
        });
        return copy;
    }

    private static final class ActionButton extends JButton {
        private final Icon defaultIcon;
        private Color emphasisColor;
        private JButton source;
        private boolean iconOnly;
        private boolean nativeTextSizing;
        private Icon disabledGlyph;
        private Icon disabledSource;
        private Color disabledColor;
        private final PropertyChangeListener sourceChanges = event -> copySourceState();

        private ActionButton(String text, Icon icon) {
            super(text, icon);
            defaultIcon = icon;
        }

        @Override public Icon getDisabledIcon() {
            Icon icon = defaultIcon == null ? getIcon() : defaultIcon;
            if (icon == null) return null;
            Color color = UIManager.getColor("Button.disabledText");
            if (color == null) color = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY);
            if (disabledGlyph == null || disabledSource != icon || !Objects.equals(disabledColor, color)) {
                int rgb = color.getRGB() & 0x00FFFFFF;
                disabledGlyph = IconUtil.filterIcon(icon, () -> new java.awt.image.RGBImageFilter() {
                    @Override public int filterRGB(int x, int y, int source) {
                        return (source & 0xFF000000) | rgb;
                    }
                }, null);
                disabledSource = icon;
                disabledColor = color;
            }
            return disabledGlyph;
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (!enabled && getModel() != null) clearInteractionState();
        }

        private void clearInteractionState() {
            getModel().setArmed(false);
            getModel().setPressed(false);
            getModel().setRollover(false);
        }

        private void copySourceState() {
            if (source == null) return;
            setText(iconOnly ? "" : source.getText());
            setEnabled(source.isEnabled());
            setSelected(source.isSelected());
            setBorder(iconOnly ? JBUI.Borders.empty(4, 6) : source.getBorder());
            setIconTextGap(source.getIconTextGap());
            setToolTipText(source.getToolTipText());
            getAccessibleContext().setAccessibleName(source.getAccessibleContext().getAccessibleName());
            setEmphasisColor(source instanceof ActionButton action ? action.emphasisColor : null);
        }

        @Override public void addNotify() {
            super.addNotify();
            if (source != null) {
                source.addPropertyChangeListener(sourceChanges);
                copySourceState();
            }
        }

        @Override public void removeNotify() {
            if (source != null) source.removePropertyChangeListener(sourceChanges);
            clearInteractionState();
            super.removeNotify();
        }

        private void setEmphasisColor(Color color) {
            if (Objects.equals(emphasisColor, color)) return;
            emphasisColor = color;
            setBackground(color);
            setForeground(color == null ? null : Color.WHITE);
            // Colorize preserves source brightness: gray glyphs would stay gray on white.
            // Filter only RGB, keeping the native icon's alpha, scale and antialiasing.
            setIcon(color == null || defaultIcon == null ? defaultIcon : IconUtil.filterIcon(defaultIcon,
                    () -> new java.awt.image.RGBImageFilter() {
                        @Override public int filterRGB(int x, int y, int rgb) {
                            return (rgb & 0xFF000000) | 0x00FFFFFF;
                        }
                    }, null));
            putClientProperty("lattice.action.emphasis", color);
            repaint();
        }

        @Override public Dimension getPreferredSize() {
            // Toolbar actions use compact content sizes; dialog actions are native JButtons.
            Dimension content = actionPreferredSize(this);
            if (nativeTextSizing) {
                Dimension nativeSize = super.getPreferredSize();
                content.width = Math.max(content.width, nativeSize.width);
                content.height = Math.max(content.height, nativeSize.height);
            }
            return content;
        }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics graphics) {
            ButtonModel model = getModel();
            boolean emphasized = isEnabled() && emphasisColor != null;
            boolean interactive = isEnabled() && (model.isSelected() || model.isRollover() || model.isPressed() && model.isArmed());
            boolean focusVisible = hasFocus() && isFocusVisible(this);
            if (emphasized || interactive || isEnabled() && focusVisible) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (emphasized || interactive) {
                        g.setColor(emphasized
                                ? emphasisColor
                                : model.isPressed() && model.isArmed()
                                        ? JBUI.CurrentTheme.ActionButton.pressedBackground()
                                        : JBUI.CurrentTheme.ActionButton.hoverBackground());
                        g.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(10), JBUI.scale(10));
                    }
                    if (emphasized) {
                        g.setColor(emphasisColor.darker());
                        g.setStroke(new BasicStroke(com.intellij.ui.scale.JBUIScale.scale(1f)));
                        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, JBUI.scale(10), JBUI.scale(10));
                    }
                    if (focusVisible) {
                        g.setColor(JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x548AF7)));
                        g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, JBUI.scale(6), JBUI.scale(6));
                    }
                } finally { g.dispose(); }
            }
            super.paintComponent(graphics);
        }
    }

    /** An indivisible group so wrapping never separates a field from its label. */
    public static JPanel group(Component... controls) {
        return group(4, controls);
    }

    /** An indivisible group with a caller-selected horizontal gap for compact toolbars. */
    public static JPanel group(int horizontalGap, Component... controls) {
        FlowLayout layout = new FlowLayout(FlowLayout.LEFT, JBUI.scale(horizontalGap), 0);
        layout.setAlignOnBaseline(true);
        JPanel group = new JPanel(layout);
        group.setOpaque(false);
        for (Component control : controls) group.add(control);
        return group;
    }

    /** Keeps the label visible while allowing the field to shrink in a narrow editor. */
    public static JPanel labeledInput(JLabel label, JComponent input) {
        JPanel group = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        group.setOpaque(false);
        label.setLabelFor(input);
        group.add(label, BorderLayout.WEST);
        group.add(input, BorderLayout.CENTER);
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
