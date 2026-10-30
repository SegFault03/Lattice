package com.segfault03.ideadb.sql.discovery;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the JDBC URLs a project's configuration files point at: Spring and Quarkus {@code application*.properties} and
 * {@code .yml}, JPA {@code persistence.xml}, Hibernate {@code hibernate.cfg.xml} and {@code .properties}, Tomcat
 * {@code context.xml} and plain {@code jdbc}/{@code database}/{@code db} properties files.
 *
 * <p>Only values under a URL key that start with {@code jdbc:} are read. User names, passwords and every other
 * setting are skipped without being looked at, and what is returned is parsed down to the target database (see
 * {@link ParsedJdbcUrl}). Placeholders such as {@code ${DB_HOST:localhost}} resolve to their default, or to another
 * setting of the same file; the environment is never consulted.
 */
public final class ProjectConfigScanner {
    private static final Pattern NAME = Pattern.compile(
            "(?i)^(application([-_.].+)?\\.(properties|ya?ml)|bootstrap([-_.].+)?\\.(properties|ya?ml)|persistence\\.xml|hibernate\\.cfg\\.xml|hibernate\\.properties"
                    + "|context\\.xml|(jdbc|database|db|datasource)([-_.].+)?\\.properties)$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::-?([^}]*))?}");
    private static final int MAX_DEPTH = 5;

    private ProjectConfigScanner() {}

    /** Whether a file of this name can hold a datasource URL. */
    public static boolean isConfigFileName(@NotNull String name) { return NAME.matcher(name).matches(); }

