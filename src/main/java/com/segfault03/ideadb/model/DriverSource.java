package com.segfault03.ideadb.model;

public enum DriverSource {
    /** The packaged driver, or a download already retained on this machine. */
    BUNDLED("Bundled driver"),
    /** Fetches a release from Maven Central once, then keeps it in the bundled list. */
    DOWNLOAD("Download a version"),
    LOCAL_JAR("Local JAR");
    private final String label;
    DriverSource(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
