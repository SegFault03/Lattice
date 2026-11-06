package com.segfault03.ideadb.sql.status;

import com.intellij.testFramework.LightVirtualFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LatticeActivityTest {
    @Test void checkingWinsOverReadingAndEndsWithItsLastRun() {
        var file = new LightVirtualFile("activity.sql", "select 1");
        assertNull(LatticeActivity.current(file));
        assertFalse(LatticeActivity.hasSql(file));
        try (var reading = LatticeActivity.begin(file, LatticeActivity.Stage.READING)) {
            assertEquals(LatticeActivity.Stage.READING, LatticeActivity.current(file));
            var first = LatticeActivity.begin(file, LatticeActivity.Stage.CHECKING);
            var second = LatticeActivity.begin(file, LatticeActivity.Stage.CHECKING);
            assertEquals(LatticeActivity.Stage.CHECKING, LatticeActivity.current(file));
            first.close();
            assertEquals(LatticeActivity.Stage.CHECKING, LatticeActivity.current(file));
            second.close();
            assertEquals(LatticeActivity.Stage.READING, LatticeActivity.current(file));
        }
        assertNull(LatticeActivity.current(file));
        assertTrue(LatticeActivity.hasSql(file));
    }

    @Test void noFileIsNoWork() {
        try (var ignored = LatticeActivity.begin(null, LatticeActivity.Stage.CHECKING)) {
            assertNotNull(ignored);
        }
    }
}
