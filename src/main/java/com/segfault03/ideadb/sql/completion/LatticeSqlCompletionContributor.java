package com.segfault03.ideadb.sql.completion;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.schema.SqlSchema;
import com.segfault03.ideadb.sql.schema.SqlSchemaService;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.ui.Icons;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Completion in Lattice SQL files. The work is in {@link SqlStatementAnalyzer} and {@link SqlCompletionEngine}; this
 * class reads the file, asks the schema service for what is cached, and presents the result. It never touches the
 * database: a missing schema starts a background read and the popup carries a note until it arrives.
 */
public final class LatticeSqlCompletionContributor extends CompletionContributor implements DumbAware {
    public LatticeSqlCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().withLanguage(LatticeSqlLanguage.INSTANCE),
                new CompletionProvider<>() {
                    @Override protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context,
                                                            @NotNull CompletionResultSet result) {
                        complete(parameters, result);
                    }
                });
    }

    private static void complete(CompletionParameters parameters, CompletionResultSet result) {
        if (!SqlEditorSettings.getInstance().isCompletionEffective()) return;
        var file = parameters.getOriginalFile();
        Project project = file.getProject();
        VirtualFile virtual = file.getVirtualFile();
        SqlDialectProfile profile = SqlDialectResolver.resolve(project, virtual);
        CharSequence text = file.getViewProvider().getContents();
        int offset = parameters.getOffset();
        SqlContext context = SqlStatementAnalyzer.analyze(text, offset, profile);
        if (context.position() == SqlContext.Position.NONE) return;
        var keywordCase = SqlEditorSettings.getInstance().keywordCase();
        if (keywordCase != SqlEditorSettings.KeywordCase.AS_TYPED) context = context.withLowercaseKeywords(keywordCase == SqlEditorSettings.KeywordCase.LOWER);

        ConnectionConfig connection = SqlDialectResolver.connection(project, virtual);
        SqlSchema schema = null;
        if (connection != null) {
            SqlSchemaService service = SqlSchemaService.getInstance();
            schema = service.schema(connection);
            service.ensureLoaded(connection);
        }
        boolean closed = context.openQuote() != 0 && offset < text.length() && text.charAt(offset) == context.openQuote();

        CompletionResultSet matching = result.withPrefixMatcher(context.prefix());
        for (SqlSuggestion suggestion : SqlCompletionEngine.suggest(context, profile, schema)) {
            matching.addElement(element(suggestion, closed ? context.openQuote() : 0));
        }
        String note = advertisement(connection, schema);
        if (note != null) matching.addLookupAdvertisement(note);
        matching.stopHere();
    }

    private static @Nullable String advertisement(@Nullable ConnectionConfig connection, @Nullable SqlSchema schema) {
        if (connection == null) {
            return DatabaseSettingsState.getInstance().getConnections().isEmpty()
                    ? "Add a connection in Lattice to complete table and column names"
                    : "Choose a connection for this file to complete table and column names";
        }
        if (schema == null || schema.namespace(null) == null) return "Reading the schema of " + connection.getName() + "...";
        return null;
    }

    private static LookupElement element(SqlSuggestion suggestion, char alreadyClosedBy) {
        String insert = suggestion.insertText();
        if (alreadyClosedBy != 0 && insert.endsWith(String.valueOf(alreadyClosedBy))) insert = insert.substring(0, insert.length() - 1);
        // The inserted text may be quoted or qualified; the plain name is what the user types and matches against.
        LookupElementBuilder builder = LookupElementBuilder.create(suggestion, insert)
                .withLookupString(suggestion.label())
                .withPresentableText(suggestion.label())
                .withIcon(icon(suggestion))
                .withBoldness(suggestion.kind() == SqlSuggestion.Kind.KEYWORD)
                .withCaseSensitivity(false);
        if (suggestion.typeText() != null) builder = builder.withTypeText(suggestion.typeText(), true);
        if (suggestion.tailText() != null) builder = builder.withTailText(tail(suggestion), true);
        String exact = insert;
        builder = builder.withInsertHandler(switch (suggestion.kind()) {
            case FUNCTION -> (context, item) -> { restore(context, exact); insertCall(context, item); };
            case NAMESPACE, ALIAS -> (context, item) -> { restore(context, exact); reopen(context.getProject(), context.getEditor()); };
            default -> (context, item) -> restore(context, exact);
        });
        return PrioritizedLookupElement.withPriority(builder, suggestion.priority());
    }

    private static String tail(SqlSuggestion suggestion) {
        return suggestion.kind() == SqlSuggestion.Kind.FUNCTION ? "()" : " " + suggestion.tailText();
    }

    /** The explorer's own icons for tables, views and columns, so the popup and the tree read alike. */
    static @Nullable Icon icon(SqlSuggestion suggestion) {
        return switch (suggestion.kind()) {
            case TABLE -> Icons.TABLE;
            case VIEW -> Icons.VIEW;
            case COLUMN -> switch (suggestion.key()) {
                case PRIMARY, PRIMARY_FOREIGN -> Icons.KEY;
                case FOREIGN -> Icons.FOREIGN_KEY;
                case NONE -> Icons.COLUMN;
            };
            case FUNCTION -> AllIcons.Nodes.Function;
            case NAMESPACE -> AllIcons.Nodes.DataSchema;
            case ALIAS -> AllIcons.Nodes.Variable;
            case JOIN -> Icons.FOREIGN_KEY;
            case KEYWORD, TYPE -> null;
        };
    }

    /**
     * Case-insensitive matching also makes the platform re-case the inserted text to the typed prefix ("auth" gives
     * "authors"), which would change a name's meaning where names are case sensitive. Put back exactly what was suggested.
     */
    private static void restore(InsertionContext context, String exact) {
        Document document = context.getDocument();
        int start = context.getStartOffset();
        int tail = context.getTailOffset();
        if (document.getText(new TextRange(start, tail)).equals(exact)) return;
        document.replaceString(start, tail, exact);
        context.setTailOffset(start + exact.length());
        context.getEditor().getCaretModel().moveToOffset(start + exact.length());
    }

    /** Adds the call parentheses and leaves the caret between them, unless the call already has them. */
    private static void insertCall(InsertionContext context, LookupElement item) {
        Document document = context.getDocument();
        int tail = context.getTailOffset();
        boolean called = tail < document.getTextLength() && document.getCharsSequence().charAt(tail) == '(';
        if (!called) document.insertString(tail, "()");
        context.getEditor().getCaretModel().moveToOffset(tail + 1);
    }

    private static void reopen(Project project, Editor editor) {
        AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
    }
}
