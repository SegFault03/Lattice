package com.segfault03.ideadb.sql.lang;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColorUtil;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/**
 * The colors of names the schema gives a meaning to: tables, views and aliases, and columns by what they are (primary
 * key, foreign key) or else by the kind of value they hold. The editor, the editors and panes embedded in the plugin's UI
 * and the hover cards read the same roles, so a primary key looks the same in all of them.
 *
 * <p>Table, view and alias names come in {@link ColorFamily families} the user chooses between, each with keys of its own
 * (coral's keep the plain names), so a family's colors can be set in the color scheme without touching another's. A
 * scheme's own value for a key wins; a scheme without one (the new UI's own schemes do not inherit the bundled ones) gets
 * the light or dark default that matches its background, and the neutral family derives its colors from the scheme's text
 * color.
 */
public final class SqlSemanticColors {
    /** A color for a dark scheme and one for a light scheme, and a font style. */
    private record Look(Color dark, Color light, int fontType) {}

    private static Look look(int dark, int light, int fontType) { return new Look(new Color(dark), new Color(light), fontType); }

    /** The same look in every family. */
    private static Look[] always(Look look) { return new Look[] {look, look, look, look}; }

    /** One look per family, in the order of {@link ColorFamily}: coral, periwinkle, lime, neutral. */
    private static Look[] looksFor(Look coral, Look periwinkle, Look lime, Look neutral) { return new Look[] {coral, periwinkle, lime, neutral}; }

    public enum Role {
        TABLE("Table", "LATTICE_SQL_TABLE", true, looksFor(
                look(0xF47C7C, 0xC62828, Font.BOLD), look(0x8EA2FF, 0x3949AB, Font.BOLD), look(0xA8D45C, 0x5C7D00, Font.BOLD), look(0xF2F3F5, 0x111111, Font.BOLD))),
        VIEW("View", "LATTICE_SQL_VIEW", true, looksFor(
                look(0xF29B8F, 0xB5483A, Font.BOLD | Font.ITALIC), look(0xA7B1FF, 0x4A56C0, Font.BOLD | Font.ITALIC),
                look(0xC6D45C, 0x7A7A00, Font.BOLD | Font.ITALIC), look(0xF2F3F5, 0x111111, Font.BOLD | Font.ITALIC))),
        ALIAS("Table alias", "LATTICE_SQL_ALIAS", true, looksFor(
                look(0xF7B0B0, 0xCC4A4A, Font.PLAIN), look(0xB8C0FF, 0x6272D4, Font.PLAIN), look(0xC2E08A, 0x7A9A2E, Font.PLAIN), look(0x9DA0A8, 0x6B6F76, Font.PLAIN))),
        PRIMARY_KEY("Primary key column", "LATTICE_SQL_COLUMN_PRIMARY_KEY", false, always(look(0xE5B567, 0x996600, Font.BOLD))),
        FOREIGN_KEY("Foreign key column", "LATTICE_SQL_COLUMN_FOREIGN_KEY", false, always(look(0xB794F4, 0x6F42C1, Font.BOLD))),
        NUMERIC("Numeric column", "LATTICE_SQL_COLUMN_NUMERIC", false, always(look(0x6CA6DD, 0x0B5CAD, Font.PLAIN))),
        TEXT("Text column", "LATTICE_SQL_COLUMN_TEXT", false, always(look(0x7EC699, 0x2E7D32, Font.PLAIN))),
        TEMPORAL("Date and time column", "LATTICE_SQL_COLUMN_TEMPORAL", false, always(look(0xE49A72, 0xB5530E, Font.PLAIN))),
        BOOLEAN("Boolean column", "LATTICE_SQL_COLUMN_BOOLEAN", false, always(look(0x5FBFC0, 0x00798C, Font.PLAIN))),
        BINARY("Binary column", "LATTICE_SQL_COLUMN_BINARY", false, always(look(0xD58AB8, 0xA0306E, Font.PLAIN))),
        OTHER("Other column", "LATTICE_SQL_COLUMN_OTHER", false, always(look(0xC2A878, 0x7A5C1C, Font.PLAIN)));

        private final String label;
        private final boolean byFamily;
        private final Look[] looks;
        private final TextAttributesKey[] keys;

        Role(String label, String keyName, boolean byFamily, Look[] looks) {
            this.label = label;
            this.byFamily = byFamily;
            this.looks = looks;
            ColorFamily[] families = ColorFamily.values();
            this.keys = new TextAttributesKey[families.length];
            // Coral's keys keep the plain names; a column role has one key whatever the family.
            for (ColorFamily family : families) {
                String suffix = !byFamily || family == ColorFamily.CORAL ? "" : "_" + family.name();
                keys[family.ordinal()] = byFamily || family == ColorFamily.CORAL ? createTextAttributesKey(keyName + suffix) : keys[ColorFamily.CORAL.ordinal()];
            }
        }

