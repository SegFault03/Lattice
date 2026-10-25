package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class SqlLiterals {
    private SqlLiterals() {}
    static String common(Object value) {
        if (value == null) return "NULL";
        if (value instanceof byte[] bytes) return "X'" + java.util.HexFormat.of().formatHex(bytes) + "'";
        if (value instanceof Boolean bool) return bool ? "TRUE" : "FALSE";
        if (value instanceof Number) {
            if (value instanceof Double number && !Double.isFinite(number) || value instanceof Float single && !Float.isFinite(single))
                throw new IllegalArgumentException("Non-finite numbers cannot be exported as SQL literals");
            return value.toString();
        }
        return null;
    }
}
