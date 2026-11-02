package com.segfault03.ideadb.sql.lang;

import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColorUtil;
import com.segfault03.ideadb.model.ColumnMetadata;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.sql.Types;
import java.util.Locale;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/**
 * The colors of names the schema gives a meaning to: tables, views and aliases, and columns by what they are (primary
 * key, foreign key) or else by the kind of value they hold. The editor and the hover cards read the same roles, so
 * a primary key looks the same in both. A scheme's own value for a role's key wins; a scheme without one (the new
 * UI's own schemes do not inherit the bundled ones) gets the light or dark default that matches its background.
 */
public final class SqlSemanticColors {
    public enum Role {
        TABLE("Table", "LATTICE_SQL_TABLE", 0xA8D45C, 0x5C7D00, Font.BOLD),
        VIEW("View", "LATTICE_SQL_VIEW", 0xC6D45C, 0x7A7A00, Font.BOLD | Font.ITALIC),
        ALIAS("Table alias", "LATTICE_SQL_ALIAS", 0xC2E08A, 0x7A9A2E, Font.PLAIN),
        PRIMARY_KEY("Primary key column", "LATTICE_SQL_COLUMN_PRIMARY_KEY", 0xE5B567, 0x996600, Font.BOLD),
        FOREIGN_KEY("Foreign key column", "LATTICE_SQL_COLUMN_FOREIGN_KEY", 0xB794F4, 0x6F42C1, Font.BOLD),
        NUMERIC("Numeric column", "LATTICE_SQL_COLUMN_NUMERIC", 0x6CA6DD, 0x0B5CAD, Font.PLAIN),
        TEXT("Text column", "LATTICE_SQL_COLUMN_TEXT", 0x7EC699, 0x2E7D32, Font.PLAIN),
        TEMPORAL("Date and time column", "LATTICE_SQL_COLUMN_TEMPORAL", 0xE49A72, 0xB5530E, Font.PLAIN),
        BOOLEAN("Boolean column", "LATTICE_SQL_COLUMN_BOOLEAN", 0x5FBFC0, 0x00798C, Font.PLAIN),
        BINARY("Binary column", "LATTICE_SQL_COLUMN_BINARY", 0xD58AB8, 0xA0306E, Font.PLAIN),
        OTHER("Other column", "LATTICE_SQL_COLUMN_OTHER", 0xC2A878, 0x7A5C1C, Font.PLAIN);

        private final String label;
        private final TextAttributesKey key;
        private final Color dark, light;
        private final int fontType;

        Role(String label, String keyName, int dark, int light, int fontType) {
            this.label = label;
            this.key = createTextAttributesKey(keyName);
            this.dark = new Color(dark);
            this.light = new Color(light);
            this.fontType = fontType;
        }

        /** The name shown under Settings | Editor | Color Scheme | Lattice SQL. */
        public @NotNull String label() { return label; }
        public @NotNull TextAttributesKey key() { return key; }
        /** The default color for a dark or a light scheme. */
        public @NotNull Color defaultColor(boolean dark) { return dark ? this.dark : this.light; }
        public int defaultFontType() { return fontType; }
    }

    private SqlSemanticColors() {}

    /**
     * What the role looks like in a scheme: its own color and style when the scheme sets a color for it, else the default
     * for the scheme's brightness. A color that is only the plain text color says nothing and counts as unset.
     */
    public static @NotNull TextAttributes attributes(@NotNull EditorColorsScheme scheme, @NotNull Role role) {
        TextAttributes own = scheme.getAttributes(role.key());
        Color color = own == null ? null : own.getForegroundColor();
        if (color == null || color.equals(scheme.getDefaultForeground())) {
            return new TextAttributes(role.defaultColor(isDark(scheme)), null, null, null, role.defaultFontType());
        }
        return new TextAttributes(color, null, null, null, own.getFontType());
    }

    /** The role's color as "#RRGGBB", for HTML. */
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
