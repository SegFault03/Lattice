package com.segfault03.ideadb.provider;

import com.segfault03.ideadb.model.*;
import java.sql.*;
import java.util.*;

final class MySqlDriverDescriptor implements DriverDescriptor {
    public String displayName() { return "MySQL Connector/J"; }
    public String packagedJarKeyword() { return "mysql-connector"; }
    public List<Coordinate> coordinates() { return List.of(new Coordinate("com.mysql", "mysql-connector-j"), new Coordinate("mysql", "mysql-connector-java")); }
    public List<String> driverClasses() { return List.of("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"); }
    public List<String> isolatedPackages() { return List.of("com.mysql."); }
    public List<String> suggestedVersions() { return List.of("26.7.0", "9.0.0", "8.4.0", "8.0.33", "5.1.49"); }
    public boolean includesRelease(String version) { return acceptsVersion(version) && Integer.parseInt(version.split("\\.")[0]) >= 5; }
    public String artifactPath(String selection) {
        if (selection == null || !selection.matches("\\d+\\.\\d+\\.\\d+")) throw new IllegalArgumentException("Enter a release version such as 8.0.33");
        String[] parts = selection.split("\\.");
        int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]), patch = Integer.parseInt(parts[2]);
        Coordinate coordinate = coordinates().get(major > 8 || major == 8 && (minor > 0 || patch >= 31) ? 0 : 1);
        return coordinate.directory() + "/" + selection + "/" + coordinate.artifactId() + "-" + selection + ".jar";
    }
}
