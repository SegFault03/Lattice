package com.segfault03.ideadb.sql.dialect;

import com.segfault03.ideadb.model.DatabaseType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The built-in dialect profiles. Word lists are curated, not exhaustive; later phases extend them from the connection. */
public final class SqlDialects {
    private SqlDialects() {}

    private static Set<String> words(String... lists) {
        Set<String> all = new HashSet<>();
        for (String list : lists) all.addAll(Arrays.asList(list.trim().split("\\s+")));
        return Set.copyOf(all);
    }

    private static final String ANSI_KEYWORDS = """
            ADD ALL ALTER AND ANY AS ASC AUTHORIZATION BEGIN BETWEEN BY CASCADE CASE CAST CHECK COLUMN COMMIT CONSTRAINT
            CREATE CROSS CURRENT DATABASE DECLARE DEFAULT DELETE DESC DISTINCT DROP ELSE END ESCAPE EXCEPT EXISTS EXPLAIN
            FALSE FETCH FIRST FOLLOWING FOR FOREIGN FROM FULL FUNCTION GRANT GROUP HAVING IF IN INDEX INNER INSERT
            INTERSECT INTO IS JOIN KEY LAST LEFT LIKE NATURAL NEXT NOT NULL NULLS OFFSET ON ONLY OR ORDER OUTER OVER
            PARTITION PRECEDING PRIMARY PROCEDURE RANGE RECURSIVE REFERENCES RESTRICT RETURN RETURNS REVOKE RIGHT
            ROLLBACK ROW ROWS SAVEPOINT SCHEMA SELECT SET START TABLE TEMPORARY THEN TO TRANSACTION TRIGGER TRUE
            TRUNCATE UNBOUNDED UNION UNIQUE UNKNOWN UPDATE USING VALUES VIEW WHEN WHERE WINDOW WITH
            """;
    private static final String ANSI_FUNCTIONS = """
            ABS AVG CEIL CEILING COALESCE CONCAT COUNT CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP DENSE_RANK EXTRACT
            FIRST_VALUE FLOOR LAG LAST_VALUE LEAD LENGTH LOWER MAX MIN MOD NULLIF NTILE POSITION POWER RANK REPLACE
            ROUND ROW_NUMBER SQRT SUBSTRING SUM TRIM UPPER
            """;
    private static final String ANSI_TYPES = """
            BIGINT BINARY BIT BLOB BOOLEAN CHAR CHARACTER CLOB DATE DEC DECIMAL DOUBLE FLOAT INT INTEGER INTERVAL NUMERIC
            REAL SMALLINT TEXT TIME TIMESTAMP VARBINARY VARCHAR
            """;

    private static final String MYSQL_KEYWORDS = """
            AUTO_INCREMENT BINARY CHARSET COLLATE COMMENT DATABASES DELAYED DESCRIBE DISTINCTROW DIV DUPLICATE ENGINE
            FORCE FULLTEXT HIGH_PRIORITY IGNORE KEYS LIMIT LOCK LOW_PRIORITY MOD OPTIMIZE REGEXP REPLACE RLIKE SHOW
            SPATIAL SQL_CALC_FOUND_ROWS STRAIGHT_JOIN TABLES UNLOCK UNSIGNED USE VARIABLES XOR ZEROFILL
            """;
    private static final String MYSQL_FUNCTIONS = """
            CONCAT_WS CURDATE CURTIME DATE_ADD DATE_FORMAT DATE_SUB DATEDIFF FIND_IN_SET FROM_UNIXTIME GROUP_CONCAT
            IFNULL INSTR JSON_EXTRACT JSON_OBJECT LAST_INSERT_ID LEFT LOCATE MD5 NOW RAND RIGHT STR_TO_DATE SUBSTR
            TIMESTAMPDIFF UNIX_TIMESTAMP UUID VALUES
            """;
    private static final String MYSQL_TYPES = """
            DATETIME ENUM JSON LONGBLOB LONGTEXT MEDIUMBLOB MEDIUMINT MEDIUMTEXT TINYBLOB TINYINT TINYTEXT YEAR
            """;

