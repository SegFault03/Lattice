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

    /** The card for one target. Where it came from is the popup's location bar, so the card does not repeat it. */
    public static @Nullable String render(@NotNull SqlTarget target, @NotNull SqlSchema schema) {
        String connectionId = schema.connectionId();
        StringBuilder html = new StringBuilder();
        boolean found = switch (target) {
            case SqlTarget.Table table -> table(html, table, schema, connectionId);
            case SqlTarget.Column column -> column(html, column, schema, connectionId);
            case SqlTarget.Alias alias -> alias(html, alias, schema, connectionId);
            case SqlTarget.Schema namespace -> namespace(html, namespace, schema, connectionId);
        };
        return found ? html.toString() : null;
    }

    // ---- table -----------------------------------------------------------------------------------------------------

    private static boolean table(StringBuilder html, SqlTarget.Table target, SqlSchema schema, String connection) {
        var table = schema.table(target.namespace(), target.table());
        if (table == null) return false;
        var metadata = table.metadata();
        html.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(esc(target.namespace())).append('.')
                .append(esc(metadata.getName())).append("</b> ").append(DocumentationMarkup.GRAYED_START)
                .append(metadata.isView() ? "view" : "table").append(DocumentationMarkup.GRAYED_END).append(DocumentationMarkup.DEFINITION_END);
        tableBody(html, table, target.namespace(), target.table(), schema, connection);
        return true;
    }

    private static final String CELL = "<td valign=\"top\" nowrap=\"nowrap\" style=\"padding: 0 8px 0 0\">";

    /** Comment, columns, keys and the link to the editor: the card of a table, and of an alias for it. */
    private static void tableBody(StringBuilder html, ExplorerMetadataSnapshot.Table table, String namespace, String tableName,
                                  SqlSchema schema, String connection) {
        var metadata = table.metadata();
        comment(html, metadata.getComment());
        html.append(DocumentationMarkup.SECTIONS_START);
        section(html, "Columns", columns(table));
        section(html, "Foreign keys", foreignKeys(table, schema, connection));
        section(html, "Referenced by", referencedBy(namespace, tableName, schema, connection));
        // The card ends with its sections, like the column card: a trailing content block leaves a gap above the location bar.
        section(html, "Editor", link(SqlDocLinks.open(connection, namespace, metadata.getName()), "Open in Lattice"));
        html.append(DocumentationMarkup.SECTIONS_END);
    }

    /** Names, types and flags as three aligned columns; nothing inside a cell wraps, and the flags are grey. */
    private static @Nullable String columns(ExplorerMetadataSnapshot.Table table) {
        if (!table.columnsLoaded()) return DocumentationMarkup.GRAYED_START + "not read yet" + DocumentationMarkup.GRAYED_END;
        if (table.columns().isEmpty()) return null;
        StringBuilder rows = new StringBuilder("<table cellspacing=\"0\" cellpadding=\"0\">");
        int shown = 0;
        for (ColumnMetadata column : table.columns()) {
            if (shown++ == MAX_COLUMNS) {
                rows.append("<tr><td colspan=\"3\">").append(DocumentationMarkup.GRAYED_START).append("... ").append(table.columns().size() - MAX_COLUMNS)
                        .append(" more").append(DocumentationMarkup.GRAYED_END).append("</td></tr>");
                break;
            }
            List<String> flags = new ArrayList<>();
            if (column.isPrimaryKey()) flags.add("PK");
            if (column.isForeignKey()) flags.add("FK");
            if (!column.isNullable()) flags.add("not null");
            rows.append("<tr>").append(CELL).append("<b>").append(esc(column.getName())).append("</b></td>")
                    .append(CELL).append(unbroken(column.getFormattedType())).append("</td>")
                    .append("<td valign=\"top\" nowrap=\"nowrap\">");
            if (!flags.isEmpty()) rows.append(DocumentationMarkup.GRAYED_START).append(unbroken(String.join(", ", flags))).append(DocumentationMarkup.GRAYED_END);
            rows.append("</td></tr>");
        }
        return rows.append("</table>").toString();
    }

    /** Escaped text whose spaces do not break the line. */
    private static String unbroken(String text) { return esc(text).replace(" ", "&nbsp;"); }

    private static @Nullable String foreignKeys(ExplorerMetadataSnapshot.Table table, SqlSchema schema, String connection) {
        List<String> lines = new ArrayList<>();
        for (ColumnMetadata column : table.columns()) {
            ColumnReference reference = column.getReference();
            if (reference != null) lines.add(esc(column.getName()) + " &rarr; " + referenceLink(reference, schema, connection));
        }
        return lines.isEmpty() ? null : String.join("<br>", lines);
    }

    private static @Nullable String referencedBy(String namespaceName, String tableName, SqlSchema schema, String connection) {
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (var namespace : schema.namespaces()) {
            for (var other : namespace.tables()) {
                for (ColumnMetadata column : other.columns()) {
                    ColumnReference reference = column.getReference();
                    if (reference == null || !reference.table().equalsIgnoreCase(tableName)) continue;
                    if (reference.namespace() != null && !reference.namespace().equalsIgnoreCase(namespaceName)) continue;
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
        section(html, "Nullability", column.isNullable() ? "nullable" : "not null");
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
        tableBody(html, table, target.namespace(), table.metadata().getName(), schema, connection);
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
