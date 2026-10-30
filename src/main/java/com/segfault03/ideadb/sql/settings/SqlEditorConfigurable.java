package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.segfault03.ideadb.sql.inject.SqlKnownCalls;
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
    private final JBCheckBox hover = new JBCheckBox("Hover documentation: tables, columns, keys and comments of the bound connection");
    private final JBCheckBox injection = new JBCheckBox("SQL inside Java and Kotlin strings");
    private final JBCheckBox injectExplicit = new JBCheckBox("Strings marked @Language(\"SQL\") or // language=SQL");
    private final JBCheckBox injectKnownCalls = new JBCheckBox("Strings passed to the methods listed below");
    private final JBCheckBox injectContent = new JBCheckBox("Strings that look like SQL statements (strict)");
    private final JBTextArea knownCalls = new JBTextArea(7, 30);
    private final javax.swing.JComboBox<SqlEditorSettings.KeywordCase> keywordCase = new javax.swing.JComboBox<>(SqlEditorSettings.KeywordCase.values());
    private final JBCheckBox validation = new JBCheckBox("Validation: syntax errors, unknown tables and columns (severities under Editor | Inspections | Lattice SQL)");
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
                .addComponent(completion).addLabeledComponent("Keyword case when inserted:", keywordCase).addComponent(hover).addComponent(validation)
                .addSeparator(JBUI.scale(8)).addComponent(injection)
                .addComponent(injectExplicit).addComponent(injectKnownCalls).addComponent(injectContent)
                .addLabeledComponent("Methods whose first argument is SQL, one per line:", new JBScrollPane(knownCalls), true)
                .addComponentFillVertically(new JPanel(), 0).getPanel();
        injection.addActionListener(event -> updateDependents());
        completion.addActionListener(event -> updateDependents());
        enabled.addActionListener(event -> updateDependents());
        reset();
        return panel;
    }

    private void updateDependents() {
        handleFiles.setEnabled(enabled.isSelected());
        highlighting.setEnabled(enabled.isSelected());
        completion.setEnabled(enabled.isSelected());
        keywordCase.setEnabled(enabled.isSelected() && completion.isSelected());
        hover.setEnabled(enabled.isSelected());
        validation.setEnabled(enabled.isSelected());
        injection.setEnabled(enabled.isSelected());
        boolean tiers = enabled.isSelected() && injection.isSelected();
        injectExplicit.setEnabled(tiers);
        injectKnownCalls.setEnabled(tiers);
        injectContent.setEnabled(tiers);
        knownCalls.setEnabled(tiers);
    }

    @Override public boolean isModified() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        return enabled.isSelected() != settings.isEnabled() || handleFiles.isSelected() != settings.isHandleSqlFiles()
                || highlighting.isSelected() != settings.isHighlighting() || completion.isSelected() != settings.isCompletion()
                || validation.isSelected() != settings.isValidation() || keywordCase.getSelectedItem() != settings.keywordCase() || hover.isSelected() != settings.isHover() || injection.isSelected() != settings.isInjection()
                || injectExplicit.isSelected() != settings.isInjectExplicit() || injectKnownCalls.isSelected() != settings.isInjectKnownCalls()
                || injectContent.isSelected() != settings.isInjectContent() || !knownCalls.getText().equals(settings.knownCalls());
    }

    @Override public void apply() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        if (enabled.isSelected() != settings.isEnabled()) settings.setEnabled(enabled.isSelected());
        settings.setHandleSqlFiles(handleFiles.isSelected());
        settings.setHighlighting(highlighting.isSelected());
        settings.setCompletion(completion.isSelected());
        settings.keywordCase((SqlEditorSettings.KeywordCase) keywordCase.getSelectedItem());
        settings.setHover(hover.isSelected());
        settings.setValidation(validation.isSelected());
        settings.setInjection(injection.isSelected());
        settings.setInjectExplicit(injectExplicit.isSelected());
        settings.setInjectKnownCalls(injectKnownCalls.isSelected());
        settings.setInjectContent(injectContent.isSelected());
        settings.knownCalls(knownCalls.getText().isBlank() ? SqlKnownCalls.DEFAULT : knownCalls.getText());
        SqlEditorSupport.injectionSettingsChanged();
        SqlEditorSupport.settingsChanged();
    }

    @Override public void reset() {
        SqlEditorSettings settings = SqlEditorSettings.getInstance();
        enabled.setSelected(settings.isEnabled());
        handleFiles.setSelected(settings.isHandleSqlFiles());
        highlighting.setSelected(settings.isHighlighting());
        completion.setSelected(settings.isCompletion());
        keywordCase.setSelectedItem(settings.keywordCase());
        hover.setSelected(settings.isHover());
        validation.setSelected(settings.isValidation());
        injection.setSelected(settings.isInjection());
        injectExplicit.setSelected(settings.isInjectExplicit());
        injectKnownCalls.setSelected(settings.isInjectKnownCalls());
        injectContent.setSelected(settings.isInjectContent());
        knownCalls.setText(settings.knownCalls());
        updateDependents();
    }

    @Override public void disposeUIResources() { panel = null; }
}
