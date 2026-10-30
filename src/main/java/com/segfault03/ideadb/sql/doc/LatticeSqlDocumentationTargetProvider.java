package com.segfault03.ideadb.sql.doc;

import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What the pointer is over in a Lattice SQL file, from the file's connection. Nothing is shown when there is no
 * connection, no cached schema yet (a read starts) or the name does not resolve.
 */
public final class LatticeSqlDocumentationTargetProvider implements DocumentationTargetProvider {
    @Override
    public @NotNull List<? extends DocumentationTarget> documentationTargets(@NotNull PsiFile file, int offset) {
        if (!file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE) || !SqlEditorSettings.getInstance().isHoverEffective()) return List.of();
        var project = file.getProject();
        ConnectionConfig connection = SqlDialectResolver.connection(project, file.getOriginalFile().getVirtualFile());
        if (connection == null) return List.of();
        SqlSchemaService service = SqlSchemaService.getInstance();
        SqlSchema schema = service.schema(connection);
        service.ensureLoaded(connection);
        if (schema == null) return List.of();
        SqlDialectProfile profile = SqlDialectResolver.resolve(project, file.getOriginalFile().getVirtualFile());
        SqlTarget target = SqlReferenceResolver.resolve(file.getViewProvider().getContents(), offset, profile, schema);
        return target == null ? List.of() : List.of(new SqlDocumentationTarget(project, connection.getId(), target));
    }
}
