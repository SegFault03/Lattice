package com.segfault03.ideadb.sql.schema;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FailureBackoffTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final FailureBackoff backoff = new FailureBackoff(30_000, now::get);

    @Test void aFailedKeyIsLeftAloneUntilTheDelayPasses() {
        assertFalse(backoff.blocked("db"));
        backoff.failed("db");
        assertTrue(backoff.blocked("db"));
        now.addAndGet(29_999);
        assertTrue(backoff.blocked("db"));
        now.addAndGet(1);
        assertFalse(backoff.blocked("db"), "The delay is over");
        assertFalse(backoff.blocked("db"), "and it stays over until the next failure");
    }

    @Test void successAndForgettingClearTheBlock() {
        backoff.failed("a");
        backoff.failed("b");
        backoff.succeeded("a");
        backoff.forget("b");
        assertFalse(backoff.blocked("a"));
        assertFalse(backoff.blocked("b"));
    }

    @Test void keysAreIndependent() {
        backoff.failed("a");
        assertTrue(backoff.blocked("a"));
        assertFalse(backoff.blocked("b"));
    }
}
