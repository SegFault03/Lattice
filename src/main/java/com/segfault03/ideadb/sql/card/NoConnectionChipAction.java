package com.segfault03.ideadb.sql.card;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlConnectionChooser;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/** Card toolbar warning, shown only while the card's file has no connection; choosing one is a click away. */
public final class NoConnectionChipAction extends AnAction implements CustomComponentAction, DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = hostFile(project, event.getData(CommonDataKeys.PSI_FILE));
        boolean unbound = event.getData(SqlCardDataKeys.SQL_CARD) != null && project != null && file != null
                && SqlDialectResolver.connection(project, file) == null;
        event.getPresentation().setEnabledAndVisible(unbound);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = hostFile(project, event.getData(CommonDataKeys.PSI_FILE));
        if (project != null && file != null) SqlConnectionChooser.chooseForFile(project, file, event.getData(CommonDataKeys.EDITOR));
    }

    @Override public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
        return new ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
    }

    private static @Nullable VirtualFile hostFile(@Nullable Project project, @Nullable PsiFile file) {
        if (project == null || file == null) return null;
        VirtualFile virtualFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(file).getVirtualFile();
        return virtualFile == null ? null : SqlBindingsState.hostOf(virtualFile);
    }
}
