package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;

public final class LatticeSqlUnknownColumnInspection extends LatticeSqlInspection {
    public LatticeSqlUnknownColumnInspection() { super(Kind.UNKNOWN_COLUMN); }
}
