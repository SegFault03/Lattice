package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.model.InstalledDriver;
import com.intellij.openapi.project.Project;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the project's declared JDBC driver when its exact, usable artifact is in Maven's local repository. */
public final class MavenPomDriverResolver {
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private MavenPomDriverResolver() {}

    public static Optional<InstalledDriver> find(DatabaseType type, Path projectPom, Path localRepository) {
        if (projectPom == null || localRepository == null) return Optional.empty();

        Map<Path, PomModel> models = new LinkedHashMap<>();
        for (Path pom : projectPoms(projectPom, models)) {
            PomModel model = readPom(pom, new LinkedHashSet<>(), models);
            for (Dependency dependency : model.dependencies()) {
                if (!isDriver(type, dependency.groupId(), dependency.artifactId())) continue;
                String version = resolve(dependency.version().isBlank()
                        ? model.managedVersions().getOrDefault(dependency.groupId() + ":" + dependency.artifactId(), "")
                        : dependency.version(), model.properties());
                Path jar = DriverCatalog.discoveredJar(type, version, localRepository);
                if (isValidDriverJar(type, version, jar)) {
                    return Optional.of(new InstalledDriver(type, version,
                            InstalledDriver.Kind.DISCOVERED, jar));
                }
            }
        }
        return Optional.empty();
    }

    /** Prefer IntelliJ's effective Maven model, then fall back to the project's POM files during import/startup. */
    public static Optional<InstalledDriver> find(DatabaseType type, Project project, Path projectPom, Path localRepository) {
        Optional<InstalledDriver> imported = findInImportedProjects(type, project, localRepository);
        return imported.isPresent() ? imported : find(type, projectPom, localRepository);
    }

