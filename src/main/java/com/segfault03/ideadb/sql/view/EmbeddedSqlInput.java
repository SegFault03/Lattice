package com.segfault03.ideadb.sql.view;

import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.ui.EditorTextField;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlEmbeddedBinding;
import com.segfault03.ideadb.sql.lang.LatticeSqlFileType;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SqlInput} that is an IDE editor over a Lattice SQL file of its own, so it has the dialect's highlighting, the
 * completion and hover of the file's connection and, through {@link SqlEmbeddedValidation}, the checks. The file lives in
 * memory only and is told its connection, namespace and, for a field that holds a fragment, the start of its statement
 * through a {@link SqlEmbeddedBinding}, never through the saved file bindings.
 */
public final class EmbeddedSqlInput implements SqlInput {
    private final Project project;
    private final Document document;
    private final EditorTextField field;
    private final SqlEmbeddedBinding binding;
    private final SqlEmbeddedValidation validation;
    private final Disposable disposable = Disposer.newDisposable("lattice.sql.input");
    /** Keeps the document and the file bound to each other. */
    private final PsiFile psiFile;
    private final List<Runnable> caretListeners = new ArrayList<>();
    private int pendingCaret = -1;
    private int pendingStart = -1;
    private int pendingEnd = -1;

    /** Whether an embedded editor can be made here: a real project, with the SQL editor support switched on. */
    public static boolean isAvailable(@Nullable Project project) {
        return project instanceof ProjectEx && !project.isDisposed() && ApplicationManager.getApplication() != null
                && SqlEditorSettings.getInstance().isEnabled();
    }

    /**
     * @param leadIn    the start of the statement a one-line fragment completes (select * from t where), or empty
     * @param multiLine whether it takes several lines, with line numbers, as a console does
     */
    public static @NotNull EmbeddedSqlInput create(@NotNull Project project, @NotNull ConnectionConfig config, @Nullable String namespace,
                                                   @NotNull String leadIn, boolean multiLine, @NotNull String text, @NotNull String accessibleName) {
        return new EmbeddedSqlInput(project, config, namespace, leadIn, multiLine, text, accessibleName);
    }

