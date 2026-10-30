package com.segfault03.ideadb.sql.validate;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.util.ModificationTracker;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.openapi.util.Key;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.state.MetadataCacheState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The findings for a Lattice SQL file, computed once per edit and per change of the cached schema, whichever
 * inspection asks. The schema is only what is cached: validating never connects to a database, and while a change
 * to the connection may have made the cache out of date (and a read has been asked for), only the shape of the
 * statements is checked.
 */
public final class SqlValidation {
    private static final Key<com.intellij.psi.util.CachedValue<List<SqlDiagnostic>>> KEY = Key.create("lattice.sql.validation");

    private SqlValidation() {}

    public static @NotNull List<SqlDiagnostic> diagnostics(@NotNull PsiFile file) {
        if (!SqlEditorSettings.getInstance().isValidationEffective() || notJudgeable(file)) return List.of();
        return CachedValuesManager.getManager(file.getProject()).getCachedValue(file, KEY, () -> compute(file), false);
    }

    private static CachedValueProvider.Result<List<SqlDiagnostic>> compute(PsiFile file) {
        VirtualFile virtual = file.getOriginalFile().getVirtualFile();
        SqlDialectProfile profile = SqlDialectResolver.resolve(file.getProject(), virtual);
        ConnectionConfig connection = SqlDialectResolver.connection(file.getProject(), virtual);
        SqlSchemaService service = SqlSchemaService.getInstance();
        SqlSchema schema = null;
        String connectionId = connection == null ? "" : connection.getId();
        if (connection != null) {
            if (service.isStale(connectionId)) service.ensureLoaded(connection); // refresh in the background; the result re-validates
            else schema = service.schema(connection);
        }
        List<SqlDiagnostic> found = SqlScriptValidator.validate(file.getViewProvider().getContents(), profile, schema);
        ModificationTracker schemaChanges = () -> MetadataCacheState.getInstance().revision() * 2 + (service.isStale(connectionId) ? 1 : 0);
        return CachedValueProvider.Result.create(found, file, schemaChanges);
    }

    /** SQL assembled from text only known at run time, or with escapes still in it, is not the SQL that will run. */
    private static boolean notJudgeable(PsiFile file) {
        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(file.getProject());
        if (!manager.isInjectedFragment(file)) return false;
        PsiLanguageInjectionHost host = manager.getInjectionHost(file);
        if (host == null || file.getText().indexOf('\\') >= 0) return true;
        boolean[] dynamic = {false};
        manager.enumerate(host, (injected, places) -> {
            if (injected != file) return;
            for (PsiLanguageInjectionHost.Shred shred : places) {
                if (!shred.getPrefix().isEmpty() || !shred.getSuffix().isEmpty()) dynamic[0] = true;
            }
        });
        return dynamic[0];
    }
}
