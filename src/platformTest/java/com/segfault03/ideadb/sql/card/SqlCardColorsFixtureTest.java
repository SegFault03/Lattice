package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.awt.Color;

/** A scheme that defines no card colours, as some bundled and third-party schemes do, still gets a visible card. */
public class SqlCardColorsFixtureTest extends BasePlatformTestCase {
    private EditorColorsScheme schemeWithoutCardColors(Color background) {
        EditorColorsScheme scheme = (EditorColorsScheme) EditorColorsManager.getInstance().getGlobalScheme().clone();
        scheme.setAttributes(SqlCardColors.CARD, new TextAttributes());
        TextAttributes text = scheme.getAttributes(com.intellij.openapi.editor.HighlighterColors.TEXT).clone();
        text.setBackgroundColor(background);
        scheme.setAttributes(com.intellij.openapi.editor.HighlighterColors.TEXT, text);
        return scheme;
    }

    public void testLightSchemeFallsBackToLightDefaults() {
        EditorColorsScheme scheme = schemeWithoutCardColors(Color.WHITE);
        assertEquals(new Color(0xF1F4FC), SqlCardColors.fill(scheme));
        assertEquals(new Color(0xDCE3F5), SqlCardColors.border(scheme, false));
        assertEquals(new Color(0xA8B8E6), SqlCardColors.border(scheme, true));
    }

    public void testDarkSchemeFallsBackToDarkDefaults() {
        EditorColorsScheme scheme = schemeWithoutCardColors(new Color(0x1E1F22));
        assertEquals(new Color(0x262A33), SqlCardColors.fill(scheme));
        assertEquals(new Color(0x3A4150), SqlCardColors.border(scheme, false));
        assertEquals(new Color(0x5A6B95), SqlCardColors.border(scheme, true));
    }

    public void testSchemeColorsWin() {
        EditorColorsScheme scheme = schemeWithoutCardColors(Color.WHITE);
        TextAttributes own = new TextAttributes();
        own.setBackgroundColor(Color.PINK);
        own.setEffectColor(Color.ORANGE);
        scheme.setAttributes(SqlCardColors.CARD, own);
        assertEquals(Color.PINK, SqlCardColors.fill(scheme));
        assertEquals(Color.ORANGE, SqlCardColors.border(scheme, false));
    }
}
