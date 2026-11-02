package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

/** The card's colours: the key's background is the fill and its effect colour the border. */
public final class SqlCardColors {
    public static final TextAttributesKey CARD = createTextAttributesKey("LATTICE_SQL_CARD");

    private static final JBColor HOVER_BORDER = new JBColor(new Color(0xA8B8E6), new Color(0x5A6B95));

    private SqlCardColors() {}

    public static @NotNull Color hoverBorder() { return HOVER_BORDER; }
}
