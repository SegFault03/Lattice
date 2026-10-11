package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.InstalledDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class MavenPomDriverResolverTest {
    @TempDir Path project;

    @Test void usesDriverVersionsFromProjectModulesAndParentPomWhenTheirJarsAreValid() throws Exception {
        Path mysqlModule = Files.createDirectories(project.resolve("mysql-module"));
        Path hsqlModule = Files.createDirectories(project.resolve("hsql-module"));
        Path repository = project.resolve("m2/repository");
        Files.createDirectories(repository);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <groupId>example</groupId><artifactId>parent</artifactId><version>1</version>
                  <properties><mysql.version>8.4.0</mysql.version><hsqldb.version>2.7.2</hsqldb.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>${mysql.version}</version></dependency>
                  </dependencies></dependencyManagement>
                  <modules><module>mysql-module</module><module>hsql-module</module></modules>
                </project>
                """);
        Files.writeString(mysqlModule.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
                  <artifactId>mysql-module</artifactId>
                  <dependencies><dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId></dependency></dependencies>
                </project>
                """);
        Files.writeString(hsqlModule.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
                  <artifactId>hsql-module</artifactId>
                  <dependencies><dependency><groupId>org.hsqldb</groupId><artifactId>hsqldb</artifactId><version>${hsqldb.version}</version></dependency></dependencies>
                </project>
                """);

        Path mysqlJar = DriverCatalog.discoveredJar(DatabaseType.MYSQL, "8.4.0", repository);
        Path hsqlJar = DriverCatalog.discoveredJar(DatabaseType.HSQLDB, "2.7.2", repository);
        Files.createDirectories(mysqlJar.getParent());
        Files.createDirectories(hsqlJar.getParent());
        Files.copy(packagedJar("mysql-connector"), mysqlJar, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(packagedJar("hsqldb-"), hsqlJar, StandardCopyOption.REPLACE_EXISTING);

        var mysql = MavenPomDriverResolver.find(DatabaseType.MYSQL, project.resolve("pom.xml"), repository).orElseThrow();
        var hsql = MavenPomDriverResolver.find(DatabaseType.HSQLDB, project.resolve("pom.xml"), repository).orElseThrow();
        assertEquals("8.4.0", mysql.version());
        assertEquals(InstalledDriver.Kind.DISCOVERED, mysql.kind());
        assertEquals(mysqlJar.toAbsolutePath(), mysql.jar());
        assertEquals("2.7.2", hsql.version());
        assertEquals(InstalledDriver.Kind.DISCOVERED, hsql.kind());
        assertEquals(hsqlJar.toAbsolutePath(), hsql.jar());
    }

    @Test void fallsBackWhenThePomVersionIsMissingOrItsMavenJarIsInvalid() throws Exception {
        Path pom = project.resolve("pom.xml");
        Path repository = project.resolve("m2/repository");
        Files.writeString(pom, """
                <project><dependencies>
                  <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>8.4.0</version></dependency>
                </dependencies></project>
                """);

        assertTrue(MavenPomDriverResolver.find(DatabaseType.MYSQL, pom, repository).isEmpty(),
                "A POM version without a local JAR must not replace the packaged driver");
        Path jar = DriverCatalog.discoveredJar(DatabaseType.MYSQL, "8.4.0", repository);
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "not a jar");
        assertTrue(MavenPomDriverResolver.find(DatabaseType.MYSQL, pom, repository).isEmpty(),
                "A corrupt local JAR must not replace the packaged driver");

        Files.writeString(pom, "<project><dependencies><dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>latest</version></dependency></dependencies></project>");
        assertTrue(MavenPomDriverResolver.find(DatabaseType.MYSQL, pom, repository).isEmpty(),
                "A non-release POM version must not replace the packaged driver");
    }

    @Test void resolvesTheLegacyMysqlCoordinateWhenItsArtifactIsCached() throws Exception {
        Path pom = project.resolve("pom.xml");
        Path repository = project.resolve("m2/repository");
        Files.writeString(pom, """
                <project><dependencies>
                  <dependency><groupId>mysql</groupId><artifactId>mysql-connector-java</artifactId><version>8.0.33</version></dependency>
                </dependencies></project>
                """);
        Path jar = repository.resolve("mysql/mysql-connector-java/8.0.33/mysql-connector-java-8.0.33.jar");
        Files.createDirectories(jar.getParent());
        Files.copy(packagedJar("mysql-connector"), jar, StandardCopyOption.REPLACE_EXISTING);

        var selected = MavenPomDriverResolver.find(DatabaseType.MYSQL, pom, repository).orElseThrow();
        assertEquals("8.0.33", selected.version());
        assertEquals(jar.toAbsolutePath(), selected.jar());
        assertEquals(java.util.List.of("8.0.33"), DriverCatalog.discoveredVersions(DatabaseType.MYSQL, repository));
        assertEquals(jar.toAbsolutePath(), DriverCatalog.discoveredJar(DatabaseType.MYSQL, "8.0.33", repository));
    }

    @Test void childOverridesInheritedDependencyAndManagedVersionExpressions() throws Exception {
        Path child = Files.createDirectories(project.resolve("child"));
        Path repository = project.resolve("repository");
        for (String version : java.util.List.of("2.7.2", "2.7.3")) {
            Path jar = DriverCatalog.discoveredJar(DatabaseType.HSQLDB, version, repository);
            Files.createDirectories(jar.getParent()); Files.copy(packagedJar("hsqldb-"), jar);
        }
        for (boolean managed : new boolean[]{false, true}) {
            String dependency = "<dependency><groupId>org.hsqldb</groupId><artifactId>hsqldb</artifactId>";
            String versioned = dependency + "<version>${jdbc.version}</version></dependency>";
            Files.writeString(project.resolve("pom.xml"), "<project><groupId>example</groupId><artifactId>parent</artifactId><version>1</version>"
                    + "<properties><jdbc.version>${driver.version}</jdbc.version><driver.version>2.7.2</driver.version></properties>"
                    + (managed ? "<dependencyManagement><dependencies>" + versioned + "</dependencies></dependencyManagement>"
                    + "<dependencies>" + dependency + "</dependency></dependencies>"
                    : "<dependencies>" + versioned + "</dependencies>") + "</project>");
            Files.writeString(child.resolve("pom.xml"), """
                    <project><parent><groupId>example</groupId><artifactId>parent</artifactId><version>1</version></parent>
                    <artifactId>child</artifactId><properties><driver.version>2.7.3</driver.version></properties></project>
                    """);
            assertEquals("2.7.3", MavenPomDriverResolver.find(DatabaseType.HSQLDB, child.resolve("pom.xml"), repository)
                    .orElseThrow().version(), "Child properties must interpolate inherited " + (managed ? "managed" : "direct") + " dependencies");
        }
    }

    @Test void unrelatedRelativeParentCannotSupplyADriver() throws Exception {
        Path child = Files.createDirectories(project.resolve("child"));
        Path repository = project.resolve("repository");
        Path jar = DriverCatalog.discoveredJar(DatabaseType.HSQLDB, "2.7.2", repository);
        Files.createDirectories(jar.getParent()); Files.copy(packagedJar("hsqldb-"), jar);
        Files.writeString(project.resolve("pom.xml"), """
                <project><groupId>unrelated</groupId><artifactId>parent</artifactId><version>1</version>
                <dependencies><dependency><groupId>org.hsqldb</groupId><artifactId>hsqldb</artifactId><version>2.7.2</version></dependency></dependencies></project>
                """);
        Files.writeString(child.resolve("pom.xml"), """
                <project><parent><groupId>expected</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>child</artifactId></project>
                """);
        assertTrue(MavenPomDriverResolver.find(DatabaseType.HSQLDB, child.resolve("pom.xml"), repository).isEmpty());
    }

    private static Path packagedJar(String prefix) throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("lattice.test.drivers")))) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)
                    && path.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
        }
    }
}
