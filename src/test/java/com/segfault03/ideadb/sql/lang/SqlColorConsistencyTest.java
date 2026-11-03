package com.segfault03.ideadb.sql.lang;

import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every surface that renders SQL takes its schema colors from {@link SqlSemanticColors}: the editor, the editors embedded
 * in the plugin's UI, the Swing panes and the hover cards. These checks keep it so: no other class defines a role's color,
 * the bundled color schemes say what the code says, and a class that colors SQL in a Swing pane is one this list knows about.
 */
class SqlColorConsistencyTest {
    private static final Path MAIN = Path.of("src/main/java");

    /** The classes that paint SQL, each of which gets its role colors from {@link SqlSemanticColoring} and {@link SqlSemanticColors}. */
    private static final Set<String> PAINTERS = Set.of("SqlSemanticAnnotator", "SqlEmbeddedValidation", "SqlSwingHighlighter", "SqlDocPalette", "SqlColorFamilyPreview");
    /** The classes that show SQL in a Swing pane, through {@code SqlSwingHighlighter} with the connection's schema. */
    private static final Set<String> PANES = Set.of("GeneratedSqlDialog", "CreateTablePanel", "AlterTablePanel", "SqlParameterPanel");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) { return files.filter(path -> path.toString().endsWith(".java")).toList(); }
    }

    private static String name(Path path) { return path.getFileName().toString().replace(".java", ""); }

    private static String hex(java.awt.Color color) { return String.format("%06X", color.getRGB() & 0xFFFFFF); }

    /** The SQL support's own code and the panes that show SQL; other colors in the plugin are not the SQL scheme, whatever they equal. */
    private static boolean rendersSql(Path path) { return path.toString().contains("/ideadb/sql/") || PANES.contains(name(path)); }

    @Test void noOtherClassDefinesARolesColor() throws IOException {
        for (Path path : sources()) {
            if (name(path).equals("SqlSemanticColors") || !rendersSql(path)) continue;
            String text = Files.readString(path).toUpperCase(java.util.Locale.ROOT);
            for (Role role : Role.values()) {
                for (ColorFamily family : ColorFamily.values()) for (boolean dark : new boolean[] {true, false}) {
                    String value = hex(role.defaultColor(dark, family));
                    assertFalse(text.contains("0X" + value) || text.contains("\"#" + value + "\""),
                            name(path) + " defines the color of " + role + " (" + value + "); take it from SqlSemanticColors");
                }
            }
        }
    }

    @Test void theBundledSchemesSayWhatTheCodeSays() throws Exception {
        for (boolean dark : new boolean[] {true, false}) {
            Path file = Path.of("src/main/resources/colorSchemes", dark ? "LatticeSqlSemanticDarcula.xml" : "LatticeSqlSemanticDefault.xml");
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            var options = document.getDocumentElement().getChildNodes();
            java.util.Map<String, String> foregrounds = new java.util.HashMap<>();
            java.util.Map<String, Integer> fonts = new java.util.HashMap<>();
            for (int i = 0; i < options.getLength(); i++) {
                if (!(options.item(i) instanceof Element option) || !option.getTagName().equals("option")) continue;
                String key = option.getAttribute("name");
                assertNull(fonts.put(key, 0), file + ": " + key + " is listed twice");
                var values = option.getElementsByTagName("option");
                for (int j = 0; j < values.getLength(); j++) {
                    Element value = (Element) values.item(j);
                    if (value.getAttribute("name").equals("FOREGROUND")) foregrounds.put(key, value.getAttribute("value").toUpperCase(java.util.Locale.ROOT));
                    if (value.getAttribute("name").equals("FONT_TYPE")) fonts.put(key, Integer.parseInt(value.getAttribute("value")));
                }
            }
            Set<String> known = new java.util.HashSet<>();
            for (Role role : Role.values()) {
                for (ColorFamily family : ColorFamily.values()) {
                    String key = role.key(family).getExternalName();
                    if (!known.add(key)) continue;
                    boolean derived = family == ColorFamily.NEUTRAL && role.byFamily();
                    if (derived && role == Role.ALIAS) {
                        assertFalse(fonts.containsKey(key), file + ": " + key + " is derived from the scheme's text color and has nothing to set");
                        continue;
                    }
                    assertTrue(fonts.containsKey(key), file + " is missing " + key);
                    assertEquals(role.defaultFontType(family), fonts.get(key), file + ": " + key + " font style");
                    if (derived) assertFalse(foregrounds.containsKey(key), file + ": " + key + " takes the scheme's text color");
                    else assertEquals(hex(role.defaultColor(dark, family)), foregrounds.get(key), file + ": " + key);
                }
            }
            assertEquals(known.stream().filter(fonts::containsKey).collect(Collectors.toSet()), fonts.keySet(), file + " has a key that is no role's");
        }
    }

    @Test void everyClassThatPaintsSqlInASwingPaneIsOneThisListKnows() throws IOException {
        Set<String> users = sources().stream().filter(path -> {
            try { return Files.readString(path).contains("SqlSwingHighlighter.apply("); } catch (IOException e) { throw new RuntimeException(e); }
        }).map(SqlColorConsistencyTest::name).collect(Collectors.toSet());
        users.remove("SqlSwingHighlighter");
        assertEquals(PANES, users, "a new Swing pane that shows SQL is added to PANES, and colors with the connection's schema");
    }

    @Test void theSwingPanesColorWithTheirConnectionsSchema() throws IOException {
        for (Path path : sources()) {
            if (!PANES.contains(name(path))) continue;
            String text = Files.readString(path);
            assertTrue(text.contains("SqlSwingHighlighter.apply(") && (text.contains("apply(sql, config, namespace)") || text.contains("apply(text, config, namespace)")
                    || text.contains("apply(preview, connection, null)")), name(path) + " colors by syntax alone");
        }
    }

    @Test void thePaintersTakeTheirRoleColorsFromTheSharedClasses() throws IOException {
        for (Path path : sources()) {
            if (!PAINTERS.contains(name(path))) continue;
            assertTrue(Files.readString(path).contains("SqlSemanticColors"), name(path) + " does not use SqlSemanticColors");
        }
    }
}
