package com.segfault03.ideadb.ui;

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

    GeneratedSqlDialog(Project project, String sql) {
        super(project, false);
        this.sql = sql;
        setTitle("Generated SQL from cached metadata");
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
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, text.getFont().getSize()));
        text.setBorder(JBUI.Borders.empty(8));
        text.setCaretPosition(0);
        text.getAccessibleContext().setAccessibleName("Generated SQL");
        return text;
    }

    @Override protected Action[] createActions() {
        return new Action[]{new DialogWrapperAction("Copy SQL") {
            @Override protected void doAction(ActionEvent event) {
                CopyPasteManager.getInstance().setContents(new StringSelection(sql));
            }
        }, getOKAction()};
    }
}
