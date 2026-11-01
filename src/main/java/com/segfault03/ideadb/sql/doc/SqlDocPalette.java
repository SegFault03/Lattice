package com.segfault03.ideadb.sql.doc;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColorUtil;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.sql.lang.LatticeSqlSyntaxHighlighter;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.sql.Types;

/**
 * The colors of a documentation card. They come from the editor's color scheme, so a card follows the theme and the
 * user's own Lattice SQL colors: numbers are the SQL number color, text the string color, and so on. The HTML cannot
 * carry a theme-aware color, so they are read once per card. A role whose scheme color is missing, or is just the plain
 * text color (which says nothing), takes a fixed color that is lighter on a dark scheme and deeper on a light one.
 */
record SqlDocPalette(@NotNull String key, @NotNull String foreignKey, @NotNull String numeric, @NotNull String text,
                     @NotNull String temporal, @NotNull String bool, @NotNull String binary, @NotNull String other,
                     @NotNull String accent, @NotNull String muted) {
    static final SqlDocPalette DARK = new SqlDocPalette("#E5B567", "#B794F4", "#6CA6DD", "#7EC699", "#E49A72", "#5FBFC0", "#D58AB8", "#C2A878", "#6CA6DD", "#8C9096");
    static final SqlDocPalette LIGHT = new SqlDocPalette("#996600", "#6F42C1", "#0B5CAD", "#2E7D32", "#B5530E", "#00798C", "#A0306E", "#7A5C1C", "#0B5CAD", "#777C82");

    /** The palette for the editor's current color scheme; the light fixed one when no IDE is running (plain unit tests). */
    static @NotNull SqlDocPalette current() {
        if (ApplicationManager.getApplication() == null) return LIGHT;
        try {
            return of(EditorColorsManager.getInstance().getGlobalScheme());
        } catch (RuntimeException unavailable) {
            return LIGHT;
        }
    }

    static @NotNull SqlDocPalette of(@NotNull EditorColorsScheme scheme) {
        SqlDocPalette base = ColorUtil.isDark(scheme.getDefaultBackground()) ? DARK : LIGHT;
        Color plain = scheme.getDefaultForeground();
        return new SqlDocPalette(
                pick(scheme, plain, LatticeSqlSyntaxHighlighter.FUNCTION, base.key),
                pick(scheme, plain, DefaultLanguageHighlighterColors.INSTANCE_FIELD, base.foreignKey),
                pick(scheme, plain, LatticeSqlSyntaxHighlighter.NUMBER, base.numeric),
                pick(scheme, plain, LatticeSqlSyntaxHighlighter.STRING, base.text),
                pick(scheme, plain, LatticeSqlSyntaxHighlighter.KEYWORD, base.temporal),
                pick(scheme, plain, DefaultLanguageHighlighterColors.METADATA, base.bool),
                pick(scheme, plain, LatticeSqlSyntaxHighlighter.PARAMETER, base.binary),
                pick(scheme, plain, DefaultLanguageHighlighterColors.CONSTANT, base.other),
                pick(scheme, plain, LatticeSqlSyntaxHighlighter.TYPE, base.accent),
                pick(scheme, plain, LatticeSqlSyntaxHighlighter.LINE_COMMENT, base.muted));
    }

    /** The scheme's color for a key as "#RRGGBB", or the fallback when it has none of its own. */
    private static String pick(EditorColorsScheme scheme, Color plain, TextAttributesKey key, String fallback) {
        TextAttributes attributes = scheme.getAttributes(key);
        Color color = attributes == null ? null : attributes.getForegroundColor();
        if (color == null || color.equals(plain)) return fallback;
        return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** The text in a color. */
    static @NotNull String paint(@NotNull String color, @NotNull String html) {
        return "<span style=\"color:" + color + "\">" + html + "</span>";
    }

    /** The color of a column's name: the key colors for primary and foreign keys, none for the rest. */
    @NotNull String nameColor(@NotNull ColumnMetadata column) {
        if (column.isPrimaryKey()) return key;
        return column.isForeignKey() || column.getReference() != null ? foreignKey : "";
    }

    /** The color of a column's type, by what kind of values it holds. */
    @NotNull String typeColor(@NotNull ColumnMetadata column) {
        String name = column.getTypeName() == null ? "" : column.getTypeName().toUpperCase(java.util.Locale.ROOT);
        if (name.contains("JSON") || name.contains("XML") || name.contains("UUID") || name.contains("ENUM") || name.equals("SET")) return other;
        return switch (column.getDataType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> numeric;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.SQLXML -> text;
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> temporal;
            case Types.BIT, Types.BOOLEAN -> bool;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> binary;
            default -> other;
        };
    }
}
