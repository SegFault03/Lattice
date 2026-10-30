package com.segfault03.ideadb.sql.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.jetbrains.annotations.NotNull;

/**
 * Flat PSI: the file is one node of leaf tokens, enough to locate the caret. The platform lexes PSI without
 * knowing the file, so it uses the generic profile; features that need dialect-correct tokens (highlighting
 * here, completion and validation later) lex the text again with the file's resolved profile.
 */
public final class LatticeSqlParserDefinition implements ParserDefinition {
    private static final IFileElementType FILE = new IFileElementType(LatticeSqlLanguage.INSTANCE);

    @Override public @NotNull Lexer createLexer(Project project) { return new LatticeSqlLexer(SqlDialects.ANSI); }

    @Override public @NotNull PsiParser createParser(Project project) {
        return new PsiParser() {
            @Override public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
                PsiBuilder.Marker marker = builder.mark();
                while (!builder.eof()) builder.advanceLexer();
                marker.done(root);
                return builder.getTreeBuilt();
            }
        };
    }

    @Override public @NotNull IFileElementType getFileNodeType() { return FILE; }
    @Override public @NotNull TokenSet getCommentTokens() { return LatticeSqlTokens.COMMENTS; }
    @Override public @NotNull TokenSet getStringLiteralElements() { return LatticeSqlTokens.STRINGS; }
    @Override public @NotNull PsiElement createElement(ASTNode node) { throw new UnsupportedOperationException(node.getElementType().toString()); }

    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider provider) {
        return new PsiFileBase(provider, LatticeSqlLanguage.INSTANCE) {
            @Override public @NotNull FileType getFileType() { return LatticeSqlFileType.INSTANCE; }
        };
    }
}
