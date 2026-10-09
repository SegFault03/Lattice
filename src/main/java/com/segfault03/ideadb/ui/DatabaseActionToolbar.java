package com.segfault03.ideadb.ui;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Native IDEA action overflow, including its hover-expanded single-row toolbar. */
public final class DatabaseActionToolbar {
    private DatabaseActionToolbar() {}

    /** Null entries are native toolbar separators. Existing controls retain their production listeners. */
    public static JComponent create(JComponent target, String title, Object... controls) {
        DefaultActionGroup group = new DefaultActionGroup();
        for (Object control : controls) {
            if (control == null) group.addSeparator();
            else if (control instanceof AnAction action) group.add(action);
            else if (control instanceof JComponent component) group.add(new ControlAction(component, null));
            else throw new IllegalArgumentException("Unsupported toolbar control: " + control);
        }
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("Lattice." + title, group, true);
        toolbar.setTargetComponent(target);
        toolbar.setLayoutStrategy(ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY);
        toolbar.setReservePlaceAutoPopupIcon(true);
        JComponent component = toolbar.getComponent();
        component.setBorder(JBUI.Borders.empty(4, 8));
        component.getAccessibleContext().setAccessibleName(title);
        return component;
    }

    public static AnAction picker(String label, JComboBox<?> combo) {
        return new ControlAction(combo, label);
    }

    static final class ControlAction extends DumbAwareAction implements CustomComponentAction {
        private final JComponent source;
        private final String label;

        ControlAction(JComponent source, String label) {
            super(source instanceof JButton button ? button.getText() : label, source.getToolTipText(),
                    source instanceof JButton button ? button.getIcon() : null);
            this.source = source;
            this.label = label;
        }

        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent event) { updatePresentation(event.getPresentation()); }

        void updatePresentation(Presentation presentation) {
            presentation.setEnabled(source.isEnabled());
            if (source instanceof JButton button) presentation.setIcon(button.getIcon());
        }
        @Override public void actionPerformed(AnActionEvent event) {
            if (source instanceof JButton button) button.doClick();
            else source.requestFocusInWindow();
        }

        @Override public void updateCustomComponent(JComponent component, Presentation presentation) {
            if (source instanceof JComboBox<?>) updatePickerEnabled(component, presentation.isEnabled());
        }

        private static void updatePickerEnabled(JComponent component, boolean enabled) {
            component.setEnabled(enabled);
            for (Component child : component.getComponents())
                if (child instanceof JComponent nested) updatePickerEnabled(nested, enabled);
        }

        @Override public JComponent createCustomComponent(Presentation presentation, String place) {
            JComponent control;
            if (source.getParent() == null) control = source;
            else if (source instanceof JButton button) control = DatabaseUi.mirrorAction(button);
            else if (source instanceof JComboBox<?> combo) control = DatabaseInputs.mirrorComboBox(combo);
            else throw new IllegalArgumentException("Unsupported toolbar control: " + source);
            JComponent result = control;
            if (label != null) result = DatabaseUi.labeledInput(new JBLabel(label), control);
            else if (control instanceof JButton && source instanceof JButton button && !button.getText().isEmpty()) {
                JPanel padded = new JPanel(new BorderLayout());
                padded.setOpaque(false);
                padded.setBorder(JBUI.Borders.empty(0, 2));
                padded.add(control);
                result = padded;
            }
            markControls(result);
            return result;
        }

        private static void markControls(JComponent component) {
            component.putClientProperty("lattice.toolbar.control", Boolean.TRUE);
            for (Component child : component.getComponents())
                if (child instanceof JComponent nested) markControls(nested);
        }
    }
}
