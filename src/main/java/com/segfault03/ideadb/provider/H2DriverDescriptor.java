package com.segfault03.ideadb.provider;

import java.util.List;

final class H2DriverDescriptor implements DriverDescriptor {
    public String displayName() { return "H2 JDBC"; }
    public String packagedJarKeyword() { return "h2-"; }
    public List<Coordinate> coordinates() { return List.of(new Coordinate("com.h2database", "h2")); }
    public List<String> driverClasses() { return List.of("org.h2.Driver"); }
    public List<String> isolatedPackages() { return List.of("org.h2."); }
    public List<String> suggestedVersions() { return List.of("2.3.232", "2.2.224"); }
    public String artifactPath(String version) {
        if (version == null || !version.matches("2\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("Choose an H2 2.x release such as 2.3.232");
        return "com/h2database/h2/" + version + "/h2-" + version + ".jar";
    }
}
