package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.DatabaseType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Downloads only known Maven Central coordinates; local JARs are never downloaded implicitly. */
public final class DriverCatalog {
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final long MAX_JAR_BYTES = 32L * 1024 * 1024;
    private static final long ABANDONED_PART_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;
    private static final String RELEASE = "\\d+\\.\\d+\\.\\d+";
    private static final Map<DatabaseType, List<String>> ARTIFACTS = Map.of(
            DatabaseType.MYSQL, List.of("mysql-connector-j", "mysql-connector-java"),
            DatabaseType.HSQLDB, List.of("hsqldb"));
    private static final Map<DatabaseType, List<String>> MAVEN_ARTIFACT_DIRECTORIES = Map.of(
            DatabaseType.MYSQL, List.of("com/mysql/mysql-connector-j", "mysql/mysql-connector-java"),
            DatabaseType.HSQLDB, List.of("org/hsqldb/hsqldb"));
    private record ValidationKey(DatabaseType type, Path path) {}
    private record ValidatedJar(java.nio.file.attribute.BasicFileAttributes attributes, String driverClass) {}
    // Discovery repeatedly inspects the same artifacts. Cache successful reads, never failures.
    private static final Map<ValidationKey, ValidatedJar> VALIDATED_JARS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<ValidationKey, ValidatedJar> entry) {
                    return size() > 256;
                }
            });
    private static final ConcurrentMap<Path, Object> DOWNLOAD_LOCKS = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface DownloadProgress {
        void onProgress(long bytesReceived, long totalBytes);
    }

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
                if (version.isEmpty() || !Files.isRegularFile(entry) || versions.contains(version)) continue;
                if (isInstalled(type, version)) versions.add(version);
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
            Path preferred = localRepository.resolve(artifactPath(type, version));
            if (isValidDiscoveredJar(type, version, preferred)) return preferred;
            String folderVersion = type == DatabaseType.HSQLDB && version.endsWith("-jdk8")
                    ? version.substring(0, version.length() - "-jdk8".length()) : version;
            for (String artifactDirectory : MAVEN_ARTIFACT_DIRECTORIES.get(type)) {
                String artifact = artifactDirectory.substring(artifactDirectory.lastIndexOf('/') + 1);
                Path candidate = localRepository.resolve(artifactDirectory).resolve(folderVersion)
                        .resolve(artifact + "-" + version + ".jar");
                if (isValidDiscoveredJar(type, version, candidate)) return candidate;
            }
            return preferred;
        } catch (IllegalArgumentException invalidVersion) {
            return null;
        }
    }

    private static boolean isValidDiscoveredJar(DatabaseType type, String version, Path jar) {
        if (!Files.isRegularFile(jar) || !version.equals(versionOf(type, jar.getFileName().toString()))) return false;
        try {
            validateJar(type, jar);
            return true;
        } catch (IOException | SecurityException invalidJar) {
            return false;
        }
    }
    /** A retained JAR is re-validated before reuse, so a damaged file is never handed to a class loader. */
    public static boolean isInstalled(DatabaseType type, String version) {
        try {
            Path jar = downloadedJar(type, version);
            if (!Files.isRegularFile(jar)) return false;
            try {
                validateJar(type, jar);
                return true;
            } catch (InterruptedIOException cancelled) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException invalidJar) {
                Files.deleteIfExists(jar);
                return false;
            }
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
        return download(type, version, (bytesReceived, totalBytes) -> {});
    }

    /** Downloads and verifies an artifact before atomically adding it to the retained driver store. */
    public static Path download(DatabaseType type, String version, DownloadProgress progress) throws Exception {
        String url = CENTRAL + artifactPath(type, version);
        Path target = downloadedJar(type, version);
        Path directory = target.getParent();
        Object lock = DOWNLOAD_LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize(), ignored -> new Object());
        synchronized (lock) {
            // A retained JAR is already checksum-verified; reuse it instead of fetching it again.
            if (isInstalled(type, version)) return target;
            Files.createDirectories(directory);
            deleteAbandonedParts(directory);
            if (Files.exists(target) && !Files.isRegularFile(target))
                throw new IOException("Driver cache target is not a regular file: " + target);
            // A damaged cache entry must not survive a failed retry or appear as an available driver.
            Files.deleteIfExists(target);
            Path temporary = Files.createTempFile(directory, "jdbc-", ".part");
            try {
                DownloadProgress listener = progress == null ? (received, total) -> {} : progress;
                long bytesReceived = readUrlToFile(url, temporary, MAX_JAR_BYTES, listener);
                String algorithm = null, expected = null;
                for (String candidate : List.of("SHA-512", "SHA-256", "SHA-1")) {
                    String suffix = candidate.toLowerCase(Locale.ROOT).replace("-", "");
                    try {
                        expected = new String(readUrl(url + "." + suffix, 1024), java.nio.charset.StandardCharsets.US_ASCII)
                                .trim().split("\\s+")[0];
                        algorithm = candidate;
                        break;
                    } catch (FileNotFoundException absent) {
                        // Older Maven artifacts may only provide SHA-1.
                    }
                }
                if (algorithm == null || !checksum(temporary, algorithm).equalsIgnoreCase(expected))
                    throw new IOException("Driver download checksum verification failed");
                validateJar(type, temporary);
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                listener.onProgress(bytesReceived, bytesReceived);
                return target;
            } finally {
                // Also removes incomplete data if the connection drops or the dialog is cancelled.
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void deleteAbandonedParts(Path directory) throws IOException {
        long staleBefore = System.currentTimeMillis() - ABANDONED_PART_MAX_AGE_MILLIS;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(directory, "jdbc-*.part")) {
            for (Path part : parts) {
                try {
                    if (Files.getLastModifiedTime(part).toMillis() < staleBefore) Files.deleteIfExists(part);
                } catch (IOException staleFileUnavailable) {
                    // An unreadable stale file does not block an otherwise valid driver download.
                }
            }
        }
    }

    private static long readUrlToFile(String address, Path destination, long limit, DownloadProgress progress) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        try {
            long totalBytes = connection.getContentLengthLong();
            if (totalBytes > limit) throw new IOException("Download exceeds size limit");
            progress.onProgress(0, totalBytes);
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                long bytesReceived = 0;
                long lastReportedBytes = 0;
                long lastReportedAt = System.nanoTime();
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
                    bytesReceived += count;
                    if (bytesReceived > limit) throw new IOException("Download exceeds size limit");
                    output.write(buffer, 0, count);
                    long now = System.nanoTime();
                    if (bytesReceived - lastReportedBytes >= 256 * 1024 || now - lastReportedAt >= 100_000_000L) {
                        progress.onProgress(bytesReceived, totalBytes);
                        lastReportedBytes = bytesReceived;
                        lastReportedAt = now;
                    }
                }
                if (totalBytes >= 0 && bytesReceived != totalBytes)
                    throw new IOException("Driver download ended before all bytes arrived");
                progress.onProgress(bytesReceived, totalBytes);
                return bytesReceived;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String checksum(Path file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
        Path realPath = path.toRealPath();
        var attributes = Files.readAttributes(realPath, java.nio.file.attribute.BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Driver JAR not found: " + path);
        var key = new ValidationKey(type, realPath);
        var cached = VALIDATED_JARS.get(key);
        if (cached != null && sameFile(cached.attributes(), attributes)) return cached.driverClass();
        List<String> classes = type == DatabaseType.MYSQL
                ? List.of("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver")
                : List.of("org.hsqldb.jdbc.JDBCDriver", "org.hsqldb.jdbcDriver");
        String driverClass = null;
        try (JarFile jar = new JarFile(realPath.toFile())) {
            // A readable central directory does not prove the entry data is intact.
            long total = 0;
            byte[] buffer = new byte[8192];
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Driver validation cancelled");
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                var crc = new java.util.zip.CRC32();
                long bytes = 0;
                int magic = 0;
                try (InputStream input = jar.getInputStream(entry)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        for (int i = 0; i < count && bytes + i < 4; i++) magic = magic << 8 | buffer[i] & 0xff;
                        bytes += count;
                        total += count;
                        if (total > 128L * 1024 * 1024) throw new IOException("Driver JAR contents exceed size limit");
                        crc.update(buffer, 0, count);
                    }
                }
                if (bytes != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new IOException("Corrupt driver JAR entry: " + entry.getName());
                for (String name : classes) {
                    if (!entry.getName().equals(name.replace('.', '/') + ".class")) continue;
                    if (bytes < 8 || magic != 0xcafebabe) throw new IOException("Invalid JDBC driver class: " + name);
                    if (driverClass == null) driverClass = name;
                }
            }
        } catch (SecurityException invalidSignature) {
            throw new IOException("Driver JAR signature verification failed", invalidSignature);
        }
        if (driverClass == null) throw new IOException("Selected JAR does not contain a " + type.getDisplayName() + " JDBC driver");
        var after = Files.readAttributes(realPath, java.nio.file.attribute.BasicFileAttributes.class);
        if (!sameFile(attributes, after)) throw new IOException("Driver JAR changed during validation");
        VALIDATED_JARS.put(key, new ValidatedJar(after, driverClass));
        return driverClass;
    }

    private static boolean sameFile(java.nio.file.attribute.BasicFileAttributes a,
                                    java.nio.file.attribute.BasicFileAttributes b) {
        return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && a.creationTime().equals(b.creationTime()) && Objects.equals(a.fileKey(), b.fileKey());
    }
}
