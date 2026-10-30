package com.segfault03.ideadb.sql.inject;

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.PsiFile;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.binding.SqlDialectResolver;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.doc.SqlDocumentationTarget;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;

import java.util.Arrays;
import java.util.List;

/** SQL in Java strings, in a Java-enabled fixture: what gets injected, with what text, and that the editor features follow. */
public class SqlInjectionJavaFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))"};
    }

    /**
     * The SQL text injected at the caret, or null when the caret is not in injected SQL. The fixture hands back the
     * injected file when the caret is inside an injection, as the IDE's own editor does.
     */
    private String injectedAtCaret(String source) {
        myFixture.configureByText("A.java", source);
        PsiFile file = myFixture.getFile();
        return file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE) ? file.getText() : null;
    }

    private String inClass(String members) { return "import java.sql.*;\nclass A {\n" + members + "\n}"; }

    public void testAJdbcCallsStringIsSqlEvenWhenTheTextIsOdd() {
        assertEquals("select ? from dual", injectedAtCaret(inClass("void f(Connection c) throws Exception { c.prepareStatement(\"select ? from <caret>dual\"); }")));
        assertEquals("call do_it(?)", injectedAtCaret(inClass("void f(Connection c) throws Exception { c.prepareCall(\"call do_<caret>it(?)\"); }")));
    }

    public void testAStringThatLooksLikeSqlIsInjectedAnywhere() {
        assertEquals("select * from orders where id = ?", injectedAtCaret(inClass("String q = \"select * from ord<caret>ers where id = ?\";")));
    }

    public void testProseIsLeftAlone() {
        assertNull(injectedAtCaret(inClass("String s = \"Select an item fr<caret>om the list\";")));
        assertNull(injectedAtCaret(inClass("void f() { System.out.println(\"Update the sett<caret>ings to apply your changes\"); }")));
    }

    public void testCommonMethodNamesNeedAStatementLookingString() {
        assertNull(injectedAtCaret(inClass("void f(Task t) { t.update(\"order<caret>s\"); }")));
        assertEquals("update orders set total = 1", injectedAtCaret(inClass("void f(Jdbc j) { j.update(\"update ord<caret>ers set total = 1\"); }")));
    }

    public void testAConcatenationIsOneInjectionWithPlaceholdersForTheUnknownParts() {
        String source = inClass("String f(String t, int id) { return \"select * from \" + t + \" where id = \" + id + \" and x = ?\"; }");
        assertEquals("select * from ? where id = ? and x = ?", injectedAtCaret(source.replace("\" where id", "\" wh<caret>ere id")));
        assertEquals("select * from ? where id = ? and x = ?", injectedAtCaret(source.replace("select * from", "sel<caret>ect * from")));
        assertEquals("select * from ? where id = ? and x = ?", injectedAtCaret(source.replace("and x = ?", "and <caret>x = ?")));
    }

    public void testAConcatenationOfOnlyLiteralsAcrossLines() {
        String text = injectedAtCaret(inClass("String q = \"select o.id \"\n + \" from orders o \"\n + \"where o.<caret>id = ?\";"));
        assertEquals("select o.id  from orders o where o.id = ?", text);
    }

    public void testATextBlock() {
        String text = injectedAtCaret(inClass("String q = \"\"\"\n    select o.<caret>id\n      from orders o\n    \"\"\";"));
        assertNotNull(text);
        assertTrue(text, text.contains("select o.id") && text.contains("from orders o"));
    }

    public void testALanguageAnnotationOrCommentMakesAnyStringSql() {
        assertEquals("anything goes here", injectedAtCaret(inClass("@Language(\"SQL\") String q = \"anything go<caret>es here\";")));
        assertEquals("anything goes here", injectedAtCaret(inClass("// language=SQL\nString q = \"anything go<caret>es here\";")));
        assertEquals("anything goes here", injectedAtCaret(inClass("void f() {\n  //language=PostgreSQL\n  String q = \"anything go<caret>es here\";\n}")));
        assertNull(injectedAtCaret(inClass("@Language(\"RegExp\") String q = \"anything go<caret>es here\";")));
    }

    public void testANotSqlCommentSuppressesEvenAKnownCall() {
        assertNull(injectedAtCaret(inClass("// lattice:not-sql\nString q = \"select * from ord<caret>ers where id = ?\";")));
        assertNull(injectedAtCaret(inClass("void f(Connection c) throws Exception {\n  // lattice:not-sql\n  c.prepareStatement(\"select ? from <caret>dual\");\n}")));
    }

    public void testANativeQueryAnnotationIsSql() {
        assertEquals("select * from orders where id = ?1", injectedAtCaret(inClass("@Query(value = \"select * from ord<caret>ers where id = ?1\", nativeQuery = true)\nvoid f() {}")));
        assertNull("A JPQL @Query is not SQL", injectedAtCaret(inClass("@Query(\"from Ord<caret>er where id = ?1\")\nvoid f() {}")));
    }

    public void testEachTierCanBeSwitchedOff() {
        var settings = SqlEditorSettings.getInstance();
        settings.setInjectContent(false);
        assertNull(injectedAtCaret(inClass("String q = \"select * from ord<caret>ers where id = ?\";")));
        assertNotNull("known calls still work", injectedAtCaret(inClass("void f(Jdbc j) { j.queryForList(\"select ? from <caret>dual\"); }")));
        settings.setInjectKnownCalls(false);
        assertNull(injectedAtCaret(inClass("void f(Jdbc j) { j.queryForList(\"select ? from <caret>dual2\"); }")));
        assertNotNull("explicit annotations still work", injectedAtCaret(inClass("@Language(\"SQL\") String q = \"x y <caret>z\";")));
        settings.setInjectExplicit(false);
        // a changed text, so the platform does not reuse the injection it computed under the previous settings
        assertNull(injectedAtCaret(inClass("@Language(\"SQL\") String q = \"x y <caret>zz\";")));
    }

    public void testTheWholeFeatureCanBeSwitchedOff() {
        SqlEditorSettings.getInstance().setInjection(false);
        assertNull(injectedAtCaret(inClass("String q = \"select * from ord<caret>ers where id = ?\";")));
    }

    public void testTheKnownCallListIsEditable() {
        SqlEditorSettings.getInstance().knownCalls("runSql");
        assertNotNull(injectedAtCaret(inClass("void f(Db d) { d.runSql(\"just <caret>some text\"); }")));
        assertNull(injectedAtCaret(inClass("void f(Connection c) throws Exception { c.prepareStatement(\"just <caret>some text\"); }")));
    }

    // ---- editor features inside the string -------------------------------------------------------------------------

    private List<String> completeIn(String source) {
        myFixture.configureByText("A.java", source);
        var items = myFixture.completeBasic();
        return items == null ? List.of() : Arrays.stream(items).map(item -> item.getLookupString()).toList();
    }

    public void testCompletionInsideAStringUsesTheProjectsConnection() {
        loadSchema();
        var columns = completeIn(inClass("void f(Connection c) throws Exception { c.prepareStatement(\"select b.<caret> from books b\"); }"));
        assertEquals(columns.toString(), List.of("AUTHOR_ID", "ID", "TITLE"), columns);
        var tables = completeIn(inClass("void f(Connection c) throws Exception { c.prepareStatement(\"select * from <caret>\"); }"));
        assertTrue(tables.toString(), tables.containsAll(List.of("AUTHORS", "BOOKS")));
    }

    public void testCompletionInAConcatenationSeesTablesFromEarlierLiterals() {
        loadSchema();
        var columns = completeIn(inClass("String f(int id) { return \"select b.<caret> \" + \" from books b where b.id = \" + id; }"));
        assertEquals(columns.toString(), List.of("AUTHOR_ID", "ID", "TITLE"), columns);
    }

    public void testHoverInsideAStringShowsTheTable() {
        loadSchema();
        myFixture.configureByText("A.java", inClass("String q = \"select * from <caret>books where id = ?\";"));
        // Quick Documentation hands the provider the injected editor and file, with the offset inside the injection.
        var targets = IdeDocumentationTargetProvider.getInstance(getProject())
                .documentationTargets(myFixture.getEditor(), myFixture.getFile(), myFixture.getCaretOffset());
        assertEquals(targets.toString(), 1, targets.size());
        String html = ((SqlDocumentationTarget) targets.get(0)).html();
        assertTrue(html, html.contains("PUBLIC.BOOKS"));
    }

    public void testTheJavaFilesBindingGivesTheInjectedSqlItsDialect() {
        myFixture.configureByText("A.java", inClass("String q = \"select * from <caret>books where id = ?\";"));
        var injected = myFixture.getFile();
        var virtual = injected.getVirtualFile();
        var hostFile = InjectedLanguageManager.getInstance(getProject()).getTopLevelFile(injected).getVirtualFile();
        assertEquals("the sole connection applies", SqlDialects.H2, SqlDialectResolver.resolve(getProject(), virtual));

        SqlBindingsState.getInstance(getProject()).bindFileToDialect(hostFile, SqlDialects.POSTGRESQL.id());
        assertEquals("a binding on the Java file applies to the SQL inside it", SqlDialects.POSTGRESQL, SqlDialectResolver.resolve(getProject(), virtual));
    }

    // ---- intentions ------------------------------------------------------------------------------------------------

    /** What the intention changed is in the source file, whose editor the fixture hides while the caret is inside SQL. */
    private com.intellij.openapi.editor.Document hostDocument() {
        var top = InjectedLanguageManager.getInstance(getProject()).getTopLevelFile(myFixture.getFile());
        return com.intellij.psi.PsiDocumentManager.getInstance(getProject()).getDocument(top);
    }

    private boolean isSqlNow(com.intellij.psi.PsiFile top) {
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        // comments are injection hosts too; the string is the one that is not a comment
        var host = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(top, com.intellij.psi.PsiLanguageInjectionHost.class).stream()
                .filter(candidate -> !(candidate instanceof com.intellij.psi.PsiComment)).findFirst().orElseThrow();
        var files = InjectedLanguageManager.getInstance(getProject()).getInjectedPsiFiles(host);
        return files != null && files.stream().anyMatch(pair -> pair.first.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE));
    }

    private com.intellij.codeInsight.intention.IntentionAction intention(String name) {
        return myFixture.getAvailableIntentions().stream().filter(action -> action.getText().equals(name)).findFirst().orElse(null);
    }

    public void testTheNotSqlIntentionMarksAnInjectedStringAndTheStringStopsBeingSql() {
        myFixture.configureByText("A.java", inClass("void f() {\n    String q = \"select * from ord<caret>ers where id = ?\";\n}"));
        var action = intention("Do not treat this string as SQL");
        assertNotNull("offered inside SQL the plugin found by itself", action);
        assertNull("the opposite is not offered", intention("Treat this string as SQL"));
        var top = InjectedLanguageManager.getInstance(getProject()).getTopLevelFile(myFixture.getFile());
        var doc = hostDocument();
        myFixture.launchAction(action);
        String text = doc.getText();
        assertTrue(text, text.contains("    // lattice:not-sql\n    String q = \"select * from orders"));
        assertFalse("the string is no longer SQL", isSqlNow(top));
    }

    public void testTheAsSqlIntentionMarksAProseLikeStringAndTheStringBecomesSql() {
        myFixture.configureByText("A.java", inClass("void f() {\n    String q = \"anything go<caret>es here\";\n}"));
        assertNull(intention("Do not treat this string as SQL"));
        var action = intention("Treat this string as SQL");
        assertNotNull(action);
        var top = InjectedLanguageManager.getInstance(getProject()).getTopLevelFile(myFixture.getFile());
        var doc = hostDocument();
        myFixture.launchAction(action);
        assertTrue(doc.getText().contains("    // language=SQL\n    String q = \"anything goes here\""));
        assertTrue("the string is SQL now", isSqlNow(top));
    }

    public void testTheIntentionIsOfferedInAMultiLineCallAboveTheWholeStatement() {
        myFixture.configureByText("A.java", inClass("void f(Jdbc j) {\n    j.queryForList(\n        \"select * from ord<caret>ers where id = ?\",\n        1);\n}"));
        var doc = hostDocument();
        myFixture.launchAction(intention("Do not treat this string as SQL"));
        assertTrue(doc.getText().contains("    // lattice:not-sql\n    j.queryForList("));
    }

    public void testNoIntentionOutsideAString() {
        myFixture.configureByText("A.java", inClass("void <caret>f() {}"));
        assertNull(intention("Do not treat this string as SQL"));
        assertNull(intention("Treat this string as SQL"));
    }

    public void testTheFileConnectionActionIsOfferedInsideSqlStringsOnly() {
        var action = new com.segfault03.ideadb.sql.binding.ChooseSqlConnectionAction();
        myFixture.configureByText("A.java", inClass("String q = \"select * from <caret>books where id = ?\";"));
        assertTrue("inside a SQL string", myFixture.testAction(action).isEnabledAndVisible());
        myFixture.configureByText("B.java", inClass("String p = \"Select an item <caret>from the list\";"));
        assertFalse("inside a string that is not SQL", myFixture.testAction(action).isEnabledAndVisible());
    }

    public void testInjectionDoesNotNeedIndexesAndSurvivesIndexing() {
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertTrue(com.intellij.openapi.project.DumbService.isDumb(getProject()));
            // A call by name and a content match need no resolve; an assignment to a variable would, and must not throw.
            assertEquals("select ? from dual", injectedAtCaret(inClass("void f(Jdbc j) { j.queryForList(\"select ? from <caret>dual\"); }")));
            assertEquals("select * from orders where id = ?", injectedAtCaret(inClass("String q;\nvoid f() { q = \"select * from ord<caret>ers where id = ?\"; }")));
            assertNull(injectedAtCaret(inClass("String s = \"x y <caret>z\";")));
        });
    }

    // ---- validation inside strings -----------------------------------------------------------------------------------

    private List<String> problemsIn(String source) {
        myFixture.enableInspections(com.segfault03.ideadb.sql.validate.LatticeSqlSyntaxInspection.class,
                com.segfault03.ideadb.sql.validate.LatticeSqlUnknownTableInspection.class, com.segfault03.ideadb.sql.validate.LatticeSqlUnknownColumnInspection.class);
        myFixture.configureByText("A.java", source);
        return myFixture.doHighlighting().stream().filter(info -> info.getSeverity().compareTo(com.intellij.lang.annotation.HighlightSeverity.WEAK_WARNING) >= 0)
                .map(info -> info.getDescription()).filter(d -> d != null && (d.startsWith("Unknown") || d.contains("expected") || d.startsWith("Unmatched") || d.startsWith("Unclosed"))).toList();
    }

    public void testASqlStringIsValidatedLikeAFile() {
        loadSchema();
        assertEquals(List.of(), problemsIn(inClass("String q = \"select a.name from authors a join books b on b.author_id = a.id where b.title = ?\";")));
        var found = problemsIn(inClass("String q = \"select * from authorz where id = ?\";"));
        assertEquals(found.toString(), 1, found.size());
        assertTrue(found.get(0), found.get(0).contains("authorz"));
        assertEquals(1, problemsIn(inClass("String q = \"select titel from books where id = ?\";")).size());
        assertEquals(1, problemsIn(inClass("String q = \"select * from books where\";")).size());
    }

    public void testAStringBuiltFromUnknownPartsIsNotJudged() {
        loadSchema();
        assertEquals(List.of(), problemsIn(inClass("String f(String cols) { return \"select \" + cols + \" from authorz where x = 1\"; }")));
        assertEquals(List.of(), problemsIn(inClass("String f(String t) { return \"select * from \" + t + \" where\"; }")));
        // joined literals only are one statement and are judged
        assertEquals(1, problemsIn(inClass("String q = \"select * \" + \"from authorz\";")).size());
    }

    public void testAStringWithAnEscapeIsNotJudged() {
        loadSchema();
        assertEquals(List.of(), problemsIn(inClass("String q = \"select *\\nfrom authorz\";")));
    }
}
