package com.segfault03.ideadb.sql.status;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.InspectionWidgetActionProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Puts Lattice's status icon into each editor's inspection widget; the action hides itself where it does not apply. */
public final class LatticeStatusActionProvider implements InspectionWidgetActionProvider {
    @Override public @Nullable AnAction createAction(@NotNull Editor editor) {
        return editor.getProject() == null ? null : new LatticeStatusAction(editor);
    }
}
