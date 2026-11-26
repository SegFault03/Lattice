package com.segfault03.ideadb.search;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.state.MetadataCacheState.CachedTable;
import com.segfault03.ideadb.state.RecentItemsState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatticeSearchTest {
    private final ConnectionConfig staging = new ConnectionConfig(DatabaseType.POSTGRESQL, "Staging");
    private final List<CachedTable> tables = List.of(
            new CachedTable("app", new TableMetadata(null, "public", "customer_orders", "TABLE")),
            new CachedTable("app", new TableMetadata(null, "public", "orders", "TABLE")),
            new CachedTable("app", new TableMetadata(null, "public", "order_items", "TABLE")));
    private final List<RecentItemsState.Item> recents = List.of(
            RecentItemsState.Item.table(staging.getId(), "app", null, "public", "order_items", "TABLE"),
            RecentItemsState.Item.table("deleted-connection", "app", null, "public", "orders", "TABLE"));

    private List<String> titles(String query, List<ConnectionConfig> connections) {
        return LatticeSearch.search(query, connections, recents, config -> tables).stream()
                .map(item -> item.kind() + " " + item.title()).toList();
    }

    @Test void emptyQueryListsRecentItemsOfExistingConnectionsOnly() {
        assertEquals(List.of("RECENT public.order_items"), titles("", List.of(staging)));
    }

    @Test void queryRanksRecentThenBestTableMatchesWithoutRepeatingRecentTables() {
        assertEquals(List.of("RECENT public.order_items", "TABLE public.orders", "TABLE public.customer_orders"),
                titles("ord", List.of(staging)));
    }

    @Test void connectionMatchOffersItsConsoleAndFirstRunOffersEngines() {
        assertEquals(List.of("CONNECTION Staging", "CONSOLE Open SQL console on Staging"), titles("stag", List.of(staging)));
        assertEquals(DatabaseType.values().length, titles("", List.of()).size());
    }
}
