package com.segfault03.ideadb.sql.doc;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.openapi.util.text.StringUtil;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.ColumnReference;
import com.segfault03.ideadb.service.ExplorerMetadataSnapshot;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The documentation card for a table, column, alias or schema, as HTML. Everything comes from the cached schema, so a
 * card can be built while editing without touching the database. Names that lead to another card are links.
 */
public final class SqlDocRenderer {
    private static final int MAX_COLUMNS = 60;
    private static final int MAX_LISTED = 12;

    private SqlDocRenderer() {}

    /** @param source what the card says it came from, e.g. "demo-mysql, MySQL" */
    public static @Nullable String render(@NotNull SqlTarget target, @NotNull SqlSchema schema, @NotNull String source) {
        String connectionId = schema.connectionId();
        StringBuilder html = new StringBuilder();
        boolean found = switch (target) {
            case SqlTarget.Table table -> table(html, table, schema, connectionId);
            case SqlTarget.Column column -> column(html, column, schema, connectionId);
            case SqlTarget.Alias alias -> alias(html, alias, schema, connectionId);
            case SqlTarget.Schema namespace -> namespace(html, namespace, schema, connectionId);
        };
        if (!found) return null;
        html.append(DocumentationMarkup.CONTENT_START).append(DocumentationMarkup.GRAYED_START).append(esc(source))
                .append(DocumentationMarkup.GRAYED_END).append(DocumentationMarkup.CONTENT_END);
        return html.toString();
    }

    // ---- table -----------------------------------------------------------------------------------------------------

