package com.segfault03.ideadb.provider;

import java.util.List;

final class PostgresDriverDescriptor implements DriverDescriptor {
    public String displayName() { return "PostgreSQL JDBC"; }
    public String packagedJarKeyword() { return "postgresql-"; }
    public List<Coordinate> coordinates() { return List.of(new Coordinate("org.postgresql", "postgresql")); }
    public List<String> driverClasses() { return List.of("org.postgresql.Driver"); }
    public List<String> isolatedPackages() { return List.of("org.postgresql."); }
    public List<String> suggestedVersions() { return List.of("42.7.14", "42.7.3", "42.2.27"); }
    public String artifactPath(String version) {
        if (version == null || !version.matches("42\\.[0-9]+\\.[0-9]+")) throw new IllegalArgumentException("Choose a Java 8 pgJDBC release such as 42.7.14");
        return "org/postgresql/postgresql/" + version + "/postgresql-" + version + ".jar";
    }
}
