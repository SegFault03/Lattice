package com.segfault03.ideadb.sql.lang;

import com.intellij.lang.Language;

/** SQL as edited in project files. Dialect-specific behavior comes from the file's bound connection, not from the language. */
public final class LatticeSqlLanguage extends Language {
    public static final LatticeSqlLanguage INSTANCE = new LatticeSqlLanguage();
    private LatticeSqlLanguage() { super("LatticeSQL"); }
}
