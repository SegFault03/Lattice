package com.segfault03.ideadb.sql.card;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory;
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar;
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.editor.SqlConsoleVirtualFile;
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Runs the card pass after injections are known, in the editors that show cards. */
public final class SqlCardPassFactory implements TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {
    @Override public void registerHighlightingPassFactory(@NotNull TextEditorHighlightingPassRegistrar registrar, @NotNull Project project) {
        registrar.registerTextEditorHighlightingPass(this, new int[] {Pass.UPDATE_ALL}, null, false, -1);
    }

    @Override public @Nullable TextEditorHighlightingPass createHighlightingPass(@NotNull PsiFile file, @NotNull Editor editor) {
        if (!showsCards(editor, file.getVirtualFile())) return null;
        boolean enabled = SqlEditorSettings.getInstance().isSqlCardsEffective() && !UltimateDatabasePlugin.isActive();
        return new SqlCardPass(file, editor, enabled);
    }

    /**
     * Writable editors of files on disk. Not viewers, diffs, previews or consoles, and not Lattice's own SQL inputs,
     * whose in-memory files are {@link LightVirtualFile}s; their editor kind cannot tell them apart from a file's.
     */
    /** The platform's own limit for loading a file's content, above which it stops analyzing the file. */
    private static final int MAX_DOCUMENT_LENGTH = 20 * 1024 * 1024;

    static boolean showsCards(@NotNull Editor editor, @Nullable VirtualFile file) {
        EditorKind kind = editor.getEditorKind();
        return kind != EditorKind.DIFF && kind != EditorKind.PREVIEW && kind != EditorKind.CONSOLE && !editor.isViewer()
                && file != null && !(file instanceof LightVirtualFile) && !(file instanceof SqlConsoleVirtualFile)
                && editor.getDocument().getTextLength() <= MAX_DOCUMENT_LENGTH;
    }
}
