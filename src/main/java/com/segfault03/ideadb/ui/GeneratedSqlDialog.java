package com.segfault03.ideadb.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;

/** Cached metadata can generate SQL without opening a live database console. */
final class GeneratedSqlDialog extends DialogWrapper {
    private final String sql;
    private final Runnable openInConsole;

    GeneratedSqlDialog(Project project, String sql) { this(project, "Generated SQL from cached metadata", sql, null); }

    /** {@code openInConsole}, when given, adds an action that closes the dialog and runs it. */
    GeneratedSqlDialog(Project project, String title, String sql, Runnable openInConsole) {
        super(project, false);
        this.sql = sql;
        this.openInConsole = openInConsole;
        setTitle(title);
        setOKButtonText("Close");
        init();
    }

    @Override protected JComponent createCenterPanel() {
        var scroll = new JBScrollPane(sqlView(sql));
        scroll.setPreferredSize(JBUI.size(640, 240));
        return scroll;
    }

    static JTextArea sqlView(String sql) {
        var text = new JBTextArea(sql);
        text.setEditable(false);
        // Tests and previews can run without an application, hence the monospaced fallback.
        text.setFont(ApplicationManager.getApplication() == null ? new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize())
                : EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
        text.setBorder(JBUI.Borders.empty(8));
        text.setCaretPosition(0);
        text.getAccessibleContext().setAccessibleName("Generated SQL");
        return text;
    }

    @Override protected Action[] createActions() {
        var copy = new DialogWrapperAction("Copy SQL") {
            @Override protected void doAction(ActionEvent event) {
                CopyPasteManager.getInstance().setContents(new StringSelection(sql));
            }
        };
        if (openInConsole == null) return new Action[]{copy, getOKAction()};
        return new Action[]{copy, new DialogWrapperAction("Open in SQL Console") {
            @Override protected void doAction(ActionEvent event) { close(OK_EXIT_CODE); openInConsole.run(); }
        }, getOKAction()};
    }
}
