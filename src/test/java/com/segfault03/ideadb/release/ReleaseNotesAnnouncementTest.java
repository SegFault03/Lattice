package com.segfault03.ideadb.release;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseNotesAnnouncementTest {
    @Test void multipleProjectsAnnounceEachVersionOnlyOnce() throws Exception {
        var service = new ReleaseNotesAnnouncement(); var shown = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var work = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) work.add(executor.submit(() -> service.announceOnce("0.1.0-alpha", shown::incrementAndGet)));
            for (var task : work) task.get(5, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(1, shown.get());
        assertTrue(service.announceOnce("0.2.0-alpha", shown::incrementAndGet));
        assertFalse(service.announceOnce("0.1.0-alpha", shown::incrementAndGet));
        assertEquals(2, shown.get());
    }
    @Test void restartRetainsSeenVersionsAndOptOut() {
        var first = new ReleaseNotesAnnouncement();
        first.announceOnce("0.1.0-alpha", () -> {});
        var restored = new ReleaseNotesAnnouncement(); restored.loadState(first.getState());
        assertFalse(restored.announceOnce("0.1.0-alpha", () -> fail("Already announced")));
        restored.disable();
        var optedOut = new ReleaseNotesAnnouncement(); optedOut.loadState(restored.getState());
        assertFalse(optedOut.announceOnce("0.2.0-alpha", () -> fail("Opted out")));
    }
    @Test void failedDeliveryCanBeRetried() {
        var service = new ReleaseNotesAnnouncement();
        assertThrows(IllegalStateException.class, () -> service.announceOnce("0.1.0-alpha", () -> { throw new IllegalStateException("Not ready"); }));
        assertTrue(service.announceOnce("0.1.0-alpha", () -> {}));
    }
}
