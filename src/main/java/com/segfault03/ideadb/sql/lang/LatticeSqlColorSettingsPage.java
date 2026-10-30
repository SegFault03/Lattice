package com.segfault03.ideadb.sql.lang;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;

public final class LatticeSqlColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = {
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
    };

    private static final String DEMO = """
            -- Orders placed this month
            SELECT o.id, `c`.`name`, COUNT(*) AS items, SUM(o.total) AS revenue, CAST(o.total AS DECIMAL(10, 2)) AS amount
              FROM orders o
              JOIN customers c ON c.id = o.customer_id
             WHERE o.created_at >= '2025-01-01' AND o.status <> "void" AND o.total > 99.50
               AND o.id = ? AND o.region = :region
             GROUP BY o.id, c.name
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
    @Override public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() { return null; }
}
