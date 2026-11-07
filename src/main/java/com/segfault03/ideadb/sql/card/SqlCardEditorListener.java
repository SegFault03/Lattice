package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import org.jetbrains.annotations.NotNull;

/** Gives each editor that shows cards its hover toolbar. */
public final class SqlCardEditorListener implements EditorFactoryListener {
    @Override public void editorCreated(@NotNull EditorFactoryEvent event) { SqlCardHoverController.install(event.getEditor()); }
}