    private static final String POSTGRES_KEYWORDS = """
            ANALYZE CONCURRENTLY CONFLICT COPY DO EXTENSION FILTER ILIKE ISNULL LATERAL LIMIT MATERIALIZED NOTHING
            NOTNULL OWNER RETURNING SEQUENCE SIMILAR TABLESPACE VACUUM VERBOSE WITHIN
            """;
    private static final String POSTGRES_FUNCTIONS = """
            ARRAY_AGG BOOL_AND BOOL_OR DATE_TRUNC GENERATE_SERIES GREATEST JSON_AGG JSONB_BUILD_OBJECT LEAST NOW
            REGEXP_REPLACE SPLIT_PART STRING_AGG TO_CHAR TO_DATE TO_TIMESTAMP UNNEST
            """;
    private static final String POSTGRES_TYPES = """
            BIGSERIAL BYTEA CIDR INET JSON JSONB MONEY SERIAL SMALLSERIAL TIMESTAMPTZ TIMETZ TSVECTOR UUID XML
            """;

    private static final String HSQLDB_KEYWORDS = """
            ALWAYS BY CACHED CALL GENERATED IDENTITY LIMIT MEMORY SCRIPT SHUTDOWN
            """;
    private static final String HSQLDB_FUNCTIONS = """
            CURRENT_USER DATEDIFF IFNULL LCASE NOW REGEXP_MATCHES TO_CHAR UCASE UUID
            """;
    private static final String HSQLDB_TYPES = """
            LONGVARCHAR LONGVARBINARY OTHER
            """;

    private static final String H2_KEYWORDS = """
            CALL IDENTITY LIMIT MERGE MINUS SHOW SHUTDOWN
            """;
    private static final String H2_FUNCTIONS = """
            ARRAY_AGG DATEADD DATEDIFF IFNULL LISTAGG NOW RANDOM_UUID REGEXP_REPLACE SYSDATE TO_CHAR
            """;
    private static final String H2_TYPES = """
            ARRAY ENUM GEOMETRY IDENTITY JSON TINYINT UUID
            """;

    private static SqlDialectProfile profile(String id, String name, DatabaseType type, SqlDialectProfile.Syntax syntax,
                                             String keywords, String functions, String types) {
        return new SqlDialectProfile(id, name, type, syntax, words(ANSI_KEYWORDS, keywords),
                words(ANSI_FUNCTIONS, functions), words(ANSI_TYPES, types));
    }

    //                                                    "str   \esc  `id`   #cmt   //cmt  --sp   $$     /*nest  E''    ::     $1     @var
    private static final SqlDialectProfile.Syntax PLAIN = syntax(false, false, false, false, false, false, false, false, false, false, false, false);

    private static SqlDialectProfile.Syntax syntax(boolean... flags) {
        return new SqlDialectProfile.Syntax(flags[0], flags[1], flags[2], flags[3], flags[4], flags[5], flags[6], flags[7],
                flags[8], flags[9], flags[10], flags[11]);
    }

    public static final SqlDialectProfile ANSI = profile("ansi", "Generic SQL", null, PLAIN, "", "", "");
    public static final SqlDialectProfile MYSQL = profile("mysql", "MySQL", DatabaseType.MYSQL,
            syntax(true, true, true, true, false, true, false, false, false, false, false, true),
            MYSQL_KEYWORDS, MYSQL_FUNCTIONS, MYSQL_TYPES);
    public static final SqlDialectProfile POSTGRESQL = profile("postgresql", "PostgreSQL", DatabaseType.POSTGRESQL,
            syntax(false, false, false, false, false, false, true, true, true, true, true, false),
            POSTGRES_KEYWORDS, POSTGRES_FUNCTIONS, POSTGRES_TYPES);
    public static final SqlDialectProfile HSQLDB = profile("hsqldb", "HSQLDB", DatabaseType.HSQLDB, PLAIN,
            HSQLDB_KEYWORDS, HSQLDB_FUNCTIONS, HSQLDB_TYPES);
    public static final SqlDialectProfile H2 = profile("h2", "H2", DatabaseType.H2,
            syntax(false, false, false, false, true, false, true, false, false, false, false, false),
            H2_KEYWORDS, H2_FUNCTIONS, H2_TYPES);

    private static final List<SqlDialectProfile> ALL = List.of(ANSI, MYSQL, POSTGRESQL, HSQLDB, H2);

    public static @NotNull List<SqlDialectProfile> all() { return ALL; }

    public static @NotNull SqlDialectProfile of(@Nullable DatabaseType type) {
        if (type == null) return ANSI;
        return switch (type) {
            case MYSQL -> MYSQL;
            case POSTGRESQL -> POSTGRESQL;
            case HSQLDB -> HSQLDB;
            case H2 -> H2;
        };
    }

    public static @NotNull SqlDialectProfile byId(@Nullable String id) {
        for (SqlDialectProfile profile : ALL) if (profile.id().equals(id)) return profile;
        return ANSI;
    }
}
