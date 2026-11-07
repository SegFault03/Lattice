package com.segfault03.ideadb.sql.view;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * A place where the user writes SQL in the plugin's own UI: the console, or the WHERE and ORDER BY fields of a table.
 * With an IDE project it is a real editor with Lattice SQL highlighting, completion and checks; without one (previews,
 * unit tests) it is a plain Swing text component with the same behavior minus those. Callers use only this.
 */
public interface SqlInput {
    /** What goes into the layout. */
    @NotNull JComponent component();

    @NotNull String text();

    /** Replaces the text and puts the caret at its end. */
    void setText(@NotNull String text);

    int caretOffset();

    int selectionStart();

    int selectionEnd();

    void select(int start, int end);

    /** Adds text at the caret, leaving the caret after it. */
    void insertAtCaret(@NotNull String text);

    /** Adds text at the end. */
    void append(@NotNull String text);

    /** Called after every change of the text. */
    void onChange(@NotNull Runnable listener);

    /** Called when the caret or the selection moves. */
    void onCaretMoved(@NotNull Runnable listener);

    /** Called when Enter is pressed in a one-line field. */
    void onSubmit(@NotNull Runnable action);

    /** Runs the action when the key is pressed while the input has the focus. */
    void registerShortcut(@NotNull KeyStroke key, @NotNull Runnable action);

    /** The namespace unqualified names resolve to, for an input that works on a database chosen elsewhere. */
    void setNamespace(@Nullable String namespace);

    /** Releases listeners and the editor. */
    void dispose();
}
