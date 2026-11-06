package com.segfault03.ideadb.sql.card;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlCardGeometryTest {
    @Test void singleLineAddsPadding() {
        assertEquals(new Rectangle(95, 40, 210, 20), SqlCardGeometry.bounds(List.of(new SqlCardGeometry.Extent(100, 300, 40, 20))));
    }

    @Test void multiLineUsesWidestExtent() {
        assertEquals(new Rectangle(115, 0, 370, 60), SqlCardGeometry.bounds(List.of(
                new SqlCardGeometry.Extent(120, 250, 0, 20),
                new SqlCardGeometry.Extent(120, 480, 20, 20),
                new SqlCardGeometry.Extent(130, 250, 40, 20))));
    }

    @Test void softWrappedLineContributesEachVisualLine() {
        Rectangle bounds = SqlCardGeometry.bounds(List.of(
                new SqlCardGeometry.Extent(200, 600, 100, 20),
                new SqlCardGeometry.Extent(40, 180, 120, 20)));
        assertEquals(40, bounds.height);
        assertEquals(35, bounds.x);
        assertEquals(605, bounds.x + bounds.width);
    }
}
