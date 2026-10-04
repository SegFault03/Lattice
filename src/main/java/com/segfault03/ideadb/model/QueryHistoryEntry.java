package com.segfault03.ideadb.model;

/** Display previews never replace the executable SQL. Null is the combo's placeholder. */
public record QueryHistoryEntry(String sql) {
    @Override public String toString() {
        if(sql==null) return "(Recent Queries)";
        String preview=sql.replaceAll("\\s+"," ");
        return preview.length()>50 ? preview.substring(0,50) + "..." : preview;
    }
}
