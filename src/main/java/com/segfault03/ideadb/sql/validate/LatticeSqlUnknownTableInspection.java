package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;

public final class LatticeSqlUnknownTableInspection extends LatticeSqlInspection {
    public LatticeSqlUnknownTableInspection() { super(Kind.UNKNOWN_TABLE); }
}
