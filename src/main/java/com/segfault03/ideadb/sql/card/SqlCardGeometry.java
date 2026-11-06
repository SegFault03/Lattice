package com.segfault03.ideadb.sql.card;

import org.jetbrains.annotations.NotNull;

import java.awt.Rectangle;
import java.util.List;

/** The rectangle a card paints, from the extent of each visual line its SQL covers. */
public final class SqlCardGeometry {
    public static final int PADDING = 5;
    public static final int ARC = 8;

    /** The SQL on one visual line: x from {@code x0} to {@code x1}, from {@code y} down {@code height} pixels. */
    public record Extent(int x0, int x1, int y, int height) {}

    private SqlCardGeometry() {}

    public static @NotNull Rectangle bounds(@NotNull List<Extent> extents) {
        int left = Integer.MAX_VALUE, right = Integer.MIN_VALUE, top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
        for (Extent extent : extents) {
            left = Math.min(left, extent.x0());
            right = Math.max(right, extent.x1());
            top = Math.min(top, extent.y());
            bottom = Math.max(bottom, extent.y() + extent.height());
        }
        if (extents.isEmpty()) return new Rectangle();
        return new Rectangle(left - PADDING, top, right - left + 2 * PADDING, bottom - top);
    }
}
