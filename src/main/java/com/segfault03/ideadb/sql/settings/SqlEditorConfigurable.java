package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.sql.SqlEditorSupport;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nls;

import javax.swing.*;

/** Settings | Tools | Lattice SQL Editor. */
public final class SqlEditorConfigurable implements SearchableConfigurable {
    private final JBCheckBox enabled = new JBCheckBox("Enable Lattice SQL editor support");
    private final JBCheckBox handleFiles = new JBCheckBox("Handle .sql files (replaces the IDE's highlighting-only SQL file type)");
    private final JBCheckBox highlighting = new JBCheckBox("Syntax highlighting for the dialect of the bound connection");
    private final JBCheckBox completion = new JBCheckBox("Code completion: keywords, functions, tables and columns of the bound connection");
    private JPanel panel;

    @Override public @NotNull String getId() { return "com.segfault03.lattice.sql.editor"; }
    @Override public @Nls String getDisplayName() { return "Lattice SQL Editor"; }

    @Override public JComponent createComponent() {
        FormBuilder form = FormBuilder.createFormBuilder().addComponent(enabled);
        if (UltimateDatabasePlugin.isActive()) {
            JBLabel note = new JBLabel("<html>The IDE's Database plugin is enabled and provides SQL support, so this is off unless you turn it on.</html>");
            note.setBorder(JBUI.Borders.emptyLeft(24));
            form.addComponent(note);
        }
        panel = form.addComponent(handleFiles, JBUI.scale(8)).addComponent(highlighting)
                .addComponent(completion).addComponentFillVertically(new JPanel(), 0).getPanel();
        enabled.addActionListener(event -> updateDependents());
        reset();
        return panel;
    }

    private void updateDependents() {
        handleFiles.setEnabled(enabled.isSelected());
        highlighting.setEnabled(enabled.isSelected());
        completion.setEnabled(enabled.isSelected());
    }

    @Override public boolean isModified() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        return enabled.isSelected() != settings.isEnabled() || handleFiles.isSelected() != settings.isHandleSqlFiles()
                || highlighting.isSelected() != settings.isHighlighting() || completion.isSelected() != settings.isCompletion();
    }

    @Override public void apply() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        if (enabled.isSelected() != settings.isEnabled()) settings.setEnabled(enabled.isSelected());
        settings.setHandleSqlFiles(handleFiles.isSelected());
        settings.setHighlighting(highlighting.isSelected());
        settings.setCompletion(completion.isSelected());
        SqlEditorSupport.settingsChanged();
    }

    @Override public void reset() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        enabled.setSelected(settings.isEnabled());
        handleFiles.setSelected(settings.isHandleSqlFiles());
        highlighting.setSelected(settings.isHighlighting());
        completion.setSelected(settings.isCompletion());
        updateDependents();
    }

    @Override public void disposeUIResources() { panel = null; }
}