        /** The name shown under Settings | Editor | Color Scheme | Lattice SQL. */
        public @NotNull String label() { return label; }
        /** Whether the role looks different in each family: table, view and alias do, columns do not. */
        public boolean byFamily() { return byFamily; }
        /** The key of the role in the family the user chose. */
        public @NotNull TextAttributesKey key() { return key(byFamily ? family() : ColorFamily.CORAL); }
        public @NotNull TextAttributesKey key(@NotNull ColorFamily family) { return keys[family.ordinal()]; }
        /** The role's distinct keys: one for a column role, one per family for the others. */
        public @NotNull List<TextAttributesKey> keys() { return List.copyOf(new LinkedHashSet<>(List.of(keys))); }
        /** The default color for a dark or a light scheme, in the family the user chose. */
        public @NotNull Color defaultColor(boolean dark) { return defaultColor(dark, family()); }
        public @NotNull Color defaultColor(boolean dark, @NotNull ColorFamily family) {
            Look look = looks[family.ordinal()];
            return dark ? look.dark : look.light;
        }
        public int defaultFontType() { return defaultFontType(family()); }
        public int defaultFontType(@NotNull ColorFamily family) { return looks[family.ordinal()].fontType; }
    }

    private SqlSemanticColors() {}

    /** The family the user chose for table, view and alias names; periwinkle, the default, when there is no IDE (plain unit tests). */
    public static @NotNull ColorFamily family() {
        if (ApplicationManager.getApplication() == null) return ColorFamily.PERIWINKLE;
        try {
            return SqlEditorSettings.getInstance().schemaColorFamily();
        } catch (RuntimeException unavailable) {
            return ColorFamily.PERIWINKLE;
        }
    }

    /** What the role looks like in the user's chosen family; see {@link #attributes(EditorColorsScheme, Role, ColorFamily)}. */
    public static @NotNull TextAttributes attributes(@NotNull EditorColorsScheme scheme, @NotNull Role role) {
        return attributes(scheme, role, family());
    }

    /**
     * What the role looks like in a family in a scheme: the scheme's own color and style when it sets a color for the
     * role's key, else the family's default for the scheme's brightness (neutral names take the scheme's text color).
     * A color that is only the plain text color says nothing and counts as unset.
     */
    public static @NotNull TextAttributes attributes(@NotNull EditorColorsScheme scheme, @NotNull Role role, @NotNull ColorFamily family) {
        TextAttributes own = scheme.getAttributes(role.key(family));
        Color color = own == null ? null : own.getForegroundColor();
        if (color != null && !color.equals(scheme.getDefaultForeground())) return new TextAttributes(color, null, null, null, own.getFontType());
        return new TextAttributes(defaultColor(scheme, role, family), null, null, null, role.defaultFontType(family));
    }

    private static Color defaultColor(EditorColorsScheme scheme, Role role, ColorFamily family) {
        if (family != ColorFamily.NEUTRAL || !role.byFamily()) return role.defaultColor(isDark(scheme), family);
        return neutralColor(role, scheme.getDefaultForeground(), scheme.getDefaultBackground());
    }

    /** The neutral family's color for a role over a text color and background: names are the text color, aliases it faded toward the background. */
    public static @NotNull Color neutralColor(@NotNull Role role, @NotNull Color text, @NotNull Color background) {
        return role == Role.ALIAS ? ColorUtil.mix(text, background, 0.42) : text;
    }

    /** The role's color in the user's family as "#RRGGBB", for HTML. */
    public static @NotNull String hex(@NotNull EditorColorsScheme scheme, @NotNull Role role) {
        return hex(attributes(scheme, role).getForegroundColor());
    }

    public static @NotNull String hex(@NotNull Color color) {
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    public static boolean isDark(@NotNull EditorColorsScheme scheme) { return ColorUtil.isDark(scheme.getDefaultBackground()); }

    /** The role of a column's name: primary key first, then foreign key, else by the kind of value it holds. */
    public static @NotNull Role roleOf(@NotNull ColumnMetadata column) {
        if (column.isPrimaryKey()) return Role.PRIMARY_KEY;
        if (isForeignKey(column)) return Role.FOREIGN_KEY;
        return typeRole(column);
    }

    public static boolean isForeignKey(@NotNull ColumnMetadata column) { return column.isForeignKey() || column.getReference() != null; }

    /** The role of a column's type, by what kind of values it holds. */
    public static @NotNull Role typeRole(@NotNull ColumnMetadata column) {
        String name = column.getTypeName() == null ? "" : column.getTypeName().toUpperCase(Locale.ROOT);
        if (name.contains("JSON") || name.contains("XML") || name.contains("UUID") || name.contains("ENUM") || name.equals("SET")) return Role.OTHER;
        return switch (column.getDataType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> Role.NUMERIC;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.SQLXML -> Role.TEXT;
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> Role.TEMPORAL;
            case Types.BIT, Types.BOOLEAN -> Role.BOOLEAN;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Role.BINARY;
            default -> Role.OTHER;
        };
    }
}
