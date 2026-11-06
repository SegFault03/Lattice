package com.segfault03.ideadb.sql.view;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.lang.LatticeSqlLexer;
import com.segfault03.ideadb.sql.lang.LatticeSqlSyntaxHighlighter;
import com.segfault03.ideadb.sql.lang.SqlSemanticColoring;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors;
import com.segfault03.ideadb.sql.schema.SqlSchema;
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
 * colors, so a pane follows the theme and the user's settings like a hover card does. Given a connection, the names its
 * cached schema knows (tables, views, aliases, keys and columns) get their role colors on top, from the same resolution
 * and the same keys as an editor. Without an IDE (plain unit tests and previews) fixed colors stand in.
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

    /** Shows a message in the place of SQL, in the interface font and the secondary text color. */
    public static void message(@NotNull JTextPane pane, @NotNull String text) {
        pane.setText(text);
        StyledDocument document = pane.getStyledDocument();
        SimpleAttributeSet prose = new SimpleAttributeSet();
        Font font = javax.swing.UIManager.getFont("Label.font");
        if (font != null) StyleConstants.setFontFamily(prose, font.getFamily());
        StyleConstants.setForeground(prose, JBColor.namedColor("Lattice.secondaryForeground", new JBColor(0x6B7078, 0xA8ADB5)));
        StyleConstants.setBold(prose, false);
        StyleConstants.setItalic(prose, false);
        document.setCharacterAttributes(0, document.getLength(), prose, true);
        pane.setCaretPosition(0);
    }

    /** Colors the text now in the pane by syntax alone. Call again whenever the text is replaced. */
    public static void apply(@NotNull JTextPane pane, @NotNull SqlDialectProfile profile) { apply(pane, profile, null); }

    /**
     * Colors the text now in the pane by syntax and by what the connection's cached schema says its names are, with
     * unqualified names read in {@code namespace} when it is given. Names the schema does not know, or a schema that may
     * be out of date, leave the syntax colors.
     */
    public static void apply(@NotNull JTextPane pane, @NotNull ConnectionConfig connection, @Nullable String namespace) {
        // Tests and previews can run without an application, and then there is no schema to read.
        SqlSchema schema = ApplicationManager.getApplication() == null ? null : SqlSemanticColoring.currentSchema(connection, null);
        apply(pane, SqlDialects.of(connection.getType()), schema == null ? null : schema.withDefaultNamespace(namespace));
    }

    static void apply(JTextPane pane, SqlDialectProfile profile, @Nullable SqlSchema schema) {
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
        if (schema == null) return;
        for (var colored : SqlSemanticColoring.colored(text, profile, schema)) {
            document.setCharacterAttributes(colored.range().getStartOffset(), colored.range().getLength(), roleStyle(colored.role(), scheme), false);
        }
    }

    /** A role's color and style: the scheme's own, else the defaults for its brightness (light and dark by the IDE theme without a scheme). */
    private static AttributeSet roleStyle(SqlSemanticColors.Role role, @Nullable EditorColorsScheme scheme) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        Color color;
        int type;
        if (scheme != null) {
            TextAttributes attributes = SqlSemanticColors.attributes(scheme, role);
            color = attributes.getForegroundColor();
            type = attributes.getFontType();
        } else {
            color = new JBColor(role.defaultColor(false), role.defaultColor(true));
            type = role.defaultFontType();
        }
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, (type & Font.BOLD) != 0);
        StyleConstants.setItalic(style, (type & Font.ITALIC) != 0);
        return style;
    }

    /**
     * The editor scheme, when it is as dark as the IDE theme. A pane sits on the dialog's own background, so a light
     * editor scheme under a dark theme (or the reverse) would paint its colors on a background they were not made for;
     * the stand-in colors for the theme read better there.
     */
    private static @Nullable EditorColorsScheme scheme() {
        if (ApplicationManager.getApplication() == null) return null;
        try {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            Color background = scheme.getDefaultBackground();
            if (background != null && com.intellij.ui.ColorUtil.isDark(background) == JBColor.isBright()) return null;
            return scheme;
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