    private static boolean table(StringBuilder html, SqlTarget.Table target, SqlSchema schema, String connection) {
        var table = schema.table(target.namespace(), target.table());
        if (table == null) return false;
        var metadata = table.metadata();
        html.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(target.namespace())).append('.')
                .append(esc(metadata.getName())).append("</b> ").append(DocumentationMarkup.GRAYED_START)
                .append(metadata.isView() ? "view" : "table").append(DocumentationMarkup.GRAYED_END).append(DocumentationMarkup.DEFINITION_END);
        comment(html, metadata.getComment());
        html.append(DocumentationMarkup.SECTIONS_START);
        section(html, "Columns", columns(table, schema, connection));
        List<String> keys = metadata.getPrimaryKeyColumnNames();
        if (!keys.isEmpty()) section(html, "Primary key", esc(String.join(", ", keys)));
        section(html, "Foreign keys", foreignKeys(table, schema, connection));
        section(html, "Referenced by", referencedBy(target, schema, connection));
        html.append(DocumentationMarkup.SECTIONS_END);
        html.append(DocumentationMarkup.CONTENT_START)
                .append(link(SqlDocLinks.open(connection, target.namespace(), metadata.getName()), "Open in Lattice"))
                .append(DocumentationMarkup.CONTENT_END);
        return true;
    }

    private static @Nullable String columns(ExplorerMetadataSnapshot.Table table, SqlSchema schema, String connection) {
        if (!table.columnsLoaded()) return DocumentationMarkup.GRAYED_START + "not read yet" + DocumentationMarkup.GRAYED_END;
        List<String> lines = new ArrayList<>();
        for (ColumnMetadata column : table.columns()) {
            if (lines.size() == MAX_COLUMNS) { lines.add(DocumentationMarkup.GRAYED_START + "... " + (table.columns().size() - MAX_COLUMNS) + " more" + DocumentationMarkup.GRAYED_END); break; }
            StringBuilder line = new StringBuilder("<b>").append(esc(column.getName())).append("</b> ").append(esc(column.getFormattedType()));
            List<String> flags = new ArrayList<>();
            if (column.isPrimaryKey()) flags.add("PK");
            if (column.isForeignKey()) flags.add("FK");
            if (!column.isNullable()) flags.add("not null");
            if (!flags.isEmpty()) line.append(' ').append(DocumentationMarkup.GRAYED_START).append(esc(String.join(", ", flags))).append(DocumentationMarkup.GRAYED_END);
            lines.add(line.toString());
        }
        return lines.isEmpty() ? null : String.join("<br>", lines);
    }

    private static @Nullable String foreignKeys(ExplorerMetadataSnapshot.Table table, SqlSchema schema, String connection) {
        List<String> lines = new ArrayList<>();
        for (ColumnMetadata column : table.columns()) {
            ColumnReference reference = column.getReference();
            if (reference != null) lines.add(esc(column.getName()) + " &rarr; " + referenceLink(reference, schema, connection));
        }
        return lines.isEmpty() ? null : String.join("<br>", lines);
    }

    private static @Nullable String referencedBy(SqlTarget.Table target, SqlSchema schema, String connection) {
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (var namespace : schema.namespaces()) {
            for (var other : namespace.tables()) {
                for (ColumnMetadata column : other.columns()) {
                    ColumnReference reference = column.getReference();
                    if (reference == null || !reference.table().equalsIgnoreCase(target.table())) continue;
                    if (reference.namespace() != null && !reference.namespace().equalsIgnoreCase(target.namespace())) continue;
                    if (++total > MAX_LISTED) continue;
                    var to = new SqlTarget.Column(namespace.name(), other.metadata().getName(), column.getName());
                    lines.add(link(SqlDocLinks.show(connection, to), other.metadata().getName() + "." + column.getName()));
                }
            }
        }
        if (total > MAX_LISTED) lines.add(DocumentationMarkup.GRAYED_START + "... " + (total - MAX_LISTED) + " more" + DocumentationMarkup.GRAYED_END);
        return lines.isEmpty() ? null : String.join("<br>", lines);
    }

    // ---- column ----------------------------------------------------------------------------------------------------

    private static boolean column(StringBuilder html, SqlTarget.Column target, SqlSchema schema, String connection) {
        var table = schema.table(target.namespace(), target.table());
        if (table == null) return false;
        ColumnMetadata column = table.columns().stream().filter(c -> c.getName().equals(target.column())).findFirst().orElse(null);
        if (column == null) return false;
        String tableLink = link(SqlDocLinks.show(connection, new SqlTarget.Table(target.namespace(), target.table())),
                target.namespace() + "." + table.metadata().getName());
        html.append(DocumentationMarkup.DEFINITION_START).append(tableLink).append(".<b>").append(esc(column.getName())).append("</b> ")
                .append(esc(column.getFormattedType())).append(DocumentationMarkup.DEFINITION_END);
        comment(html, column.getComment());
        html.append(DocumentationMarkup.SECTIONS_START);
        section(html, "Nullable", column.isNullable() ? "yes" : "no");
        if (column.getDefaultValue() != null) section(html, "Default", esc(column.getDefaultValue()));
        List<String> keys = new ArrayList<>();
        if (column.isPrimaryKey()) keys.add("primary key");
        if (column.getReference() != null) keys.add("foreign key &rarr; " + referenceLink(column.getReference(), schema, connection));
        else if (column.isForeignKey()) keys.add("foreign key");
        if (!keys.isEmpty()) section(html, "Key", String.join(", ", keys));
        List<String> extra = new ArrayList<>();
        if (column.isAutoIncrement()) extra.add("auto increment");
        if (column.isGenerated()) extra.add("generated");
        if (!extra.isEmpty()) section(html, "Extra", String.join(", ", extra));
        html.append(DocumentationMarkup.SECTIONS_END);
        return true;
    }

    // ---- alias and schema ------------------------------------------------------------------------------------------

    private static boolean alias(StringBuilder html, SqlTarget.Alias target, SqlSchema schema, String connection) {
        var table = schema.table(target.namespace(), target.table());
        if (table == null) return false;
        var to = new SqlTarget.Table(target.namespace(), table.metadata().getName());
        html.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(target.alias())).append("</b> ")
                .append(DocumentationMarkup.GRAYED_START).append("alias for ").append(DocumentationMarkup.GRAYED_END)
                .append(link(SqlDocLinks.show(connection, to), target.namespace() + "." + table.metadata().getName()))
                .append(DocumentationMarkup.DEFINITION_END);
        comment(html, table.metadata().getComment());
        return true;
    }

    private static boolean namespace(StringBuilder html, SqlTarget.Schema target, SqlSchema schema, String connection) {
        var namespace = schema.namespace(target.namespace());
        if (namespace == null) return false;
        html.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(namespace.name())).append("</b> ")
                .append(DocumentationMarkup.GRAYED_START).append("schema").append(DocumentationMarkup.GRAYED_END).append(DocumentationMarkup.DEFINITION_END);
        html.append(DocumentationMarkup.SECTIONS_START);
        if (!namespace.tablesLoaded()) {
            section(html, "Tables", DocumentationMarkup.GRAYED_START + "not read yet" + DocumentationMarkup.GRAYED_END);
        } else {
            List<String> lines = new ArrayList<>();
            for (var table : namespace.tables()) {
                if (lines.size() == MAX_LISTED) { lines.add(DocumentationMarkup.GRAYED_START + "... " + (namespace.tables().size() - MAX_LISTED) + " more" + DocumentationMarkup.GRAYED_END); break; }
                lines.add(link(SqlDocLinks.show(connection, new SqlTarget.Table(namespace.name(), table.metadata().getName())), table.metadata().getName()));
            }
            section(html, "Tables", lines.isEmpty() ? "none" : String.join("<br>", lines));
        }
        html.append(DocumentationMarkup.SECTIONS_END);
        return true;
    }

    // ---- pieces ----------------------------------------------------------------------------------------------------

    private static String referenceLink(ColumnReference reference, SqlSchema schema, String connection) {
        String namespace = reference.namespace() != null ? reference.namespace() : schema.defaultNamespace();
        String label = (reference.namespace() != null ? reference.namespace() + "." : "") + reference.table() + "." + reference.column();
        if (namespace == null) return esc(label);
        var table = schema.table(namespace, reference.table());
        if (table == null) return esc(label);
        var to = new SqlTarget.Column(schema.namespace(namespace).name(), table.metadata().getName(), reference.column());
        return link(SqlDocLinks.show(connection, to), label);
    }

    private static void comment(StringBuilder html, @Nullable String comment) {
        if (comment == null || comment.isBlank()) return;
        html.append(DocumentationMarkup.CONTENT_START).append(esc(comment.strip()).replace("\n", "<br>")).append(DocumentationMarkup.CONTENT_END);
    }

    private static void section(StringBuilder html, String header, @Nullable String value) {
        if (value == null) return;
        html.append(DocumentationMarkup.SECTION_HEADER_START).append(header).append(DocumentationMarkup.SECTION_SEPARATOR)
                .append(value).append(DocumentationMarkup.SECTION_END);
    }

    private static String link(String url, String text) {
        return "<a href=\"" + esc(url) + "\">" + esc(text) + "</a>";
    }

    private static String esc(String text) { return StringUtil.escapeXmlEntities(text); }
}
