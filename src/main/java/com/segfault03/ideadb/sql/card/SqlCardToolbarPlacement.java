package com.segfault03.ideadb.sql.card;

import org.jetbrains.annotations.NotNull;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

/** Where the hover toolbar goes: above the card's top-right corner, inside the card when there is no room above. */
public final class SqlCardToolbarPlacement {
    private static final int GAP = 2;

    private SqlCardToolbarPlacement() {}

    public static @NotNull Point place(@NotNull Rectangle card, @NotNull Dimension toolbar, @NotNull Rectangle visible) {
        int y = card.y - toolbar.height - GAP;
        if (y < visible.y) y = card.y + GAP;
        int x = card.x + card.width - toolbar.width;
        x = Math.max(visible.x, Math.min(x, visible.x + visible.width - toolbar.width));
        return new Point(x, y);
    }
}
