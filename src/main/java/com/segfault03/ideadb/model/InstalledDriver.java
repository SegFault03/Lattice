package com.segfault03.ideadb.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A driver JAR that is already on this machine and therefore never needs downloading again.
 * Drivers can ship inside the plugin, be retained in Lattice's cache, or be found in Maven's
 * configured local repository.
 */
public record InstalledDriver(DatabaseType type, String version, Kind kind, Path jar) {
    public enum Kind { BUNDLED, DOWNLOADED, DISCOVERED }

    public InstalledDriver {
        Objects.requireNonNull(type);
        version = Objects.requireNonNullElse(version, "");
        Objects.requireNonNull(kind);
        jar = jar == null ? null : jar.toAbsolutePath();
    }

    public InstalledDriver(DatabaseType type, String version, boolean packaged, Path jar) {
        this(type, version, packaged ? Kind.BUNDLED : Kind.DOWNLOADED, jar);
    }

    public InstalledDriver(DatabaseType type, String version, boolean packaged) {
        this(type, version, packaged ? Kind.BUNDLED : Kind.DOWNLOADED, null);
    }

    public boolean packaged() {
        return kind == Kind.BUNDLED;
    }

    public String label() {
        String name = version.isBlank() ? "Packaged with plugin" : version;
        return name + " · " + kind;
    }

    @Override public String toString() {
        return label();
    }
}
