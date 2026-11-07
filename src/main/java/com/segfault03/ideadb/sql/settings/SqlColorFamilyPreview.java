package com.segfault03.ideadb.sql.settings;

import com.intellij.util.ui.JBUI;
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
 * A few lines of SQL colored the way a family colors them, on a light or a dark editor background, for the settings screen to
 * show what each choice looks like in both kinds of theme at once. The backgrounds and the syntax colors are those of
 * IntelliJ's own light and dark schemes, the name colors are the family's defaults for that brightness; a color the user has
 * set in their color scheme is not shown.
 */
public final class SqlColorFamilyPreview {
    private SqlColorFamilyPreview() {}

    /** The editor colors of a light or a dark scheme, enough to show a few lines of SQL. */
    private record Palette(Color background, Color text, Color keyword) {}

    private static final Palette LIGHT = new Palette(new Color(0xFFFFFF), new Color(0x080808), new Color(0x0033B3));
    private static final Palette DARK = new Palette(new Color(0x2B2D30), new Color(0xBCBEC4), new Color(0xCF8E6D));

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

    /** The background of the sample on a light or a dark editor. */
    public static @NotNull Color background(boolean dark) { return (dark ? DARK : LIGHT).background; }

    /** The color a role has in the sample for a family on a light or a dark editor. */
    public static @NotNull Color colorOf(@NotNull Role role, @NotNull ColorFamily family, boolean dark) {
        Palette palette = dark ? DARK : LIGHT;
        return family == ColorFamily.NEUTRAL && role.byFamily() ? SqlSemanticColors.neutralColor(role, palette.text, palette.background) : role.defaultColor(dark, family);
    }

    public static @NotNull JTextPane create(@NotNull ColorFamily family, boolean dark) {
        Palette palette = dark ? DARK : LIGHT;
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setFocusable(false);
        pane.setOpaque(true);
        pane.setBorder(JBUI.Borders.empty(6, 10));
        pane.setBackground(palette.background);
        pane.setForeground(palette.text);
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)));
        pane.getAccessibleContext().setAccessibleName("Preview of " + family + " schema name colors on a " + (dark ? "dark" : "light") + " editor");
        StyledDocument document = pane.getStyledDocument();
        try {
            for (Part part : SAMPLE) document.insertString(document.getLength(), part.text, style(part, palette, family, dark));
        } catch (BadLocationException unexpected) {
            throw new IllegalStateException(unexpected);
        }
        return pane;
    }

    private static SimpleAttributeSet style(Part part, Palette palette, ColorFamily family, boolean dark) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        Color color = palette.text;
        int fontType = Font.PLAIN;
        if (part.role != null) {
            color = colorOf(part.role, family, dark);
            fontType = part.role.defaultFontType(family);
        } else if (part.keyword) {
            color = palette.keyword;
        }
        StyleConstants.setForeground(style, color);
        StyleConstants.setBold(style, (fontType & Font.BOLD) != 0);
        StyleConstants.setItalic(style, (fontType & Font.ITALIC) != 0);
        return style;
    }
}
