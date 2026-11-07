package com.segfault03.ideadb.sql.lang;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.segfault03.ideadb.sql.card.SqlCardColors;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LatticeSqlColorSettingsPage implements ColorSettingsPage {
    private static final List<AttributesDescriptor> BASE = List.of(
            new AttributesDescriptor("Keyword", LatticeSqlSyntaxHighlighter.KEYWORD),
            new AttributesDescriptor("Function", LatticeSqlSyntaxHighlighter.FUNCTION),
            new AttributesDescriptor("Data type", LatticeSqlSyntaxHighlighter.TYPE),
            new AttributesDescriptor("String", LatticeSqlSyntaxHighlighter.STRING),
            new AttributesDescriptor("Number", LatticeSqlSyntaxHighlighter.NUMBER),
            new AttributesDescriptor("Quoted identifier", LatticeSqlSyntaxHighlighter.QUOTED_IDENTIFIER),
            new AttributesDescriptor("Parameter", LatticeSqlSyntaxHighlighter.PARAMETER),
            new AttributesDescriptor("Line comment", LatticeSqlSyntaxHighlighter.LINE_COMMENT),
            new AttributesDescriptor("Block comment", LatticeSqlSyntaxHighlighter.BLOCK_COMMENT),
            new AttributesDescriptor("Operator", LatticeSqlSyntaxHighlighter.OPERATOR),
            new AttributesDescriptor("SQL card", SqlCardColors.CARD));

    /** The syntax colors, then the colors of names the schema gives a meaning to, which show only for a file with a connection. */
    private static final AttributesDescriptor[] DESCRIPTORS = descriptors();

    private static AttributesDescriptor[] descriptors() {
        List<AttributesDescriptor> all = new ArrayList<>(BASE);
        for (SqlSemanticColors.Role role : SqlSemanticColors.Role.values()) {
            if (!role.byFamily()) {
                all.add(new AttributesDescriptor("Schema names//Columns//" + role.label(), role.key()));
                continue;
            }
            for (SqlEditorSettings.ColorFamily family : SqlEditorSettings.ColorFamily.values()) {
                all.add(new AttributesDescriptor("Schema names//" + family + " names//" + role.label(), role.key(family)));
            }
        }
        return all.toArray(new AttributesDescriptor[0]);
    }

    /** Tags in the demo text, one per role, so the preview shows what a resolved name looks like in the chosen family. */
    private static Map<String, TextAttributesKey> tags() {
        Map<String, TextAttributesKey> tags = new LinkedHashMap<>();
        for (SqlSemanticColors.Role role : SqlSemanticColors.Role.values()) tags.put(role.name().toLowerCase(Locale.ROOT), role.key());
        return tags;
    }

    private static final String DEMO = """
            -- Orders placed this month
            SELECT <alias>o</alias>.<primary_key>id</primary_key>, <alias>c</alias>.<text>`name`</text>, COUNT(*) AS items, SUM(<alias>o</alias>.<numeric>total</numeric>) AS revenue,
                   CAST(<alias>o</alias>.<numeric>total</numeric> AS DECIMAL(10, 2)) AS amount, <alias>o</alias>.<boolean>paid</boolean>, <alias>o</alias>.<binary>receipt</binary>, <alias>c</alias>.<other>preferences</other>
              FROM <table>orders</table> <alias>o</alias>
              JOIN <table>customers</table> <alias>c</alias> ON <alias>c</alias>.<primary_key>id</primary_key> = <alias>o</alias>.<foreign_key>customer_id</foreign_key>
              JOIN <view>open_invoices</view> i ON i.<foreign_key>order_id</foreign_key> = <alias>o</alias>.<primary_key>id</primary_key>
             WHERE <alias>o</alias>.<temporal>created_at</temporal> >= '2025-01-01' AND <alias>o</alias>.<text>status</text> <> "void" AND <alias>o</alias>.<numeric>total</numeric> > 99.50
               AND <alias>o</alias>.<primary_key>id</primary_key> = ? AND <alias>o</alias>.<text>region</text> = :region
             GROUP BY <alias>o</alias>.<primary_key>id</primary_key>, <alias>c</alias>.<text>name</text>
             ORDER BY revenue DESC /* biggest first */
             LIMIT 50;
            CREATE TABLE audit (id BIGINT PRIMARY KEY, note VARCHAR(80));
            """;

    @Override public @NotNull String getDisplayName() { return "Lattice SQL"; }
    @Override public @Nullable Icon getIcon() { return LatticeSqlFileType.INSTANCE.getIcon(); }
    @Override public AttributesDescriptor @NotNull [] getAttributeDescriptors() { return DESCRIPTORS; }
    @Override public ColorDescriptor @NotNull [] getColorDescriptors() { return ColorDescriptor.EMPTY_ARRAY; }
    @Override public @NotNull SyntaxHighlighter getHighlighter() { return new LatticeSqlSyntaxHighlighter(SqlDialects.MYSQL, true); }
    @Override public @NotNull String getDemoText() { return DEMO; }
    @Override public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() { return tags(); }
}
