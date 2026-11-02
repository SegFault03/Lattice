package com.segfault03.ideadb.sql.lang;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;


/**
 * Colors the tables, views, aliases and columns of a Lattice SQL file once the connection's cached schema says what
 * they are: the same resolution as the hover card, so what is colored is what the card would describe. It reads the
 * cache only and never connects. Without a connection, or while the cache may be out of date, the file keeps the
 * token colors of the syntax highlighter.
 *
 * <p>The work is done once per file, at the file element, because the file's PSI is flat; {@link SqlSemanticColoring}
 * does the resolving, for this and for every other surface that colors SQL.
 */
public final class SqlSemanticAnnotator implements Annotator, DumbAware {
    @Override public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file) || !file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE)) return;
        if (!SqlEditorSettings.getInstance().isHighlightingEffective()) return;
        CharSequence text = file.getViewProvider().getContents();
        VirtualFile virtual = file.getOriginalFile().getVirtualFile();
        SqlSchema schema = SqlSemanticColoring.currentSchema(SqlDialectResolver.connection(file.getProject(), virtual), virtual);
        if (schema == null) return;
        SqlDialectProfile profile = SqlDialectResolver.resolve(file.getProject(), virtual);
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        for (SqlSemanticColoring.Colored colored : SqlSemanticColoring.colored(text, profile, schema)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(colored.range())
                    .enforcedTextAttributes(SqlSemanticColors.attributes(scheme, colored.role())).create();
        }
    }
}
