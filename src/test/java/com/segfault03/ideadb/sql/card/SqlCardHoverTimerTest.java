package com.segfault03.ideadb.sql.card;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlCardHoverTimerTest {
    private final SqlCardHoverTimer<String> timer = new SqlCardHoverTimer<>();

    @Test void showsAfter150() {
        timer.over("a", false, 0);
        assertEquals(Optional.empty(), timer.due(149));
        assertEquals(Optional.of("a"), timer.due(150));
    }

    @Test void quickSweepNeverShows() {
        timer.over("a", false, 0);
        timer.over(null, false, 100);
        assertEquals(Optional.empty(), timer.due(200));
    }

    @Test void staysWhileOverToolbar() {
        timer.over("a", false, 0);
        assertEquals(Optional.of("a"), timer.due(150));
        timer.over(null, true, 200);
        assertEquals(Optional.of("a"), timer.due(1000));
    }

    @Test void hidesAfter300() {
        timer.over("a", false, 0);
        timer.due(150);
        timer.over(null, false, 400);
        assertEquals(Optional.of("a"), timer.due(699));
        assertEquals(Optional.empty(), timer.due(700));
    }

    @Test void returningBeforeTheHideDelayKeepsTheCard() {
        timer.over("a", false, 0);
        timer.due(150);
        timer.over(null, false, 400);
        timer.over("a", false, 600);
        assertEquals(Optional.of("a"), timer.due(1000));
    }

    @Test void switchingCardsShowsNewAfterDelay() {
        timer.over("a", false, 0);
        timer.due(150);
        timer.over("b", false, 200);
        assertEquals(Optional.of("a"), timer.due(349));
        assertEquals(Optional.of("b"), timer.due(350));
    }

    @Test void cancelHidesImmediately() {
        timer.over("a", false, 0);
        timer.due(150);
        timer.cancel();
        assertEquals(Optional.empty(), timer.due(151));
    }
}
