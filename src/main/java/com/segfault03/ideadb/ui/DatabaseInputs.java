package com.segfault03.ideadb.ui;

import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.table.TableCellEditor;
import javax.swing.event.CellEditorListener;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.function.Consumer;
import java.util.EventObject;

/** Compact single-line inputs with shared geometry; native editors and popups remain intact. */
public final class DatabaseInputs {
    private DatabaseInputs() {}
    private interface SizedInput {}

    public static JBTextField textField() { return new InputTextField("", 0); }
    public static JBTextField textField(String text) { return new InputTextField(text, 0); }
    public static JBTextField textField(int columns) { return new InputTextField("", columns); }
    public static JPasswordField passwordField() { return new InputPasswordField(); }
    public static <E> JComboBox<E> comboBox() { return new InputComboBox<>(); }
    public static <E> JComboBox<E> comboBox(E[] items) { return new InputComboBox<>(items); }
    public static TextFieldWithBrowseButton browseField() { return new InputBrowseField(); }

    public static void styleTableEditors(JTable table) {
        for (Class<?> type : new Class<?>[]{Object.class, Number.class}) {
            TableCellEditor editor = table.getDefaultEditor(type);
            if (editor != null) table.setDefaultEditor(type, new InputCellEditor(editor));
        }
    }

    public static int height(JComponent input) {
        Font font = input.getFont();
        int text = font == null ? 0 : input.getFontMetrics(font).getHeight();
        return Math.max(JBUI.scale(26), text + JBUI.scale(6));
    }

    private static Dimension inputSize(JComponent input, Dimension size) {
        return new Dimension(size.width, height(input));
    }

    /** Also styles native text editors supplied by JTable without changing conversion/validation. */
    public static <T extends JComponent> T style(T input) {
        Font font = UIManager.getFont("TextField.font");
        if (font != null && (input.getFont() == null || input.getFont() instanceof javax.swing.plaf.UIResource))
            input.setFont(font);
        input.setBorder(input instanceof TextFieldWithBrowseButton ? JBUI.Borders.empty() : new InputBorder());
        if (input instanceof JTextField field) {
            field.setMargin(JBUI.emptyInsets());
            // Darcula adds its own margins even when JTextField.margin is zero.
            // Let the shared border provide the same text origin as password fields.
            field.putClientProperty("TextFieldWithoutMargins", Boolean.TRUE);
        }
        if (input instanceof JComboBox<?> combo) configureCombo(combo);
        if (!(input instanceof SizedInput)) {
            input.setPreferredSize(inputSize(input, input.getPreferredSize()));
            input.setMinimumSize(inputSize(input, input.getMinimumSize()));
        }
        return input;
    }

