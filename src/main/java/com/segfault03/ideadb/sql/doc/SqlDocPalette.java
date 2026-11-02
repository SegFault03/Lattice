package com.segfault03.ideadb.sql.doc;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColorUtil;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.sql.lang.LatticeSqlSyntaxHighlighter;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.function.Function;

/**
 * The colors of a documentation card. They come from the editor's color scheme, so a card follows the theme and the
 * user's own Lattice SQL colors: the key and column-kind roles are the same ones the editor colors names with
 * ({@link SqlSemanticColors}), so a primary key looks the same in a card and in a query. The HTML cannot carry a
 * theme-aware color, so they are read once per card. A role whose scheme color is missing, or is just the plain text
 * color (which says nothing), takes a fixed color that is lighter on a dark scheme and deeper on a light one.
 */
record SqlDocPalette(@NotNull String key, @NotNull String foreignKey, @NotNull String numeric, @NotNull String text,
                     @NotNull String temporal, @NotNull String bool, @NotNull String binary, @NotNull String other,
                     @NotNull String accent, @NotNull String muted) {
    static final SqlDocPalette DARK = fixed(true, "#6CA6DD", "#8C9096");
    static final SqlDocPalette LIGHT = fixed(false, "#0B5CAD", "#777C82");

    private static SqlDocPalette fixed(boolean dark, String accent, String muted) {
        Function<Role, String> hex = role -> SqlSemanticColors.hex(role.defaultColor(dark));
        return new SqlDocPalette(hex.apply(Role.PRIMARY_KEY), hex.apply(Role.FOREIGN_KEY), hex.apply(Role.NUMERIC), hex.apply(Role.TEXT),
                hex.apply(Role.TEMPORAL), hex.apply(Role.BOOLEAN), hex.apply(Role.BINARY), hex.apply(Role.OTHER), accent, muted);
    }

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
                SqlSemanticColors.hex(scheme, Role.PRIMARY_KEY),
                SqlSemanticColors.hex(scheme, Role.FOREIGN_KEY),
                SqlSemanticColors.hex(scheme, Role.NUMERIC),
                SqlSemanticColors.hex(scheme, Role.TEXT),
                SqlSemanticColors.hex(scheme, Role.TEMPORAL),
                SqlSemanticColors.hex(scheme, Role.BOOLEAN),
                SqlSemanticColors.hex(scheme, Role.BINARY),
                SqlSemanticColors.hex(scheme, Role.OTHER),
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
        return SqlSemanticColors.isForeignKey(column) ? foreignKey : "";
    }

    /** The color of a column's type, by what kind of values it holds. */
    @NotNull String typeColor(@NotNull ColumnMetadata column) {
        return switch (SqlSemanticColors.typeRole(column)) {
            case NUMERIC -> numeric;
            case TEXT -> text;
            case TEMPORAL -> temporal;
            case BOOLEAN -> bool;
            case BINARY -> binary;
            default -> other;
        };
    }
}
