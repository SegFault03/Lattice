package com.segfault03.ideadb.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reopening moves an item to the top instead of duplicating it, and the list stays bounded. */
class RecentItemsStateTest {
    @Test void reopeningMovesToTopAndListStaysBounded() {
        RecentItemsState recent = new RecentItemsState(null);
        recent.record(RecentItemsState.Item.table("c1", "shop", null, "public", "orders", "TABLE"));
        recent.record(RecentItemsState.Item.console("c1", "shop"));
        // The same table reported with another type is still the same place.
        recent.record(RecentItemsState.Item.table("c1", "shop", null, "public", "orders", "BASE TABLE"));
        assertEquals(2, recent.items().size());
        assertEquals("orders", recent.items().get(0).name);
        assertEquals("BASE TABLE", recent.items().get(0).tableType);

        for (int i = 0; i < RecentItemsState.LIMIT + 5; i++)
            recent.record(RecentItemsState.Item.table("c1", "shop", null, "public", "t" + i, "TABLE"));
        assertEquals(RecentItemsState.LIMIT, recent.items().size());
        assertEquals("t" + (RecentItemsState.LIMIT + 4), recent.items().get(0).name);
    }
}
