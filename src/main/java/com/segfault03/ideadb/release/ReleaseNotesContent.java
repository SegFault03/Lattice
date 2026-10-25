package com.segfault03.ideadb.release;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Curated guides and screenshots shipped with the same version as the plugin. */
public final class ReleaseNotesContent {
    public record Feature(String id, String title, String description, List<String> steps, String image, String alt) {}
    private static final Properties CONTENT = load();
    private ReleaseNotesContent() {}

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = ReleaseNotesContent.class.getResourceAsStream("/release-notes/release.properties")) {
            if (input == null) throw new IllegalStateException("Missing release notes");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException error) { throw new IllegalStateException("Cannot read release notes", error); }
        return properties;
    }
    public static String version() {
        String version = CONTENT.getProperty("version");
        // Standalone Swing previews read source resources before Gradle expansion.
        return version.startsWith("${") ? CONTENT.getProperty("edition") : version;
    }
    public static String since() { return CONTENT.getProperty("since"); }
    public static String summary() { return CONTENT.getProperty("summary"); }
    public static List<Feature> features() {
        return Arrays.stream(CONTENT.getProperty("features").split(",")).map(id -> new Feature(id,
                CONTENT.getProperty(id + ".title"), CONTENT.getProperty(id + ".description"),
                List.of(CONTENT.getProperty(id + ".steps").split("\\|")),
                "/release-notes/images/" + CONTENT.getProperty(id + ".image"), CONTENT.getProperty(id + ".alt"))).toList();
    }
    public static String changelog() {
        try (InputStream input = ReleaseNotesContent.class.getResourceAsStream("/release-notes/CHANGELOG.md")) {
            if (input == null) return "See the full changelog on GitHub for earlier releases.";
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String heading = "## [" + version() + "]";
            int start = text.indexOf(heading);
            if (start < 0) start = text.indexOf("## [" + CONTENT.getProperty("edition") + "]");
            if (start < 0) return text;
            int end = text.indexOf("\n## [", start + heading.length());
            return text.substring(start, end < 0 ? text.length() : end).strip();
        } catch (IOException error) { throw new IllegalStateException("Cannot read changelog", error); }
    }
}
