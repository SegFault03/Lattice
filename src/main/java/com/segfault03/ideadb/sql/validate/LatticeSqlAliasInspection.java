package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;

public final class LatticeSqlAliasInspection extends LatticeSqlInspection {
    public LatticeSqlAliasInspection() { super(Kind.ALIAS); }
}
