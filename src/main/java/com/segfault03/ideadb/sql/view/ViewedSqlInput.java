package com.segfault03.ideadb.sql.view;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.KeyStroke;

/** A {@link SqlInput} shown through another component, such as a frame around its editor. Everything else is the input's. */
public final class ViewedSqlInput implements SqlInput {
    private final SqlInput input;
    private final JComponent view;

    public ViewedSqlInput(@NotNull SqlInput input, @NotNull JComponent view) {
        this.input = input;
        this.view = view;
    }

    @Override public @NotNull JComponent component() { return view; }
    @Override public @NotNull String text() { return input.text(); }
    @Override public void setText(@NotNull String text) { input.setText(text); }
    @Override public int caretOffset() { return input.caretOffset(); }
    @Override public int selectionStart() { return input.selectionStart(); }
    @Override public int selectionEnd() { return input.selectionEnd(); }
    @Override public void select(int start, int end) { input.select(start, end); }
    @Override public void insertAtCaret(@NotNull String text) { input.insertAtCaret(text); }
    @Override public void append(@NotNull String text) { input.append(text); }
    @Override public void onChange(@NotNull Runnable listener) { input.onChange(listener); }
    @Override public void onCaretMoved(@NotNull Runnable listener) { input.onCaretMoved(listener); }
    @Override public void onSubmit(@NotNull Runnable action) { input.onSubmit(action); }
    @Override public void registerShortcut(@NotNull KeyStroke key, @NotNull Runnable action) { input.registerShortcut(key, action); }
    @Override public void setNamespace(@Nullable String namespace) { input.setNamespace(namespace); }
    @Override public void dispose() { input.dispose(); }
}
