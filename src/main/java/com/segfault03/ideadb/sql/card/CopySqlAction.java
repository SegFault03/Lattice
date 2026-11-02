package com.segfault03.ideadb.sql.card;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/** Card toolbar action: copy the card's SQL as plain text, with {@code ?} for the parts of a string built at run time. */
public final class CopySqlAction extends AnAction implements DumbAware {
    static final String COPIED = "SQL copied";

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getData(SqlCardDataKeys.SQL_CARD) != null);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        SqlCard card = event.getData(SqlCardDataKeys.SQL_CARD);
        if (card == null) return;
        CopyPasteManager.getInstance().setContents(new StringSelection(card.sql()));
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor != null) HintManager.getInstance().showInformationHint(editor, COPIED);
    }
}
