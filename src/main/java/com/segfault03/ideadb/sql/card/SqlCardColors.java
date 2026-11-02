package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.ColorUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/**
 * The card's colours: the key's background is the fill and its effect colour the border. A scheme that does not set
 * them, as the new UI's own schemes do not inherit them, gets the light or dark defaults that match its background.
 */
public final class SqlCardColors {
    public static final TextAttributesKey CARD = createTextAttributesKey("LATTICE_SQL_CARD");

    private static final Color LIGHT_FILL = new Color(0xF1F4FC), LIGHT_BORDER = new Color(0xDCE3F5), LIGHT_HOVER = new Color(0xA8B8E6);
    private static final Color DARK_FILL = new Color(0x262A33), DARK_BORDER = new Color(0x3A4150), DARK_HOVER = new Color(0x5A6B95);

    private SqlCardColors() {}

    public static @NotNull Color fill(@NotNull EditorColorsScheme scheme) {
        TextAttributes attributes = scheme.getAttributes(CARD);
        Color own = attributes == null ? null : attributes.getBackgroundColor();
        return own != null ? own : isDark(scheme) ? DARK_FILL : LIGHT_FILL;
    }

    public static @NotNull Color border(@NotNull EditorColorsScheme scheme, boolean hovered) {
        if (hovered) return isDark(scheme) ? DARK_HOVER : LIGHT_HOVER;
        TextAttributes attributes = scheme.getAttributes(CARD);
        Color own = attributes == null ? null : attributes.getEffectColor();
        return own != null ? own : isDark(scheme) ? DARK_BORDER : LIGHT_BORDER;
    }

    private static boolean isDark(EditorColorsScheme scheme) { return ColorUtil.isDark(scheme.getDefaultBackground()); }
}
