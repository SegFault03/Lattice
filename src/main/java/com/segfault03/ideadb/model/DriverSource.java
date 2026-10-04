package com.segfault03.ideadb.model;

public enum DriverSource {
    BUNDLED("Bundled driver"), DOWNLOAD("Download a version"), LOCAL_JAR("Local JAR");
    private final String label;
    DriverSource(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
