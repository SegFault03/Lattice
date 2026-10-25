package com.segfault03.ideadb.provider;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class PostgresTransactionCommandTest {
    @Test void recognizesTransactionEndsAfterWhitespaceAndComments() {
        for (String command : new String[]{"ROLLBACK", "commit;", "End transaction", "abort", "ROLLBACK TO SAVEPOINT item"}) {
            assertTrue(PostgresProvider.isTransactionEnd(" \t\r\n-- line\r\n/* outer /* nested */ comment */ " + command), command);
        }
    }

    @Test void requiresAWholeUnquotedKeywordAndClosedComments() {
        for (String sql : new String[]{"", "-- COMMIT", "/* ROLLBACK", "/* outer /* nested */ COMMIT",
                "COMMITMENT", "ROLLBACK_TO", "COMMIT$tag", "END2", "\"COMMIT\"", "'ROLLBACK'", "SELECT 1", "BEGIN"}) {
            assertFalse(PostgresProvider.isTransactionEnd(sql), sql);
        }
    }

    @Test void scansAdversarialAndDeeplyNestedCommentsWithoutBacktracking() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertFalse(PostgresProvider.isTransactionEnd("/*" + "*//*".repeat(25_000) + "ROLLBACK"));
            assertTrue(PostgresProvider.isTransactionEnd("/*".repeat(25_000) + "comment" + "*/".repeat(25_000) + " COMMIT"));
            assertFalse(PostgresProvider.isTransactionEnd("X".repeat(100_000)));
        });
    }
}
