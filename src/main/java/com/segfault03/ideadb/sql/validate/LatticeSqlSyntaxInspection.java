package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;

public final class LatticeSqlSyntaxInspection extends LatticeSqlInspection {
    public LatticeSqlSyntaxInspection() { super(Kind.SYNTAX); }
}
