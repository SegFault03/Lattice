package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class HsqlDriverDescriptor implements DriverDescriptor {
    public String displayName() { return "HSQLDB"; }
    public String packagedJarKeyword() { return "hsqldb"; }
    public List<Coordinate> coordinates() { return List.of(new Coordinate("org.hsqldb", "hsqldb")); }
    public List<String> driverClasses() { return List.of("org.hsqldb.jdbc.JDBCDriver", "org.hsqldb.jdbcDriver"); }
    public List<String> isolatedPackages() { return List.of("org.hsqldb."); }
    public List<String> suggestedVersions() { return List.of("2.7.4-jdk8", "2.7.3-jdk8", "2.6.1-jdk8", "2.5.2", "2.4.1", "2.3.6", "2.3.0", "2.2.9"); }
    public String artifactPath(String selection) {
        if (selection == null || !selection.matches("\\d+\\.\\d+\\.\\d+(-jdk8)?")) throw new IllegalArgumentException("Enter a release version such as 2.7.4-jdk8");
        String version = selection.replaceFirst("-jdk8$", "");
        return "org/hsqldb/hsqldb/" + version + "/hsqldb-" + selection + ".jar";
    }
    public String baseVersion(String selection) { return selection.replaceFirst("-jdk8$", ""); }
    public List<String> discoveryVersionsForRelease(String version) { return List.of(version, version + "-jdk8"); }
    public List<String> versionsForRelease(String version) {
        return version.matches("2\\.[67]\\.[0-9]+") ? List.of(version, version + "-jdk8") : List.of(version);
    }
}
