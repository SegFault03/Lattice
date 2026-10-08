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

    private static Path packagedJar(String prefix) throws Exception {
        try (var files = Files.list(Path.of("lib"))) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)
                            && path.getFileName().toString().endsWith(".jar"))
                    .findFirst().orElseThrow();
        }
    }
}
