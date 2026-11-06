package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

/** All discovery and classloader rules for one engine live with its provider. */
public interface DriverDescriptor {
    record Coordinate(String groupId, String artifactId) {
        public String directory() { return groupId.replace('.', '/') + "/" + artifactId; }
    }
    String displayName();
    String packagedJarKeyword();
    List<Coordinate> coordinates();
    List<String> driverClasses();
    List<String> isolatedPackages();
    List<String> suggestedVersions();
    String artifactPath(String selection);
    default String baseVersion(String selection) { return selection; }
    default boolean includesRelease(String version) { return acceptsVersion(version); }
    /** The first release clear of every published advisory; blank when none is known. */
    default String firstPatchedVersion() { return ""; }
    /** Old servers can need affected releases, so they stay selectable with a warning. Blank for a safe or unrecognized version. */
    default String securityWarning(String version) {
        String patched = firstPatchedVersion();
        String release = version == null ? "" : version.strip();
        if (patched.isEmpty() || !release.matches("\\d+(\\.\\d+)+.*") || DriverVersionOrder.descending(release, patched) <= 0) return "";
        return displayName() + " " + release + " has known security vulnerabilities. Use " + patched
                + " or later unless your server needs this release.";
    }

    default boolean acceptsVersion(String selection) {
        try { artifactPath(selection); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    default List<String> versionsForRelease(String version) { return List.of(version); }
    default List<String> discoveryVersionsForRelease(String version) { return versionsForRelease(version); }
    default int compareVersionsDescending(String left, String right) { return DriverVersionOrder.descending(left, right); }
    default boolean isolatesClass(String name) { return isolatedPackages().stream().anyMatch(name::startsWith); }
    default boolean isolatesResource(String name) { return isolatedPackages().stream().map(s -> s.replace('.', '/')).anyMatch(name::startsWith); }
}
