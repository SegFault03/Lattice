package com.segfault03.ideadb.sql.view;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.util.Alarm;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.binding.SqlEmbeddedBinding;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaListener;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic;
import com.segfault03.ideadb.sql.validate.SqlScriptValidator;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the findings of {@link SqlScriptValidator} in an editor embedded in the plugin's own UI. The inspections of a file
 * only run for editors the IDE's daemon tracks, which an editor inside a tool window or a table's filter bar may not be,
 * so this paints the same findings itself: with the same checks, the same cached schema and nothing sent to a database.
 */
final class SqlEmbeddedValidation implements Disposable {
    /** Highlights of this kind are replaced as a group on every run. */
    private static final int GROUP = 0x4C41_5453;
    private static final int DELAY_MILLIS = 300;

    private final Project project;
    private final Document document;
    private final VirtualFile file;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
    private volatile Editor editor;

    SqlEmbeddedValidation(@NotNull Project project, @NotNull Document document, @NotNull VirtualFile file, @NotNull Disposable parent) {
        this.project = project;
        this.document = document;
        this.file = file;
        com.intellij.openapi.util.Disposer.register(parent, this);
        document.addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) { schedule(); }
        }, this);
        // A background read that finishes later is what makes table and column checks possible.
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(SqlSchemaListener.TOPIC, connectionId -> schedule());
    }

    /** The editor to paint in, once the field has made one. */
    void attach(@NotNull Editor editor) {
        this.editor = editor;
        schedule();
    }

    void schedule() {
        if (project.isDisposed() || alarm.isDisposed()) return;
        alarm.cancelAllRequests();
        alarm.addRequest(this::run, DELAY_MILLIS);
    }

    private void run() {
        long stamp = document.getModificationStamp();
        List<SqlDiagnostic> found = ReadAction.compute(this::diagnose);
        ApplicationManager.getApplication().invokeLater(() -> show(found, stamp), project.getDisposed());
    }

    private List<SqlDiagnostic> diagnose() {
        if (!SqlEditorSettings.getInstance().isValidationEffective()) return List.of();
        SqlEmbeddedBinding binding = SqlEmbeddedBinding.of(file);
        if (binding == null) return List.of();
        String leadIn = binding.leadIn();
        String text = leadIn + document.getText();
        SqlDialectProfile profile = SqlDialectResolver.resolve(project, file);
        ConnectionConfig connection = SqlDialectResolver.connection(project, file);
        SqlSchemaService service = SqlSchemaService.getInstance();
        SqlSchema schema = null;
        if (connection != null) {
            // While the cache may be out of date only the shape of the statements is checked, as for a file.
            if (!service.isStale(connection.getId())) schema = SqlDialectResolver.schema(connection, file);
            service.ensureLoaded(connection);
        }
        List<SqlDiagnostic> found = new ArrayList<>();
        for (SqlDiagnostic diagnostic : SqlScriptValidator.validate(text, profile, schema, true)) {
            // The lead-in is not the user's text, so what is found in it is not shown.
            if (diagnostic.start() < leadIn.length()) continue;
            found.add(new SqlDiagnostic(diagnostic.kind(), diagnostic.start() - leadIn.length(), diagnostic.end() - leadIn.length(),
                    diagnostic.message(), diagnostic.replacement()));
        }
        return found;
    }

    private void show(List<SqlDiagnostic> found, long stamp) {
        Editor target = editor;
        if (target == null || target.isDisposed() || project.isDisposed() || document.getModificationStamp() != stamp) return;
        List<HighlightInfo> infos = new ArrayList<>();
        int length = document.getTextLength();
        for (SqlDiagnostic diagnostic : found) {
            if (diagnostic.start() >= diagnostic.end() || diagnostic.end() > length) continue;
            var builder = HighlightInfo.newHighlightInfo(diagnostic.kind() == SqlDiagnostic.Kind.SYNTAX ? HighlightInfoType.ERROR : HighlightInfoType.WARNING)
                    .range(diagnostic.start(), diagnostic.end()).descriptionAndTooltip(diagnostic.message());
            if (diagnostic.replacement() != null) builder.registerFix(new Replace(diagnostic, stamp), null, null, null, null);
            HighlightInfo info = builder.create();
            if (info != null) infos.add(info);
        }
        // The document's own markup is where the hover popup and Alt+Enter look for findings, so that is where they go.
        UpdateHighlightersUtil.setHighlightersToEditor(project, document, 0, length, infos, target.getColorsScheme(), GROUP);
    }

    /** Puts the likely intended spelling in place of the flagged name or word, as the inspection of a file does. */
    private final class Replace implements IntentionAction {
        private final SqlDiagnostic diagnostic;
        private final long stamp;

        Replace(SqlDiagnostic diagnostic, long stamp) {
            this.diagnostic = diagnostic;
            this.stamp = stamp;
        }

        @Override public @NotNull String getText() { return "Replace with '" + diagnostic.replacement() + "'"; }
        @Override public @NotNull String getFamilyName() { return "Replace with the suggested name"; }
        @Override public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return document.getModificationStamp() == stamp && diagnostic.end() <= document.getTextLength();
        }
        @Override public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
            if (document.getModificationStamp() == stamp) document.replaceString(diagnostic.start(), diagnostic.end(), diagnostic.replacement());
        }
        @Override public boolean startInWriteAction() { return true; }
    }

    @Override public void dispose() { editor = null; }
}
