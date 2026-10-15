package com.segfault03.ideadb.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Literal, case-insensitive matching of object names, never types or status text. */
final class DatabaseExplorerSearch {
    static final String MATCHES_PROPERTY = "lattice.explorer.search.matches";
    enum Category {
        DATA_SOURCE("Data sources"), SCHEMA("Schemas"), TABLE("Tables"), KEY("Keys / columns");
        final String label;
        Category(String label) { this.label = label; }
    }
    record Range(int start, int end) {}
    private final String text;
    private final Set<Category> categories;

    DatabaseExplorerSearch(String text, Set<Category> categories) {
        this.text = text == null ? "" : text.strip();
        this.categories = categories.isEmpty() ? EnumSet.allOf(Category.class) : EnumSet.copyOf(categories);
    }

    boolean isActive() { return !text.isEmpty(); }
    int metadataDepth() {
        return !isActive() ? 0 : categories.contains(Category.KEY) ? 3
                : categories.contains(Category.TABLE) ? 2 : categories.contains(Category.SCHEMA) ? 1 : 0;
    }

    String name(TreeNodeData data) {
        return data.getType() == TreeNodeData.NodeType.COLUMN && data.getColumnMetadata() != null
                ? data.getColumnMetadata().getName() : data.getName();
    }

    boolean matches(TreeNodeData data) {
        Category category = switch (data.getType()) {
            case SOURCE_TYPE, CONNECTION -> Category.DATA_SOURCE;
            case DATABASE -> Category.SCHEMA;
            case TABLE, VIEW -> Category.TABLE;
            case COLUMN -> Category.KEY;
            default -> null;
        };
        return category != null && categories.contains(category) && !ranges(name(data)).isEmpty();
    }

    List<Range> ranges(String name) {
        List<Range> result = new ArrayList<>();
        if (!isActive() || name == null) return result;
        for (int i = 0; i <= name.length() - text.length();) {
            if (name.regionMatches(true, i, text, 0, text.length())) {
                result.add(new Range(i, i + text.length()));
                i += text.length();
            } else i++;
        }
        return result;
    }
}
