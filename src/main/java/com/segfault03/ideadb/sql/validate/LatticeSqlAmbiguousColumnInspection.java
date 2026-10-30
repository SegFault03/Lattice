package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;

public final class LatticeSqlAmbiguousColumnInspection extends LatticeSqlInspection {
    public LatticeSqlAmbiguousColumnInspection() { super(Kind.AMBIGUOUS_COLUMN); }
}
