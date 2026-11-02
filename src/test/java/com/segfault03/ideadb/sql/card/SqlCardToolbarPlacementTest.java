package com.segfault03.ideadb.sql.card;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlCardToolbarPlacementTest {
    private static final Dimension TOOLBAR = new Dimension(120, 28);

    @Test void aboveTopRight() {
        assertEquals(new Point(380, 70), SqlCardToolbarPlacement.place(new Rectangle(100, 100, 400, 60), TOOLBAR, new Rectangle(0, 0, 1000, 800)));
    }

    @Test void insideWhenNoRoomAbove() {
        assertEquals(new Point(380, 102), SqlCardToolbarPlacement.place(new Rectangle(100, 100, 400, 60), TOOLBAR, new Rectangle(0, 90, 1000, 800)));
    }

    @Test void clampedToVisibleWidth() {
        assertEquals(880, SqlCardToolbarPlacement.place(new Rectangle(900, 100, 400, 60), TOOLBAR, new Rectangle(0, 0, 1000, 800)).x);
    }

    @Test void clampedToVisibleLeft() {
        assertEquals(0, SqlCardToolbarPlacement.place(new Rectangle(0, 100, 60, 20), TOOLBAR, new Rectangle(0, 0, 1000, 800)).x);
    }
}
