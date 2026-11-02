package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.actionSystem.DataKey;

/** The card a toolbar action works on; without it the SQL actions work on the caret, as from the editor menu. */
public final class SqlCardDataKeys {
    public static final DataKey<SqlCard> SQL_CARD = DataKey.create("com.segfault03.lattice.sql.card");

    private SqlCardDataKeys() {}
}
