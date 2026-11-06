package com.segfault03.ideadb.sql.view;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.event.ActionEvent;

/** A {@link SqlInput} over a Swing text component, for where there is no IDE project to build an editor in. */
public final class SwingSqlInput implements SqlInput {
    private final JTextComponent text;
    private final JComponent view;
    private int shortcuts;

    /** @param view what goes into the layout: the text component itself, or a scroll pane around it */
    public SwingSqlInput(@NotNull JTextComponent text, @NotNull JComponent view) {
        this.text = text;
        this.view = view;
    }

    @Override public @NotNull JComponent component() { return view; }
    @Override public @NotNull String text() { return text.getText(); }

    @Override public void setText(@NotNull String value) {
        text.setText(value);
        text.setCaretPosition(value.length());
    }

    @Override public int caretOffset() { return text.getCaretPosition(); }
    @Override public int selectionStart() { return text.getSelectionStart(); }
    @Override public int selectionEnd() { return text.getSelectionEnd(); }
    @Override public void select(int start, int end) { text.select(start, end); }

    @Override public void insertAtCaret(@NotNull String value) {
        try {
            int at = text.getCaretPosition();
            text.getDocument().insertString(at, value, null);
            text.setCaretPosition(at + value.length());
        } catch (BadLocationException unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    @Override public void append(@NotNull String value) {
        try {
            text.getDocument().insertString(text.getDocument().getLength(), value, null);
        } catch (BadLocationException unexpected) {
            throw new IllegalStateException(unexpected);
        }
    }

    @Override public void onChange(@NotNull Runnable listener) {
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { listener.run(); }
            @Override public void removeUpdate(DocumentEvent event) { listener.run(); }
            @Override public void changedUpdate(DocumentEvent event) { listener.run(); }
        });
    }

    @Override public void onCaretMoved(@NotNull Runnable listener) { text.addCaretListener(event -> listener.run()); }

    @Override public void onSubmit(@NotNull Runnable action) {
        if (text instanceof JTextField field) field.addActionListener(event -> action.run());
    }

    @Override public void registerShortcut(@NotNull KeyStroke key, @NotNull Runnable action) {
        String name = "lattice.sql.input." + shortcuts++;
        text.getInputMap().put(key, name);
        text.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    @Override public void setNamespace(@Nullable String namespace) {}
    @Override public void dispose() {}
}
