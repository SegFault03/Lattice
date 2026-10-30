package com.segfault03.ideadb.sql.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/** What the caret is in: the clause, the name being typed, and which tables are in scope there. */
public record SqlContext(@NotNull Position position, @NotNull String prefix, int prefixStart, char openQuote,
                         @NotNull List<String> qualifier, @NotNull List<TableRef> tables, @Nullable TableRef target,
                         @Nullable TableRef joinTarget, @NotNull List<TableRef> joinPrior, boolean afterOperand,
                         boolean lowercaseKeywords, @NotNull Set<String> usedNames) {

    /** The same context with the prefix located elsewhere, for an analysis made on part of a larger text. */
    public @NotNull SqlContext withPrefixStart(int start) {
        return new SqlContext(position, prefix, start, openQuote, qualifier, tables, target, joinTarget, joinPrior, afterOperand, lowercaseKeywords, usedNames);
    }

    /** The same context with keywords forced to one case, for a setting that overrides following the typed text. */
    public @NotNull SqlContext withLowercaseKeywords(boolean lowercase) {
        return new SqlContext(position, prefix, prefixStart, openQuote, qualifier, tables, target, joinTarget, joinPrior, afterOperand, lowercase, usedNames);
    }

    /** Where in a statement the caret is, which decides what is worth suggesting. */
    public enum Position {
        /** Inside a comment or string: suggest nothing. */
        NONE,
        STATEMENT_START,
        SELECT_LIST,
        /** The slot for a table name after FROM, JOIN, UPDATE, INTO and the like. */
        TABLE_NAME,
        /** After a table reference: alias, join, WHERE and so on. */
        AFTER_TABLE,
        /** WHERE, HAVING, or a join's ON. */
        CONDITION,
        ORDER_GROUP,
        SET_COLUMN,
        SET_VALUE,
        INSERT_COLUMNS,
        VALUES,
        TYPE_NAME,
        OTHER
    }

    /**
     * A table named in a FROM, JOIN, UPDATE or INTO clause. A derived table (a subquery with an alias) has no name.
     * Token indexes locate the reference in its statement.
     */
    public record TableRef(@Nullable String namespace, @Nullable String name, @Nullable String alias, int block,
                           @NotNull String introducer, int introducerIndex, int startIndex, int endIndex, boolean derived) {
        /** The name the statement uses to refer to this table. */
        public @Nullable String exposedName() { return alias != null ? alias : name; }
    }
}