    private static Optional<InstalledDriver> findInImportedProjects(DatabaseType type, Project project, Path localRepository) {
        if (project == null || localRepository == null) return Optional.empty();
        List<Coordinate> coordinates = driverCoordinates(type);
        try {
            ClassLoader loader = MavenPomDriverResolver.class.getClassLoader();
            Class<?> managerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager", true, loader);
            Object manager = managerClass.getMethod("getInstance", Project.class).invoke(null, project);
            Object importedProjects = managerClass.getMethod("getProjects").invoke(manager);
            if (!(importedProjects instanceof Iterable<?> projects)) return Optional.empty();
            for (Object mavenProject : projects) {
                for (Coordinate coordinate : coordinates) {
                    Object matches = mavenProject.getClass()
                            .getMethod("findDependencies", String.class, String.class)
                            .invoke(mavenProject, coordinate.groupId(), coordinate.artifactId());
                    if (!(matches instanceof Iterable<?> dependencies)) continue;
                    for (Object dependency : dependencies) {
                        Object rawVersion = dependency.getClass().getMethod("getVersion").invoke(dependency);
                        if (rawVersion == null) continue;
                        String version = rawVersion.toString().trim();
                        Path jar = DriverCatalog.discoveredJar(type, version, localRepository);
                        if (isValidDriverJar(type, version, jar))
                            return Optional.of(new InstalledDriver(type, version, InstalledDriver.Kind.DISCOVERED, jar));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Maven's effective model may not be ready yet; the POM reader below handles that startup window.
        }
        return Optional.empty();
    }

    private static List<Coordinate> driverCoordinates(DatabaseType type) {
        return switch (type) {
            case MYSQL -> List.of(new Coordinate("com.mysql", "mysql-connector-j"),
                    new Coordinate("mysql", "mysql-connector-java"));
            case HSQLDB -> List.of(new Coordinate("org.hsqldb", "hsqldb"));
        };
    }

    private static List<Path> projectPoms(Path rootPom, Map<Path, PomModel> models) {
        ArrayDeque<Path> pending = new ArrayDeque<>();
        Set<Path> seen = new LinkedHashSet<>();
        List<Path> poms = new ArrayList<>();
        pending.add(canonical(rootPom));
        while (!pending.isEmpty()) {
            Path pom = pending.removeFirst();
            if (!seen.add(pom) || !Files.isRegularFile(pom)) continue;
            poms.add(pom);
            PomModel model = readPom(pom, new LinkedHashSet<>(), models);
            for (String module : model.modules()) {
                Path modulePath = pom.getParent().resolve(module).normalize();
                Path modulePom = modulePath.getFileName() != null && modulePath.getFileName().toString().equals("pom.xml")
                        ? modulePath : modulePath.resolve("pom.xml");
                pending.addLast(canonical(modulePom));
            }
        }
        return poms;
    }

    private static Path canonical(Path path) {
        try { return path.toRealPath(); }
        catch (java.io.IOException missing) { return path.toAbsolutePath().normalize(); }
    }

    private static PomModel readPom(Path pom, Set<Path> reading, Map<Path, PomModel> models) {
        Path normalized = canonical(pom);
        PomModel cached = models.get(normalized);
        if (cached != null) return cached;
        PomModel model = readUncachedPom(normalized, reading, models);
        models.put(normalized, model);
        return model;
    }

    private static PomModel readUncachedPom(Path normalized, Set<Path> reading, Map<Path, PomModel> models) {
        Path pom = normalized;
        if (!reading.add(normalized) || !Files.isRegularFile(normalized)) return PomModel.empty();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(normalized.toFile());
            Element project = document.getDocumentElement();

            PomModel parent = readParent(pom, project, reading, models);
            Map<String, String> properties = new LinkedHashMap<>(parent.properties());
            Element propertiesElement = child(project, "properties");
            if (propertiesElement != null) {
                for (Element property : children(propertiesElement))
                    properties.put(localName(property), property.getTextContent().trim());
            }

            String parentVersion = text(child(child(project, "parent"), "version"));
            String parentGroup = text(child(child(project, "parent"), "groupId"));
            String parentArtifact = text(child(child(project, "parent"), "artifactId"));
            String version = first(resolve(text(child(project, "version")), properties), parent.version(), resolve(parentVersion, properties));
            String groupId = first(resolve(text(child(project, "groupId")), properties), parent.groupId(), resolve(parentGroup, properties));
            String artifactId = first(resolve(text(child(project, "artifactId")), properties), resolve(parentArtifact, properties));
            putBuiltIn(properties, "project.version", version);
            putBuiltIn(properties, "pom.version", version);
            putBuiltIn(properties, "version", version);
            putBuiltIn(properties, "project.groupId", groupId);
            putBuiltIn(properties, "pom.groupId", groupId);
            putBuiltIn(properties, "project.artifactId", artifactId);
            putBuiltIn(properties, "pom.artifactId", artifactId);
            putBuiltIn(properties, "project.parent.version", first(parent.version(), resolve(parentVersion, properties)));
            putBuiltIn(properties, "parent.version", first(parent.version(), resolve(parentVersion, properties)));
            // Keep inherited expressions raw: child properties must interpolate them in the child model.

            Map<String, String> managedVersions = new LinkedHashMap<>(parent.managedVersions());
            Element management = child(child(project, "dependencyManagement"), "dependencies");
            for (Element dependency : childrenNamed(management, "dependency")) {
                String group = resolve(text(child(dependency, "groupId")), properties);
                String artifact = resolve(text(child(dependency, "artifactId")), properties);
                String managedVersion = text(child(dependency, "version"));
                if (!group.isBlank() && !artifact.isBlank() && !managedVersion.isBlank())
                    managedVersions.put(group + ":" + artifact, managedVersion);
            }

            Map<String, Dependency> dependenciesByCoordinate = new LinkedHashMap<>();
            for (Dependency dependency : parent.dependencies())
                dependenciesByCoordinate.put(dependency.groupId() + ":" + dependency.artifactId(), dependency);
            for (Element dependency : childrenNamed(child(project, "dependencies"), "dependency")) {
                String group = resolve(text(child(dependency, "groupId")), properties);
                String artifact = resolve(text(child(dependency, "artifactId")), properties);
                String dependencyVersion = text(child(dependency, "version"));
                String coordinate = group + ":" + artifact;
                if (dependencyVersion.isBlank() && dependenciesByCoordinate.containsKey(coordinate))
                    dependencyVersion = dependenciesByCoordinate.get(coordinate).version();
                if (!group.isBlank() && !artifact.isBlank())
                    dependenciesByCoordinate.put(coordinate, new Dependency(group, artifact, dependencyVersion));
            }

            List<String> modules = new ArrayList<>();
            for (Element module : childrenNamed(child(project, "modules"), "module")) {
                String value = resolve(module.getTextContent().trim(), properties);
                if (!value.isBlank()) modules.add(value);
            }
            return new PomModel(groupId, artifactId, version, Map.copyOf(properties),
                    Map.copyOf(managedVersions), List.copyOf(dependenciesByCoordinate.values()), List.copyOf(modules));
        } catch (Exception | LinkageError invalidPom) {
            return PomModel.empty();
        } finally {
            reading.remove(normalized);
        }
    }

    private static PomModel readParent(Path pom, Element project, Set<Path> reading, Map<Path, PomModel> models) {
        Element parent = child(project, "parent");
        if (parent == null) return PomModel.empty();
        Element relativePathElement = child(parent, "relativePath");
        String relativePath = relativePathElement == null ? "../pom.xml" : relativePathElement.getTextContent().trim();
        if (relativePath.isEmpty()) return PomModel.empty();
        Path parentPath = pom.getParent().resolve(relativePath).normalize();
        if (Files.isDirectory(parentPath)) parentPath = parentPath.resolve("pom.xml");
        PomModel model = readPom(parentPath, reading, models);
        // Maven ignores a relative-path POM whose GAV does not match the declared parent.
        if (!model.groupId().equals(resolve(text(child(parent, "groupId")), model.properties()))
                || !model.artifactId().equals(resolve(text(child(parent, "artifactId")), model.properties()))
                || !model.version().equals(resolve(text(child(parent, "version")), model.properties())))
            return PomModel.empty();
        return model;
    }

    private static boolean isDriver(DatabaseType type, String groupId, String artifactId) {
        return switch (type) {
            case MYSQL -> groupId.equals("com.mysql") && artifactId.equals("mysql-connector-j")
                    || groupId.equals("mysql") && artifactId.equals("mysql-connector-java");
            case HSQLDB -> groupId.equals("org.hsqldb") && artifactId.equals("hsqldb");
        };
    }

    private static boolean isValidDriverJar(DatabaseType type, String version, Path jar) {
        if (jar == null || !Files.isRegularFile(jar)
                || !version.equals(DriverCatalog.versionOf(type, jar.getFileName().toString()))) return false;
        try {
            return DriverCatalog.validateJar(type, jar) != null;
        } catch (Exception | LinkageError invalidJar) {
            return false;
        }
    }

    private static void putBuiltIn(Map<String, String> properties, String key, String value) {
        if (value != null && !value.isBlank()) properties.put(key, value);
    }

    private static String resolve(String value, Map<String, String> properties) {
        String result = value == null ? "" : value.trim();
        for (int pass = 0; pass < 10; pass++) {
            Matcher matcher = PROPERTY.matcher(result);
            StringBuffer expanded = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) continue;
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
                changed = true;
            }
            matcher.appendTail(expanded);
            result = expanded.toString().trim();
            if (!changed) break;
        }
        return result;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName(element).equals(name)) return element;
        }
        return null;
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        if (parent == null) return List.of();
        List<Element> result = new ArrayList<>();
        for (Element element : children(parent)) if (localName(element).equals(name)) result.add(element);
        return result;
    }

    private static List<Element> children(Element parent) {
        if (parent == null) return List.of();
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element) result.add(element);
        return result;
    }

    private static String localName(Element element) {
        String name = element.getLocalName();
        if (name != null) return name;
        name = element.getTagName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }

    private record Dependency(String groupId, String artifactId, String version) {}
    private record Coordinate(String groupId, String artifactId) {}

    private record PomModel(String groupId, String artifactId, String version,
                            Map<String, String> properties, Map<String, String> managedVersions,
                            List<Dependency> dependencies, List<String> modules) {
        static PomModel empty() {
            return new PomModel("", "", "", Map.of(), Map.of(), List.of(), List.of());
        }
    }
}
