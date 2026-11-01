package com.segfault03.ideadb.sql.view;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLexer;
import com.segfault03.ideadb.sql.lang.LatticeSqlSyntaxHighlighter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;

/**
 * Colors the SQL shown in a read-only Swing text pane the way the editor colors it: the dialect's own lexer decides what
 * is a keyword, function, type, string, number or comment, and the colors are the editor color scheme's Lattice SQL
 * colors, so a pane follows the theme and the user's settings like a hover card does. Without an IDE (plain unit tests
 * and previews) fixed colors stand in.
 */
public final class SqlSwingHighlighter {
    private SqlSwingHighlighter() {}

    /** Shows the text now in the pane without color, for what is not SQL (an error in the place of a statement). */
    public static void clear(@NotNull JTextPane pane) {
        StyledDocument document = pane.getStyledDocument();
        SimpleAttributeSet plain = new SimpleAttributeSet();
        StyleConstants.setForeground(plain, pane.getForeground() != null ? pane.getForeground() : JBColor.foreground());
        StyleConstants.setBold(plain, false);
        StyleConstants.setItalic(plain, false);
        document.setCharacterAttributes(0, document.getLength(), plain, true);
    }

    /** Colors the text now in the pane. Call again whenever the text is replaced. */
    public static void apply(@NotNull JTextPane pane, @NotNull SqlDialectProfile profile) {
        StyledDocument document = pane.getStyledDocument();
        String text;
        try {
            text = document.getText(0, document.getLength());
        } catch (BadLocationException unexpected) {
            return;
        }
        SimpleAttributeSet plain = new SimpleAttributeSet();
        StyleConstants.setForeground(plain, pane.getForeground() != null ? pane.getForeground() : JBColor.foreground());
        StyleConstants.setBold(plain, false);
        StyleConstants.setItalic(plain, false);
        document.setCharacterAttributes(0, document.getLength(), plain, true);

        EditorColorsScheme scheme = scheme();
        var highlighter = new LatticeSqlSyntaxHighlighter(profile, true);
        var lexer = new LatticeSqlLexer(profile);
        lexer.start(text);
        for (; lexer.getTokenType() != null; lexer.advance()) {
            TextAttributesKey[] keys = highlighter.getTokenHighlights(lexer.getTokenType());
            if (keys.length == 0) continue;
            AttributeSet style = style(keys[0], scheme);
            if (style != null) document.setCharacterAttributes(lexer.getTokenStart(), lexer.getTokenEnd() - lexer.getTokenStart(), style, false);
        }
    }

    private static @Nullable EditorColorsScheme scheme() {
        if (ApplicationManager.getApplication() == null) return null;
        try {
            return EditorColorsManager.getInstance().getGlobalScheme();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static @Nullable AttributeSet style(TextAttributesKey key, @Nullable EditorColorsScheme scheme) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        TextAttributes attributes = scheme == null ? null : scheme.getAttributes(key);
        Color color = attributes == null ? null : attributes.getForegroundColor();
        if (color == null) color = fallback(key);
        if (color == null) return null;
        StyleConstants.setForeground(style, color);
        int type = attributes == null ? Font.PLAIN : attributes.getFontType();
        StyleConstants.setBold(style, (type & Font.BOLD) != 0);
        StyleConstants.setItalic(style, (type & Font.ITALIC) != 0);
        return style;
    }

    /** Stand-in colors where there is no color scheme, with one hue per kind of token. */
    private static @Nullable Color fallback(TextAttributesKey key) {
        if (key == LatticeSqlSyntaxHighlighter.KEYWORD || key == LatticeSqlSyntaxHighlighter.TYPE) return new JBColor(0x0033B3, 0xCF8E6D);
        if (key == LatticeSqlSyntaxHighlighter.FUNCTION) return new JBColor(0x00627A, 0x56A8F5);
        if (key == LatticeSqlSyntaxHighlighter.STRING) return new JBColor(0x067D17, 0x6AAB73);
        if (key == LatticeSqlSyntaxHighlighter.NUMBER) return new JBColor(0x1750EB, 0x2AACB8);
        if (key == LatticeSqlSyntaxHighlighter.LINE_COMMENT || key == LatticeSqlSyntaxHighlighter.BLOCK_COMMENT) return new JBColor(0x8C8C8C, 0x7A7E85);
        if (key == LatticeSqlSyntaxHighlighter.PARAMETER) return new JBColor(0x871094, 0xC77DBB);
        return null;
    }
}
