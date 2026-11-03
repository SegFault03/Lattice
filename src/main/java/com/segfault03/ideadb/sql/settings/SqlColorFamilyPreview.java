package com.segfault03.ideadb.sql.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.sql.lang.LatticeSqlSyntaxHighlighter;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors;
import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;
import java.util.List;

/**
 * A few lines of SQL colored the way a family colors them, on the editor's own background and in its colors, for the
 * settings screen to show what each choice looks like. It uses the color scheme the user has now (so it follows the
 * theme and any colors they set for the family) and fixed light colors without an IDE.
 */
public final class SqlColorFamilyPreview {
    private SqlColorFamilyPreview() {}

    /** A piece of the sample: a role's color, the keyword color, or none. */
    private record Part(@NotNull String text, @Nullable Role role, boolean keyword) {}

    private static Part plain(String text) { return new Part(text, null, false); }
    private static Part keyword(String text) { return new Part(text, null, true); }
    private static Part role(Role role, String text) { return new Part(text, role, false); }

    static final List<Part> SAMPLE = List.of(
            keyword("SELECT "), role(Role.ALIAS, "o"), plain("."), role(Role.PRIMARY_KEY, "id"), plain(", "), role(Role.ALIAS, "c"), plain("."),
            role(Role.TEXT, "name"), plain(", "), role(Role.ALIAS, "o"), plain("."), role(Role.FOREIGN_KEY, "customer_id"),
            keyword("\n  FROM "), role(Role.TABLE, "orders"), plain(" "), role(Role.ALIAS, "o"),
            keyword("\n  JOIN "), role(Role.TABLE, "customers"), plain(" "), role(Role.ALIAS, "c"), keyword(" ON "), role(Role.ALIAS, "c"), plain("."),
            role(Role.PRIMARY_KEY, "id"), plain(" = "), role(Role.ALIAS, "o"), plain("."), role(Role.FOREIGN_KEY, "customer_id"),
            keyword("\n  JOIN "), role(Role.VIEW, "open_invoices"), plain(" "), role(Role.ALIAS, "i"), keyword(" ON "), role(Role.ALIAS, "i"), plain("."),
            role(Role.FOREIGN_KEY, "order_id"), plain(" = "), role(Role.ALIAS, "o"), plain("."), role(Role.PRIMARY_KEY, "id"), plain(";"));

    /** The text of the sample, without color. */
    public static @NotNull String sampleText() {
        StringBuilder text = new StringBuilder();
        for (Part part : SAMPLE) text.append(part.text);
        return text.toString();
    }

    public static @NotNull JTextPane create(@NotNull ColorFamily family) {
        EditorColorsScheme scheme = scheme();
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setFocusable(false);
        pane.setOpaque(true);
        pane.setBorder(JBUI.Borders.empty(6, 10));
        pane.setBackground(scheme == null ? Color.WHITE : scheme.getDefaultBackground());
        pane.setForeground(scheme == null ? Color.BLACK : scheme.getDefaultForeground());
        pane.setFont(scheme == null ? new Font(Font.MONOSPACED, Font.PLAIN, 12) : scheme.getFont(EditorFontType.PLAIN));
        pane.getAccessibleContext().setAccessibleName("Preview of " + family + " schema name colors");
        StyledDocument document = pane.getStyledDocument();
        try {
            for (Part part : SAMPLE) document.insertString(document.getLength(), part.text, style(part, pane.getForeground(), scheme, family));
        } catch (BadLocationException unexpected) {
            throw new IllegalStateException(unexpected);
        }
        return pane;
    }

    private static SimpleAttributeSet style(Part part, Color text, @Nullable EditorColorsScheme scheme, ColorFamily family) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        Color color = text;
        int fontType = Font.PLAIN;
        if (part.role != null) {
            TextAttributes attributes = scheme != null ? SqlSemanticColors.attributes(scheme, part.role, family)
                    : new TextAttributes(part.role.defaultColor(false, family), null, null, null, part.role.defaultFontType(family));
            color = attributes.getForegroundColor();
            fontType = attributes.getFontType();
        } else if (part.keyword) {
            TextAttributes keyword = scheme == null ? null : scheme.getAttributes(LatticeSqlSyntaxHighlighter.KEYWORD);
            color = keyword != null && keyword.getForegroundColor() != null ? keyword.getForegroundColor() : new Color(0x0033B3);
            fontType = keyword == null ? Font.PLAIN : keyword.getFontType();
        }
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, (fontType & Font.BOLD) != 0);
        StyleConstants.setItalic(style, (fontType & Font.ITALIC) != 0);
        return style;
    }

    private static @Nullable EditorColorsScheme scheme() {
        if (ApplicationManager.getApplication() == null) return null;
        try {
            return EditorColorsManager.getInstance().getGlobalScheme();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
