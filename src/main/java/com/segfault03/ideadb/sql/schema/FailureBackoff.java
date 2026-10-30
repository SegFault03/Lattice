package com.segfault03.ideadb.sql.schema;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Keeps an unreachable connection from being retried on every keystroke: after a failure it is left alone for a while. */
final class FailureBackoff {
    private final long delayMillis;
    private final LongSupplier clock;
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    FailureBackoff(long delayMillis, @NotNull LongSupplier clock) {
        this.delayMillis = delayMillis;
        this.clock = clock;
    }

    void failed(@NotNull String key) { failedAt.put(key, clock.getAsLong()); }
    void succeeded(@NotNull String key) { failedAt.remove(key); }
    void forget(@NotNull String key) { failedAt.remove(key); }

    boolean blocked(@NotNull String key) {
        Long failure = failedAt.get(key);
        if (failure == null) return false;
        if (clock.getAsLong() - failure >= delayMillis) {
            failedAt.remove(key, failure);
            return false;
        }
        return true;
    }
}
