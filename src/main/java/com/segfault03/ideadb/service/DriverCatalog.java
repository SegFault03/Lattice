package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Downloads only known Maven Central coordinates; local JARs are never downloaded implicitly. */
public final class DriverCatalog {
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final long MAX_JAR_BYTES = 32L * 1024 * 1024;
    private static final String RELEASE = "\\d+\\.\\d+\\.\\d+";
    private static final Map<DatabaseType, List<String>> ARTIFACTS = Map.of(
            DatabaseType.MYSQL, List.of("mysql-connector-j", "mysql-connector-java"),
            DatabaseType.HSQLDB, List.of("hsqldb"));
    private static final Map<DatabaseType, List<String>> MAVEN_ARTIFACT_DIRECTORIES = Map.of(
            DatabaseType.MYSQL, List.of("com/mysql/mysql-connector-j", "mysql/mysql-connector-java"),
            DatabaseType.HSQLDB, List.of("org/hsqldb/hsqldb"));
    private DriverCatalog() {}
    public static List<String> suggestedVersions(DatabaseType type) {
        return type == DatabaseType.MYSQL ? List.of("26.7.0", "9.0.0", "8.4.0", "8.0.33", "5.1.49")
                : List.of("2.7.4-jdk8", "2.7.3-jdk8", "2.6.1-jdk8", "2.5.2", "2.4.1", "2.3.6", "2.3.0", "2.2.9");
    }
    public static Path cacheDirectory() {
        String override = System.getProperty("lattice.jdbc.cache");
        if (override != null) return Path.of(override);
        String root = ApplicationManager.getApplication() == null ? System.getProperty("user.home") + "/.lattice" : PathManager.getSystemPath() + "/lattice";
        return Path.of(root, "jdbc");
    }
    public static String artifactPath(DatabaseType type, String selection) {
        if (selection == null || !selection.matches(RELEASE + "(-jdk8)?")) throw new IllegalArgumentException("Enter a release version such as 8.0.33 or 2.7.4-jdk8");
        boolean jdk8 = selection.endsWith("-jdk8");
        if (jdk8 && type != DatabaseType.HSQLDB) throw new IllegalArgumentException("jdk8 variants are available only for HSQLDB");
        String version = jdk8 ? selection.substring(0, selection.length() - 5) : selection;
        String artifact = type == DatabaseType.HSQLDB ? "org/hsqldb/hsqldb" : modernMysql(version) ? "com/mysql/mysql-connector-j" : "mysql/mysql-connector-java";
        String name = artifact.substring(artifact.lastIndexOf('/') + 1);
        return artifact + "/" + version + "/" + name + "-" + version + (jdk8 ? "-jdk8" : "") + ".jar";
    }
    private static boolean modernMysql(String version) {
        String[] p = version.split("\\."); int major = Integer.parseInt(p[0]), minor = Integer.parseInt(p[1]), patch = Integer.parseInt(p[2]);
        return major > 8 || major == 8 && (minor > 0 || patch >= 31);
    }
    public static Path downloadedJar(DatabaseType type, String version) {
        String relative = artifactPath(type, version);
        return cacheDirectory().resolve(type.name().toLowerCase(Locale.ROOT)).resolve(relative.substring(relative.lastIndexOf('/') + 1));
    }
    /** Recovers the release a retained JAR was downloaded for; blank when the name is not ours. */
    public static String versionOf(DatabaseType type, String fileName) {
        if (fileName == null || !fileName.endsWith(".jar")) return "";
        String stem = fileName.substring(0, fileName.length() - 4);
        for (String artifact : ARTIFACTS.get(type)) {
            if (!stem.startsWith(artifact + "-")) continue;
            String version = stem.substring(artifact.length() + 1);
            if (version.matches(RELEASE + "(-jdk8)?")) return version;
        }
        return "";
    }
    /** Versions already retained on this machine, newest first, so they are never downloaded twice. */
    public static List<String> downloadedVersions(DatabaseType type) {
        Path directory = cacheDirectory().resolve(type.name().toLowerCase(Locale.ROOT));
        List<String> versions = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path entry : entries) {
                String version = versionOf(type, entry.getFileName().toString());
                if (!version.isEmpty() && Files.isRegularFile(entry) && !versions.contains(version)) versions.add(version);
            }
        } catch (IOException absent) {
            return List.of();
        }
        versions.sort(DriverCatalog::compareVersionsDescending);
        return List.copyOf(versions);
    }

    /**
     * Find validated driver artifacts in Maven's local repository. The selected repository is
     * supplied by the IDE's Maven project settings; Maven home itself contains the Maven
     * installation, not dependency artifacts.
     */
    public static List<String> discoveredVersions(DatabaseType type, Path localRepository) {
        if (localRepository == null || !Files.isDirectory(localRepository)) return List.of();
        Set<String> versions = new LinkedHashSet<>();
        for (String artifactDirectory : MAVEN_ARTIFACT_DIRECTORIES.get(type)) {
            Path versionsDirectory = localRepository.resolve(artifactDirectory);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(versionsDirectory)) {
                for (Path entry : entries) {
                    if (!Files.isDirectory(entry)) continue;
                    String baseVersion = entry.getFileName().toString();
                    if (!baseVersion.matches(RELEASE)) continue;
                    addDiscoveredVersion(type, localRepository, baseVersion, versions);
                    if (type == DatabaseType.HSQLDB)
                        addDiscoveredVersion(type, localRepository, baseVersion + "-jdk8", versions);
                }
            } catch (IOException | SecurityException ignored) {
                // A missing artifact directory is normal when the driver was never resolved by Maven.
            }
        }
        List<String> sorted = new ArrayList<>(versions);
        sorted.sort(DriverCatalog::compareVersionsDescending);
        return List.copyOf(sorted);
    }

    private static void addDiscoveredVersion(DatabaseType type, Path localRepository, String version,
                                             Set<String> versions) {
        Path jar = discoveredJar(type, version, localRepository);
        if (jar == null || !Files.isRegularFile(jar)) return;
        try {
            validateJar(type, jar);
            versions.add(version);
        } catch (IOException | SecurityException ignored) {
            // Ignore incomplete downloads, source jars, and unrelated/corrupt files.
        }
    }

    /** Canonical JAR path for a Maven-cached release, or null for an invalid version. */
    public static Path discoveredJar(DatabaseType type, String version, Path localRepository) {
        if (localRepository == null) return null;
        try {
            return localRepository.resolve(artifactPath(type, version));
        } catch (IllegalArgumentException invalidVersion) {
            return null;
        }
    }
    /** A retained JAR is re-validated before reuse, so a damaged file is never handed to a class loader. */
    public static boolean isInstalled(DatabaseType type, String version) {
        try {
            Path jar = downloadedJar(type, version);
            return Files.isRegularFile(jar) && validateJar(type, jar) != null;
        } catch (IOException | IllegalArgumentException | SecurityException unusable) {
            return false;
        }
    }
    /**
     * Maven's descending release order, with the {@code -jdk8} build of a release ahead of the plain
     * artifact because it is the variant this plugin suggests for HSQLDB.
     */
    static int compareVersionsDescending(String left, String right) {
        String[] first = left.split("\\."), second = right.split("\\.");
        for (int index = 0; index < Math.max(first.length, second.length); index++) {
            int difference = numberAt(second, index) - numberAt(first, index);
            if (difference != 0) return difference;
        }
        return Boolean.compare(right.endsWith("-jdk8"), left.endsWith("-jdk8"));
    }
    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replace("-jdk8", ""));
        } catch (NumberFormatException suffix) {
            return 0;
        }
    }
    public static List<String> availableVersions(DatabaseType type) throws IOException {
        List<String> paths = type == DatabaseType.MYSQL ? List.of("mysql/mysql-connector-java", "com/mysql/mysql-connector-j") : List.of("org/hsqldb/hsqldb");
        Set<String> versions = new LinkedHashSet<>(suggestedVersions(type));
        Pattern pattern = Pattern.compile("<version>([0-9]+\\.[0-9]+\\.[0-9]+)</version>");
        for (String path : paths) {
            String metadata = new String(readUrl(CENTRAL + path + "/maven-metadata.xml", 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
            var matches = pattern.matcher(metadata);
            while (matches.find()) {
                String version = matches.group(1);
                if (type == DatabaseType.MYSQL && Integer.parseInt(version.split("\\.")[0]) < 5) continue;
                versions.add(version);
                if (type == DatabaseType.HSQLDB && version.matches("2\\.[67]\\.[0-9]+")) versions.add(version + "-jdk8");
            }
        }
        return List.copyOf(versions);
    }
    public static Path download(DatabaseType type, String version) throws Exception {
        String url = CENTRAL + artifactPath(type, version);
        Path target = downloadedJar(type, version);
        // A retained JAR is already checksum-verified; reuse it instead of fetching it again.
        if (isInstalled(type, version)) return target;
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "jdbc-", ".part");
        try {
            byte[] bytes = readUrl(url, MAX_JAR_BYTES);
            String algorithm = null, expected = null;
            for (String candidate : List.of("SHA-512", "SHA-256", "SHA-1")) {
                String suffix = candidate.toLowerCase(Locale.ROOT).replace("-", "");
                try { expected = new String(readUrl(url + "." + suffix, 1024), java.nio.charset.StandardCharsets.US_ASCII).trim().split("\\s+")[0]; algorithm = candidate; break; }
                catch (FileNotFoundException absent) { /* old Maven artifacts may only provide SHA-1 */ }
            }
            if (algorithm == null || !HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes)).equalsIgnoreCase(expected)) throw new IOException("Driver download checksum verification failed");
            Files.write(temporary, bytes); validateJar(type, temporary);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static byte[] readUrl(String address, long limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
                if ((long)bytes.size() + n > limit) throw new IOException("Download exceeds size limit");
                bytes.write(buffer, 0, n);
            }
            return bytes.toByteArray();
        } finally { connection.disconnect(); }
    }
    public static String validateJar(DatabaseType type, Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Driver JAR not found: " + path);
        try (JarFile jar = new JarFile(path.toFile())) {
            List<String> classes = type == DatabaseType.MYSQL ? List.of("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver") : List.of("org.hsqldb.jdbc.JDBCDriver", "org.hsqldb.jdbcDriver");
            for (String name : classes) if (jar.getJarEntry(name.replace('.', '/') + ".class") != null) return name;
            throw new IOException("Selected JAR does not contain a " + type.getDisplayName() + " JDBC driver");
        }
    }
}
