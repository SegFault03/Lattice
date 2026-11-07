package com.segfault03.ideadb.sql.inject;

import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Finds the marker comments above a statement or member, whatever the language. */
public final class SqlStatementComments {
    private SqlStatementComments() {}

    /**
     * The statement, declaration or member an expression belongs to: the nearest ancestor whose parent is a container
     * of statements.
     *
     * @param isContainer recognizes a code block, class body or file in the language at hand
     */
    public static @NotNull PsiElement statementOf(@NotNull PsiElement expression, @NotNull Predicate<PsiElement> isContainer) {
        PsiElement current = expression;
        while (current.getParent() != null && !isContainer.test(current.getParent())) current = current.getParent();
        return current;
    }

    /**
     * The mark in the comments directly above a statement; the nearest comment that says anything wins. A member's
     * own leading comments may be children of the member (Java fields, Kotlin declarations); a statement's are the
     * siblings before it.
     */
    public static @NotNull SqlMarkers.Mark markAbove(@NotNull PsiElement statement) {
        List<PsiComment> nearestFirst = new ArrayList<>();
        for (PsiElement child = statement.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof PsiComment comment) nearestFirst.add(0, comment);
            else if (!(child instanceof PsiWhiteSpace)) break;
        }
        for (PsiElement previous = PsiTreeUtil.skipWhitespacesBackward(statement); previous instanceof PsiComment comment;
             previous = PsiTreeUtil.skipWhitespacesBackward(previous)) {
            nearestFirst.add(comment);
        }
        for (PsiComment comment : nearestFirst) {
            SqlMarkers.Mark mark = SqlMarkers.ofComment(comment.getText());
            if (mark != SqlMarkers.Mark.NONE) return mark;
        }
        return SqlMarkers.Mark.NONE;
    }
}