    private static void configureCombo(JComboBox<?> combo) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setVerticalAlignment(SwingConstants.CENTER);
                putClientProperty("html.disable", Boolean.TRUE);
                // The closed control supplies its padding; popup rows keep comfortable spacing.
                setBorder(index < 0 ? JBUI.Borders.empty() : JBUI.Borders.empty(4, 8));
                return this;
            }
        });
        if (combo.isEditable() && combo.getEditor().getEditorComponent() instanceof JTextField field) {
            field.setFont(combo.getFont());
            field.setBorder(JBUI.Borders.empty());
            field.setMargin(JBUI.emptyInsets());
            field.putClientProperty("TextFieldWithoutMargins", Boolean.TRUE);
        }
    }

    private static void paintRounded(JComponent input, Graphics graphics, Consumer<Graphics> painter) {
        Graphics2D copy = (Graphics2D)graphics.create();
        try {
            copy.clip(new RoundRectangle2D.Float(0, 0, input.getWidth(), input.getHeight(), JBUI.scale(8), JBUI.scale(8)));
            painter.accept(copy);
        } finally { copy.dispose(); }
    }

    private static boolean focused(Component input) {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return owner != null && (owner == input || input instanceof Container parent && SwingUtilities.isDescendingFrom(owner, parent));
    }

    private static Object outline(JComponent input) {
        Object value = input.getClientProperty("JComponent.outline");
        if (value == null && input instanceof TextFieldWithBrowseButton browse)
            value = browse.getTextField().getClientProperty("JComponent.outline");
        if (value == null && input instanceof JComboBox<?> combo && combo.isEditable()
                && combo.getEditor().getEditorComponent() instanceof JComponent editor)
            value = editor.getClientProperty("JComponent.outline");
        return value;
    }

    private static final class InputBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component input) { return JBUI.insets(3, 8); }
        @Override public Insets getBorderInsets(Component input, Insets insets) {
            Insets padding = getBorderInsets(input);
            insets.set(padding.top, padding.left, padding.bottom, padding.right);
            return insets;
        }
        @Override public void paintBorder(Component input, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D)graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float inset = JBUI.scale(1f);
                RoundRectangle2D shape = new RoundRectangle2D.Float(x + inset, y + inset,
                        width - 2 * inset, height - 2 * inset, JBUI.scale(8), JBUI.scale(8));
                Area corners = new Area(new Rectangle(x, y, width, height));
                corners.subtract(new Area(shape));
                g.setColor(input.getParent() == null ? input.getBackground() : input.getParent().getBackground());
                g.fill(corners);
                String state = input instanceof JComponent component ? String.valueOf(outline(component)) : "";
                boolean focus = focused(input);
                Color color = "error".equalsIgnoreCase(state) ? JBColor.namedColor("Component.errorFocusColor", new JBColor(0xD94B4B, 0xF07878))
                        : "warning".equalsIgnoreCase(state) ? JBColor.namedColor("Component.warningFocusColor", new JBColor(0xC98B23, 0xD9A343))
                        : focus ? JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x548AF7))
                        : JBColor.namedColor("Component.borderColor", new JBColor(0xB8BDC6, 0x646872));
                g.setColor(input.isEnabled() ? color : JBColor.namedColor("Component.disabledBorderColor", JBColor.GRAY));
                g.setStroke(new BasicStroke(JBUI.scale(focus ? 2f : 1f)));
                g.draw(shape);
            } finally { g.dispose(); }
        }
    }

    private static final class InputTextField extends JBTextField implements SizedInput {
        private boolean initialized;
        InputTextField(String text, int columns) {
            super();
            setText(text);
            setColumns(columns);
            style(this);
            initialized = true;
        }
        @Override public void setUI(javax.swing.plaf.TextUI ui) {
            super.setUI(ui);
            if (initialized) style(this);
        }
        @Override public Dimension getPreferredSize() { return inputSize(this, super.getPreferredSize()); }
        @Override public Dimension getMinimumSize() { return inputSize(this, super.getMinimumSize()); }
        @Override public void paint(Graphics graphics) { paintRounded(this, graphics, super::paint); }
    }

    private static final class InputPasswordField extends JPasswordField implements SizedInput {
        private boolean initialized;
        InputPasswordField() { style(this); initialized = true; }
        @Override public void setUI(javax.swing.plaf.TextUI ui) {
            super.setUI(ui);
            // Darcula reinstalls its own margin even with a non-UIResource border.
            if (initialized) style(this);
        }
        @Override public Dimension getPreferredSize() { return inputSize(this, super.getPreferredSize()); }
        @Override public Dimension getMinimumSize() { return inputSize(this, super.getMinimumSize()); }
        @Override public void paint(Graphics graphics) { paintRounded(this, graphics, super::paint); }
    }

    private static final class InputComboBox<E> extends JComboBox<E> implements SizedInput {
        private boolean initialized;
        InputComboBox() { initialize(); }
        InputComboBox(E[] items) { super(items); initialize(); }
        private void initialize() {
            style(this);
            initialized = true;
            addPropertyChangeListener("editable", event -> configureCombo(this));
            addPropertyChangeListener("editor", event -> configureCombo(this));
        }
        @Override public void updateUI() {
            super.updateUI();
            if (initialized) style(this);
        }
        @Override public Dimension getPreferredSize() { return inputSize(this, super.getPreferredSize()); }
        @Override public Dimension getMinimumSize() { return inputSize(this, super.getMinimumSize()); }
        @Override public void paint(Graphics graphics) { paintRounded(this, graphics, super::paint); }
    }

    private static final class InputBrowseField extends TextFieldWithBrowseButton implements SizedInput {
        InputBrowseField() {
            super(DatabaseInputs.textField());
            ((BorderLayout)getLayout()).setHgap(JBUI.scale(6));
            setOpaque(false);
            setButtonIcon(com.intellij.icons.AllIcons.Nodes.Folder);
            JButton browseButton = null;
            for (Component child : getComponents()) {
                if (child instanceof JButton button) browseButton = button;
            }
            if (browseButton == null) throw new IllegalStateException("Missing native browse button");
            browseButton.setRolloverIcon(com.intellij.icons.AllIcons.Nodes.Folder);
            browseButton.setBorder(new InputBorder());
            browseButton.setContentAreaFilled(true);
            browseButton.setFocusPainted(false);
            browseButton.setToolTipText("Browse files…");
            browseButton.getAccessibleContext().setAccessibleName("Browse files");
            style(this);
            getTextField().setFont(getFont());
            addPropertyChangeListener("font", event -> getTextField().setFont(getFont()));
            FocusAdapter focus = new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { repaint(); }
                @Override public void focusLost(FocusEvent event) { repaint(); }
            };
            getTextField().addFocusListener(focus);
            browseButton.addFocusListener(focus);
            getTextField().addPropertyChangeListener("JComponent.outline", event -> repaint());
        }
        @Override public Dimension getPreferredSize() { return inputSize(this, super.getPreferredSize()); }
        @Override public Dimension getMinimumSize() { return inputSize(this, super.getMinimumSize()); }
        @Override public void paint(Graphics graphics) { paintRounded(this, graphics, super::paint); }
    }

    /** Preserve JTable's typed value conversion and its editor lifecycle. */
    private static final class InputCellEditor implements TableCellEditor {
        private final TableCellEditor delegate;
        private JComponent input;
        InputCellEditor(TableCellEditor delegate) { this.delegate = delegate; }
        @Override public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
            Component component = delegate.getTableCellEditorComponent(table, value, selected, row, column);
            if (component instanceof JTextField field) {
                input = field;
                input.putClientProperty("JComponent.outline", null);
                style(input);
            } else input = null;
            return component;
        }
        @Override public Object getCellEditorValue() { return delegate.getCellEditorValue(); }
        @Override public boolean isCellEditable(EventObject event) { return delegate.isCellEditable(event); }
        @Override public boolean shouldSelectCell(EventObject event) { return delegate.shouldSelectCell(event); }
        @Override public boolean stopCellEditing() {
            boolean stopped = delegate.stopCellEditing();
            if (!stopped && input != null) {
                input.putClientProperty("JComponent.outline", "error");
                style(input);
                input.repaint();
            }
            return stopped;
        }
        @Override public void cancelCellEditing() { delegate.cancelCellEditing(); }
        @Override public void addCellEditorListener(CellEditorListener listener) { delegate.addCellEditorListener(listener); }
        @Override public void removeCellEditorListener(CellEditorListener listener) { delegate.removeCellEditorListener(listener); }
    }
}
