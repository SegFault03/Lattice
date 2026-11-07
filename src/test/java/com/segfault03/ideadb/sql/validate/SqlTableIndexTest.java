package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The table index must answer exactly as a scan of every table with the edit distance would. */
class SqlTableIndexTest {
    private static String randomName(Random random, int maxLength) {
        String alphabet = "abcAB_1";
        StringBuilder name = new StringBuilder();
        for (int i = 1 + random.nextInt(maxLength); i > 0; i--) name.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return name.toString();
    }

    private static String mutate(Random random, String name) {
        StringBuilder text = new StringBuilder(name);
        for (int edits = random.nextInt(4); edits > 0; edits--) {
            int at = text.length() == 0 ? 0 : random.nextInt(text.length());
            switch (random.nextInt(4)) {
                case 0 -> { if (text.length() > 0) text.deleteCharAt(at); }
                case 1 -> text.insert(at, "abAB_1".charAt(random.nextInt(6)));
                case 2 -> { if (text.length() > 0) text.setCharAt(at, "abAB_1".charAt(random.nextInt(6))); }
                default -> { if (at + 1 < text.length()) { char c = text.charAt(at); text.setCharAt(at, text.charAt(at + 1)); text.setCharAt(at + 1, c); } }
            }
        }
        return text.toString();
    }

    private static String scan(List<String> names, String wanted) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int limit = wanted.length() <= 4 ? 1 : 2;
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(wanted)) continue;
            int distance = SqlEditDistance.distance(wanted.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT), limit);
            if (distance <= limit && distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }

    @Test void nearestAgreesWithAScanOfEveryName() {
        Random random = new Random(42);
        List<String> names = new ArrayList<>();
        List<ExplorerMetadataSnapshot.Table> tables = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String name = randomName(random, 9);
            names.add(name);
            tables.add(new ExplorerMetadataSnapshot.Table(new TableMetadata(null, "public", name, "TABLE"), List.of(), false));
        }
        var snapshot = new ExplorerMetadataSnapshot(List.of(new ExplorerMetadataSnapshot.Namespace("public", tables, true)), List.of(), 3);
        var schema = SqlSchema.of(new ConnectionConfig(DatabaseType.H2, "index"), snapshot, "public", 1);
        var index = schema.index(snapshot.namespaces().get(0));
        assertSame(index, schema.index(snapshot.namespaces().get(0)), "built once");
        for (int i = 0; i < 4_000; i++) {
            String wanted = mutate(random, names.get(random.nextInt(names.size())));
            if (wanted.isEmpty()) continue;
            assertEquals(scan(names, wanted), index.nearest(wanted, wanted.length() <= 4 ? 1 : 2), "for '" + wanted + "'");
        }
    }

    @Test void findAndAmbiguityFollowTheNamesCase() {
        var tables = List.of(
                new ExplorerMetadataSnapshot.Table(new TableMetadata(null, "public", "orders", "TABLE"), List.of(), false),
                new ExplorerMetadataSnapshot.Table(new TableMetadata(null, "public", "Orders", "TABLE"), List.of(), false),
                new ExplorerMetadataSnapshot.Table(new TableMetadata(null, "public", "Items", "TABLE"), List.of(), false));
        var snapshot = new ExplorerMetadataSnapshot(List.of(new ExplorerMetadataSnapshot.Namespace("public", tables, true)), List.of(), 3);
        var index = SqlSchema.of(new ConnectionConfig(DatabaseType.H2, "index"), snapshot, "public", 1).index(snapshot.namespaces().get(0));
        assertSame(tables.get(0), index.find("orders"));
        assertSame(tables.get(1), index.find("Orders"));
        assertNull(index.find("ORDERS"));
        assertSame(tables.get(2), index.find("items"));
        assertTrue(index.ambiguousCase("ORDERS"));
        assertFalse(index.ambiguousCase("items"));
        assertFalse(index.ambiguousCase("missing"));
    }
}
