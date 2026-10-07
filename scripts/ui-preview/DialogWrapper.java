package com.intellij.openapi.ui;

import com.intellij.openapi.project.Project;
import javax.swing.*;
import java.awt.*;

/** Preview-only shell. Does not initialize the IDE or invoke dialog actions. */
public abstract class DialogWrapper {
    private final JPanel panel = new JPanel(new BorderLayout(0, 16));
    private String title;
    private final Action okAction = new AbstractAction("OK") { public void actionPerformed(java.awt.event.ActionEvent event) {} };
    private final Action cancelAction = new AbstractAction("Cancel") { public void actionPerformed(java.awt.event.ActionEvent event) {} };
    protected DialogWrapper(Project project, boolean modal) {}
    protected void init() {
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(createCenterPanel(), BorderLayout.CENTER);
        panel.add(createSouthPanel(), BorderLayout.SOUTH);
    }
    public Window getWindow() { return null; }
    public JPanel previewPanel() { return panel; }
    protected abstract JComponent createCenterPanel();
    protected JComponent createSouthPanel() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        for (Action action : createActions()) buttons.add(new JButton(action));
        return buttons;
    }
    protected Action[] createActions() { return new Action[]{cancelAction, okAction}; }
    protected Action getCancelAction() { return cancelAction; }
    protected void setCancelButtonText(String value) { cancelAction.putValue(Action.NAME, value); }
    public void setOKButtonText(String value) { okAction.putValue(Action.NAME, value); }
    public void setTitle(String value) { title = value; }
    public void setResizable(boolean value) {}
    public void setOKActionEnabled(boolean value) {}
    public boolean isDisposed() { return false; }
    protected ValidationInfo doValidate() { return null; }
    protected void dispose() {}
}
