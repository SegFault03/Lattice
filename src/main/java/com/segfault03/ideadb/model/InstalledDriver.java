package com.segfault03.ideadb.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A driver JAR that is already on this machine and therefore never needs downloading again.
 * Packaged drivers ship inside the plugin; downloaded drivers are retained in the JDBC cache.
 */
public record InstalledDriver(DatabaseType type, String version, boolean packaged, Path jar) {
    public InstalledDriver {
        Objects.requireNonNull(type);
        version = Objects.requireNonNullElse(version, "");
        jar = jar == null ? null : jar.toAbsolutePath();
    }

    public InstalledDriver(DatabaseType type, String version, boolean packaged) {
        this(type, version, packaged, null);
    }

    public String label() {
        String name = version.isBlank() ? "Packaged with plugin" : version;
        return packaged ? name : name + " · DOWNLOADED";
    }

    @Override public String toString() {
        return label();
    }
}