    /** The databases the file's URLs point at, in file order, without duplicates. */
    public static @NotNull List<ParsedJdbcUrl> urlsIn(@NotNull String fileName, @NotNull CharSequence text) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        List<Map<String, String>> documents = lower.endsWith(".yml") || lower.endsWith(".yaml") ? yamlDocuments(text)
                : List.of(lower.endsWith(".xml") ? xml(text) : properties(text));
        List<ParsedJdbcUrl> found = new ArrayList<>();
        for (Map<String, String> settings : documents) {
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                if (!isUrlKey(entry.getKey())) continue;
                String value = resolve(entry.getValue(), settings, 0);
                if (!value.toLowerCase(Locale.ROOT).startsWith("jdbc:")) continue;
                ParsedJdbcUrl parsed = JdbcUrls.parse(value);
                if (parsed != null && !found.contains(parsed)) found.add(parsed);
            }
        }
        return found;
    }

    /** {@code spring.datasource.url}, {@code hibernate.connection.url}, {@code quarkus.datasource.jdbc.url}, {@code url} under a datasource... */
    private static boolean isUrlKey(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return last.equals("url") || last.equals("jdbcurl") || last.equals("connectionurl");
    }

    // ---- what is never read ------------------------------------------------------------------------------------------

    /** Credentials and other secrets: dropped as each file is read, so no later step can see or keep them. */
    static boolean isSensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String word : new String[] {"password", "passwd", "pwd", "secret", "token", "credential", "apikey", "api-key", "api_key", "privatekey", "username", "user-name", "user_name"}) {
            if (lower.contains(word)) return true;
        }
        String last = lower.substring(lower.lastIndexOf('.') + 1);
        return last.equals("user") || last.equals("login") || last.equals("auth");
    }

    private static void store(Map<String, String> settings, String key, String value) {
        if (!isSensitive(key)) settings.put(key, value);
    }

    // ---- placeholders ----------------------------------------------------------------------------------------------

    /** Replaces {@code ${key:default}} by another setting of the file, else the default, else the unknown marker. */
    private static String resolve(String value, Map<String, String> settings, int depth) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).strip();
            String replacement = depth < MAX_DEPTH ? settings.get(key) : null;
            if (replacement != null) replacement = resolve(replacement, settings, depth + 1);
            else if (matcher.group(2) != null) replacement = resolve(matcher.group(2), settings, depth + 1);
            else replacement = String.valueOf(JdbcUrls.UNKNOWN);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        // Maven resource filtering (@db.url@) and SpEL (#{...}) cannot be resolved here either
        return out.toString().replaceAll("@[\\w.\\-]+@|#\\{[^}]*}", String.valueOf(JdbcUrls.UNKNOWN));
    }

    // ---- .properties -----------------------------------------------------------------------------------------------

    static Map<String, String> properties(CharSequence text) {
        Map<String, String> settings = new LinkedHashMap<>();
        StringBuilder logical = new StringBuilder();
        for (String raw : text.toString().split("\\R", -1)) {
            String line = raw.stripLeading();
            if (logical.length() == 0 && (line.isEmpty() || line.startsWith("#") || line.startsWith("!"))) continue;
            if (endsWithContinuation(line)) { logical.append(line, 0, line.length() - 1); continue; }
            logical.append(line);
            addProperty(settings, logical.toString());
            logical.setLength(0);
        }
        if (logical.length() > 0) addProperty(settings, logical.toString());
        return settings;
    }

    private static boolean endsWithContinuation(String line) {
        int slashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) slashes++;
        return slashes % 2 == 1;
    }

    private static void addProperty(Map<String, String> settings, String line) {
        int split = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '=' || c == ':' || Character.isWhitespace(c)) { split = i; break; }
        }
        String key = split < 0 ? line : line.substring(0, split);
        String value = split < 0 ? "" : line.substring(split);
        value = value.stripLeading();
        if (value.startsWith("=") || value.startsWith(":")) value = value.substring(1).stripLeading();
        store(settings, unescape(key.strip()), unescape(value.stripTrailing()));
    }

    /** Escapes as {@link java.util.Properties} reads them: {@code \:}, {@code \=}, {@code \\}, {@code \ }, {@code \t}, {@code \n}, {@code \r}, {@code \f}, four-digit unicode escapes; any other escaped character is itself. */
    static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i >= raw.length()) break;
            char next = raw.charAt(i);
            switch (next) {
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    int code = hex(raw, i + 1);
                    if (code >= 0) { out.append((char) code); i += 4; } else out.append('u');
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    /** The value of the four hex digits after {@code from - 1}, or -1 when they are not four hex digits. */
    private static int hex(String text, int from) {
        if (from + 4 > text.length()) return -1;
        int value = 0;
        for (int i = from; i < from + 4; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) return -1;
            value = value * 16 + digit;
        }
        return value;
    }

    // ---- .yml ------------------------------------------------------------------------------------------------------

    /** Flattens nested maps to dotted keys. Lists and anchors are skipped: a datasource URL is a plain scalar. */
    static Map<String, String> yaml(CharSequence text) {
        Map<String, String> all = new LinkedHashMap<>();
        yamlDocuments(text).forEach(all::putAll);
        return all;
    }

    /** One map per {@code ---} document: Spring profiles repeat the same keys, and each is its own database. */
    static List<Map<String, String>> yamlDocuments(CharSequence text) {
        List<Map<String, String>> documents = new ArrayList<>();
        Map<String, String> settings = new LinkedHashMap<>();
        documents.add(settings);
        List<int[]> indents = new ArrayList<>();
        List<String> path = new ArrayList<>();
        for (String raw : text.toString().split("\\R", -1)) {
            if (raw.startsWith("---")) {
                indents.clear();
                path.clear();
                settings = new LinkedHashMap<>();
                documents.add(settings);
                continue;
            }
            String line = stripComment(raw);
            if (line.isBlank() || line.stripLeading().startsWith("- ")) continue;
            int indent = line.length() - line.stripLeading().length();
            String content = line.strip();
            int colon = keyEnd(content);
            if (colon < 0) continue;
            String key = unquote(content.substring(0, colon).strip());
            String value = content.substring(colon + 1).strip();
            while (!indents.isEmpty() && indents.get(indents.size() - 1)[0] >= indent) { indents.remove(indents.size() - 1); path.remove(path.size() - 1); }
            if (value.isEmpty() || value.equals("|") || value.equals(">")) {
                indents.add(new int[] {indent});
                path.add(key);
            } else {
                store(settings, path.isEmpty() ? key : String.join(".", path) + "." + key, unquote(value));
            }
        }
        return documents;
    }

    /** The colon that ends the key: the first one followed by a space or the end, outside quotes. */
    private static int keyEnd(String content) {
        char quote = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == ':' && (i + 1 == content.length() || content.charAt(i + 1) == ' ')) return i;
        }
        return -1;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) return line.substring(0, i);
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'') && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // ---- .xml ------------------------------------------------------------------------------------------------------

    /** persistence.xml and hibernate.cfg.xml properties, and the {@code url} attribute of a Tomcat Resource. DTDs are never loaded. */
    static Map<String, String> xml(CharSequence text) {
        Map<String, String> settings = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            var root = builder.parse(new InputSource(new StringReader(text.toString()))).getDocumentElement();
            collect(root, settings);
        } catch (Exception unreadable) {
            // a file that is not well-formed has no URL to offer
        }
        return settings;
    }

    private static void collect(Element element, Map<String, String> settings) {
        String name = element.getAttribute("name");
        if (!name.isEmpty()) {
            String value = element.hasAttribute("value") ? element.getAttribute("value") : directText(element);
            if (!value.isEmpty()) store(settings, name, value);
        }
        if (element.hasAttribute("url")) store(settings, (name.isEmpty() ? element.getTagName() : name) + ".url", element.getAttribute("url"));
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested) collect(nested, settings);
        }
    }

    private static @Nullable String directText(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) text.append(child.getNodeValue());
        }
        return text.toString().strip();
    }
}
