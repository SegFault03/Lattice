package com.segfault03.ideadb.sql.view;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.ui.EditorTextField;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.binding.SqlEmbeddedBinding;
import com.segfault03.ideadb.sql.validate.LatticeSqlSyntaxInspection;

import java.util.Arrays;
import java.util.List;

/** The SQL editors embedded in the plugin's own UI: the console, and the WHERE and ORDER BY fields of a table. */
public class SqlEmbeddedInputFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))"};
    }

    private List<String> lookups(String leadIn, String fragment) {
        myFixture.configureByText("fragment.sql", fragment);
        new SqlEmbeddedBinding(h2.getId(), null, leadIn).attachTo(myFixture.getFile().getVirtualFile());
        var items = myFixture.completeBasic();
        return items == null ? List.of() : Arrays.stream(items).map(item -> item.getLookupString()).toList();
    }

    public void testAWhereFieldCompletesTheColumnsOfItsTable() {
        loadSchema();
        var items = lookups("select * from BOOKS where ", "<caret>");
        assertTrue(items.toString(), items.containsAll(List.of("ID", "TITLE", "AUTHOR_ID")));
        assertFalse("not the columns of another table", items.contains("NAME"));
    }

    public void testAFragmentIsCompletedAsTheEndOfItsStatement() {
        loadSchema();
        var items = lookups("select * from BOOKS order by ", "TI<caret>");
        assertEquals(items.toString(), List.of("TITLE"), items.stream().filter(item -> item.startsWith("TI")).toList());
        var afterColumn = lookups("select * from BOOKS where TITLE = 'x' ", "<caret>");
        assertTrue(afterColumn.toString(), afterColumn.contains("and"));
    }

    public void testInspectionsLeaveEmbeddedFilesToTheirOwnPainting() {
        loadSchema();
        myFixture.enableInspections(LatticeSqlSyntaxInspection.class);
        myFixture.configureByText("console.sql", "selct * from books");
        assertFalse("the same text in a file is an error", myFixture.doHighlighting().stream().noneMatch(info -> info.getSeverity() == HighlightSeverity.ERROR));
        myFixture.configureByText("console2.sql", "selct * from books");
        new SqlEmbeddedBinding(h2.getId(), null, "").attachTo(myFixture.getFile().getVirtualFile());
        assertTrue(myFixture.doHighlighting().stream().noneMatch(info -> info.getSeverity() == HighlightSeverity.ERROR));
    }

    private List<String> findings(Editor editor) {
        return Arrays.stream(highlighters(editor)).map(RangeHighlighter::getErrorStripeTooltip)
                .filter(tooltip -> tooltip instanceof HighlightInfo).map(tooltip -> ((HighlightInfo) tooltip).getDescription()).toList();
    }

    /** The findings are in the document's markup, where hover and Alt+Enter look for them. */
    private RangeHighlighter[] highlighters(Editor editor) {
        return com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(editor.getDocument(), getProject(), false).getAllHighlighters();
    }

    public void testAnEmbeddedConsolePaintsTheFindingsOfItsText() {
        loadSchema();
        var input = EmbeddedSqlInput.create(getProject(), h2, null, "", true, "select * from nowhere", "test console");
        try {
            EditorEx editor = ((EditorTextField) input.component()).getEditor(true);
            assertNotNull(editor);
            PlatformTestUtil.waitWithEventsDispatching("The unknown table was never reported", () -> findings(editor).stream().anyMatch(d -> d.contains("nowhere")), 30);
            input.setText("select * from books");
            PlatformTestUtil.waitWithEventsDispatching("The finding was never cleared", () -> findings(editor).isEmpty(), 30);
            input.setText("select * from");
            PlatformTestUtil.waitWithEventsDispatching("The unfinished statement was not left alone", () -> findings(editor).isEmpty(), 30);
        } finally {
            input.dispose();
        }
    }

    /** The console reads its selection on the EDT outside any read action, as when it runs the selected SQL. */
    public void testTheSelectionCanBeReadOutsideAReadAction() {
        var input = EmbeddedSqlInput.create(getProject(), h2, null, "", true, "select 1;\nselect 2", "test console");
        try {
            assertNotNull(((EditorTextField) input.component()).getEditor(true));
            input.select(10, 18);
            // A plain Swing event, as the console's query callback is: it holds no lock of the platform's.
            int[] read = new int[3];
            javax.swing.SwingUtilities.invokeLater(() -> {
                read[0] = input.selectionStart();
                read[1] = input.selectionEnd();
                read[2] = input.caretOffset();
            });
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(10, read[0]);
            assertEquals(18, read[1]);
            assertEquals(18, read[2]);
        } finally {
            input.dispose();
        }
    }

    public void testAFieldDoesNotReportItsHiddenLeadIn() {
        loadSchema();
        var input = EmbeddedSqlInput.create(getProject(), h2, null, "select * from BOOKS where ", false, "TITEL = 'x'", "test filter");
        try {
            EditorEx editor = ((EditorTextField) input.component()).getEditor(true);
            PlatformTestUtil.waitWithEventsDispatching("The unknown column was never reported", () -> findings(editor).stream().anyMatch(d -> d.contains("TITEL")), 30);
            var found = Arrays.stream(highlighters(editor)).filter(h -> h.getErrorStripeTooltip() instanceof HighlightInfo).toList();
            assertEquals(1, found.size());
            assertEquals("the mark sits on the user's text, not shifted by the lead-in", "TITEL", editor.getDocument().getText(
                    new com.intellij.openapi.util.TextRange(found.get(0).getStartOffset(), found.get(0).getEndOffset())));
        } finally {
            input.dispose();
        }
    }

    public void testAFindingShowsItsMessageOnHoverAndOffersTheLikelyFixOnAltEnter() {
        loadSchema();
        var input = EmbeddedSqlInput.create(getProject(), h2, null, "", true, "select * from bookz", "test console");
        try {
            EditorEx editor = ((EditorTextField) input.component()).getEditor(true);
            PlatformTestUtil.waitWithEventsDispatching("The unknown table was never reported", () -> findings(editor).stream().anyMatch(d -> d.contains("bookz")), 30);
            var info = Arrays.stream(highlighters(editor)).map(RangeHighlighter::getErrorStripeTooltip).filter(t -> t instanceof HighlightInfo)
                    .map(t -> (HighlightInfo) t).findFirst().orElseThrow();
            assertNotNull("the hover tooltip", info.getToolTip());
            var fixes = new java.util.ArrayList<String>();
            info.findRegisteredQuickFix((descriptor, range) -> { fixes.add(descriptor.getAction().getText()); return null; });
            assertEquals(fixes.toString(), List.of("Replace with 'BOOKS'"), fixes);
            // applying it
            var action = info.findRegisteredQuickFix((descriptor, range) -> descriptor.getAction());
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> action.invoke(getProject(), editor, null));
            assertEquals("select * from BOOKS", input.text());
        } finally {
            input.dispose();
        }
    }

    public void testAOneLineFieldAddsNoFrameOrPaddingOfItsOwn() {
        loadSchema();
        var input = EmbeddedSqlInput.create(getProject(), h2, null, "select * from BOOKS where ", false, "ID > 3", "test filter");
        try {
            EditorEx editor = ((EditorTextField) input.component()).getEditor(true);
            var insets = editor.getScrollPane().getBorder() == null ? new java.awt.Insets(0, 0, 0, 0) : editor.getScrollPane().getBorder().getBorderInsets(editor.getScrollPane());
            assertEquals(new java.awt.Insets(0, 0, 0, 0), insets);
            var framed = com.segfault03.ideadb.ui.DatabaseInputs.editorField(input.component(), 20);
            assertTrue("the frame is tall enough for a line of the editor's font",
                    framed.getPreferredSize().height >= input.component().getPreferredSize().height + framed.getInsets().top + framed.getInsets().bottom);
        } finally {
            input.dispose();
        }
    }

    public void testTheNamespaceOfAConsoleIsWhereUnqualifiedNamesResolve() {
        loadSchema();
        myFixture.configureByText("console.sql", "select * from books");
        var file = myFixture.getFile().getVirtualFile();
        var binding = new SqlEmbeddedBinding(h2.getId(), "INFORMATION_SCHEMA", "");
        binding.attachTo(file);
        assertEquals("INFORMATION_SCHEMA", com.segfault03.ideadb.sql.binding.SqlDialectResolver.schema(h2, file).defaultNamespace());
        binding.namespace("PUBLIC");
        assertEquals("PUBLIC", com.segfault03.ideadb.sql.binding.SqlDialectResolver.schema(h2, file).defaultNamespace());
        binding.namespace("NOT_A_NAMESPACE");
        assertEquals("a namespace the schema does not have changes nothing", "PUBLIC", com.segfault03.ideadb.sql.binding.SqlDialectResolver.schema(h2, file).defaultNamespace());
    }
}