    private EmbeddedSqlInput(Project project, ConnectionConfig config, @Nullable String namespace, String leadIn, boolean multiLine,
                             String text, String accessibleName) {
        this.project = project;
        this.binding = new SqlEmbeddedBinding(config.getId(), namespace, leadIn);
        this.psiFile = PsiFileFactory.getInstance(project).createFileFromText("lattice-input.sql", LatticeSqlFileType.INSTANCE, text,
                System.currentTimeMillis(), true, false);
        VirtualFile virtual = psiFile.getViewProvider().getVirtualFile();
        binding.attachTo(virtual);
        this.document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) throw new IllegalStateException("No document for the SQL input");
        this.validation = new SqlEmbeddedValidation(project, document, virtual, disposable);
        this.field = new EditorTextField(document, project, LatticeSqlFileType.INSTANCE, false, !multiLine) {
            // A one-line field puts a text field's frame, and its padding, around the editor: inside the frame it is put in, that is a second frame.
            @Override protected boolean shouldHaveBorder() { return false; }
            @Override protected void setupBorder(@NotNull EditorEx editor) { editor.setBorder(JBUI.Borders.empty()); }
        };
        field.setFontInheritedFromLAF(false);
        field.setBorder(JBUI.Borders.empty());
        field.getAccessibleContext().setAccessibleName(accessibleName);
        field.addSettingsProvider(editor -> {
            editor.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtual));
            editor.getSettings().setAdditionalLinesCount(0);
            editor.getSettings().setAdditionalColumnsCount(0);
            editor.getSettings().setFoldingOutlineShown(false);
            editor.getSettings().setLineMarkerAreaShown(false);
            editor.getSettings().setTabSize(4);
            editor.getSettings().setCaretRowShown(multiLine);
            if (multiLine) {
                editor.getSettings().setLineNumbersShown(true);
                editor.setVerticalScrollbarVisible(true);
                editor.setHorizontalScrollbarVisible(true);
                editor.setBorder(JBUI.Borders.empty(6, 4));
            }
            editor.getCaretModel().addCaretListener(new CaretListener() {
                @Override public void caretPositionChanged(@NotNull CaretEvent event) { caretListeners.forEach(Runnable::run); }
            }, disposable);
            editor.getSelectionModel().addSelectionListener(new SelectionListener() {
                @Override public void selectionChanged(@NotNull SelectionEvent event) { caretListeners.forEach(Runnable::run); }
            }, disposable);
            if (pendingCaret >= 0) editor.getCaretModel().moveToOffset(Math.min(pendingCaret, document.getTextLength()));
            if (pendingStart >= 0) editor.getSelectionModel().setSelection(Math.min(pendingStart, document.getTextLength()), Math.min(pendingEnd, document.getTextLength()));
            validation.attach(editor);
        });
        field.setDisposedWith(disposable);
        this.pendingCaret = text.length();
    }

    @Override public @NotNull JComponent component() { return field; }
    @Override public @NotNull String text() { return document.getText(); }

    @Override public void setText(@NotNull String value) {
        field.setText(value);
        moveCaret(value.length());
    }

    private @Nullable Editor editor() { return field.getEditor(); }

    private void moveCaret(int offset) {
        pendingCaret = offset;
        pendingStart = -1;
        Editor editor = editor();
        if (editor != null) {
            editor.getSelectionModel().removeSelection();
            editor.getCaretModel().moveToOffset(Math.min(offset, document.getTextLength()));
        }
    }

    // The console asks for the caret and selection from plain Swing callbacks, which hold no read lock; the editor's
    // caret model needs one.
    @Override public int caretOffset() {
        Editor editor = editor();
        return editor != null ? ReadAction.compute(() -> editor.getCaretModel().getOffset())
                : pendingCaret >= 0 ? pendingCaret : document.getTextLength();
    }

    @Override public int selectionStart() {
        Editor editor = editor();
        if (editor != null) return ReadAction.compute(() -> editor.getSelectionModel().getSelectionStart());
        return pendingStart >= 0 ? pendingStart : caretOffset();
    }

    @Override public int selectionEnd() {
        Editor editor = editor();
        if (editor != null) return ReadAction.compute(() -> editor.getSelectionModel().getSelectionEnd());
        return pendingStart >= 0 ? pendingEnd : caretOffset();
    }

    @Override public void select(int start, int end) {
        pendingStart = start;
        pendingEnd = end;
        Editor editor = editor();
        if (editor != null) editor.getSelectionModel().setSelection(start, end);
    }

    @Override public void insertAtCaret(@NotNull String value) {
        int at = Math.min(caretOffset(), document.getTextLength());
        WriteCommandAction.runWriteCommandAction(project, () -> document.insertString(at, value));
        moveCaret(at + value.length());
    }

    @Override public void append(@NotNull String value) {
        WriteCommandAction.runWriteCommandAction(project, () -> document.insertString(document.getTextLength(), value));
    }

    @Override public void onChange(@NotNull Runnable listener) {
        document.addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) { listener.run(); }
        }, disposable);
    }

    @Override public void onCaretMoved(@NotNull Runnable listener) { caretListeners.add(listener); }

    @Override public void onSubmit(@NotNull Runnable action) {
        new DumbAwareAction() {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(@NotNull AnActionEvent event) {
                // While the completion popup is open, Enter chooses its item.
                Editor editor = editor();
                event.getPresentation().setEnabled(editor != null && LookupManager.getActiveLookup(editor) == null);
            }
            @Override public void actionPerformed(@NotNull AnActionEvent event) { action.run(); }
        }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke("ENTER")), field, disposable);
    }

    @Override public void registerShortcut(@NotNull KeyStroke key, @NotNull Runnable action) {
        new DumbAwareAction() {
            @Override public void actionPerformed(@NotNull AnActionEvent event) { action.run(); }
        }.registerCustomShortcutSet(new CustomShortcutSet(key), field, disposable);
    }

    @Override public void setNamespace(@Nullable String namespace) {
        binding.namespace(namespace);
        validation.schedule();
    }

    @Override public void dispose() { Disposer.dispose(disposable); }
}
