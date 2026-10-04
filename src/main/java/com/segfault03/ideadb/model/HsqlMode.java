package com.segfault03.ideadb.model;

public enum HsqlMode {
    MEM("In-Memory (mem)"),
    FILE("Embedded File (file)"),
    SERVER("Remote Server (hsql://)");

    private final String displayName;

    HsqlMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
