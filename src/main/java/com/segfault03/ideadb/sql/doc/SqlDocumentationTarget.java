package com.segfault03.ideadb.sql.doc;

import com.intellij.icons.AllIcons;
import com.intellij.model.Pointer;
import com.intellij.openapi.project.Project;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** A table, column, alias or schema of one connection, shown by the IDE's documentation popup. Reads the cache only. */
public final class SqlDocumentationTarget implements DocumentationTarget {
    private final Project project;
    private final String connectionId;
    private final SqlTarget target;

    public SqlDocumentationTarget(@NotNull Project project, @NotNull String connectionId, @NotNull SqlTarget target) {
        this.project = project;
        this.connectionId = connectionId;
        this.target = target;
    }

    public @NotNull Project project() { return project; }
    public @NotNull String connectionId() { return connectionId; }
    public @NotNull SqlTarget target() { return target; }

    /** The card as HTML, or null when the connection or the named object is no longer known. */
    public @Nullable String html() {
        ConnectionConfig config = DatabaseSettingsState.getInstance().getConnection(connectionId);
        if (config == null) return null;
        SqlSchema schema = SqlSchemaService.getInstance().schema(config);
        if (schema == null) return null;
        return SqlDocRenderer.render(target, schema, config.getName() + ", " + config.getType().name());
    }

    @Override public @NotNull Pointer<? extends DocumentationTarget> createPointer() { return Pointer.hardPointer(this); }

    @Override public @NotNull TargetPresentation computePresentation() {
        ConnectionConfig config = DatabaseSettingsState.getInstance().getConnection(connectionId);
        var builder = TargetPresentation.builder(label()).icon(icon());
        if (config != null) builder = builder.locationText(config.getName());
        return builder.presentation();
    }

    @Override public @Nullable DocumentationResult computeDocumentation() {
        String html = html();
        return html == null ? null : DocumentationResult.documentation(html);
    }

    private String label() {
        return switch (target) {
            case SqlTarget.Schema schema -> schema.namespace();
            case SqlTarget.Table table -> table.table();
            case SqlTarget.Column column -> column.table() + "." + column.column();
            case SqlTarget.Alias alias -> alias.alias();
        };
    }

    private Icon icon() {
        return switch (target) {
            case SqlTarget.Schema ignored -> AllIcons.Nodes.Package;
            case SqlTarget.Column ignored -> AllIcons.Nodes.Field;
            case SqlTarget.Table ignored -> AllIcons.Nodes.DataTables;
            case SqlTarget.Alias ignored -> AllIcons.Nodes.Variable;
        };
    }
}
