package com.segfault03.ideadb.sql.inject;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.doc.SqlDocumentationTarget;
import com.segfault03.ideadb.sql.lang.LatticeSqlLanguage;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings;
import com.segfault03.ideadb.sql.validate.LatticeSqlSyntaxInspection;
import com.segfault03.ideadb.sql.validate.LatticeSqlUnknownColumnInspection;
import com.segfault03.ideadb.sql.validate.LatticeSqlUnknownTableInspection;

import java.util.Arrays;
import java.util.List;

/** SQL in Kotlin strings, in a Kotlin-enabled fixture: plain, raw and template strings, concatenation and the same tiers as Java. */
public class SqlInjectionKotlinFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() {
        return new String[] {
                "CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(40))",
                "CREATE TABLE BOOKS (ID INTEGER NOT NULL PRIMARY KEY, TITLE VARCHAR(60), AUTHOR_ID INTEGER NOT NULL, "
                        + "FOREIGN KEY (AUTHOR_ID) REFERENCES AUTHORS (ID))"};
    }

    /** The SQL text injected at the caret, or null when the caret is not in injected SQL. */
    private String injectedAtCaret(String source) {
        myFixture.configureByText("A.kt", source);
        PsiFile file = myFixture.getFile();
        return file.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE) ? file.getText() : null;
    }

    public void testAStringThatLooksLikeSqlIsInjected() {
        assertEquals("select * from orders where id = ?", injectedAtCaret("val q = \"select * from ord<caret>ers where id = ?\""));
    }

    public void testProseIsLeftAlone() {
        assertNull(injectedAtCaret("val s = \"Select an item fr<caret>om the list\""));
        assertNull(injectedAtCaret("fun f() { println(\"Update the sett<caret>ings to apply your changes\") }"));
    }

    public void testTemplateEntriesBecomePlaceholders() {
        assertEquals("select * from orders where id = ?", injectedAtCaret("fun f(id: Int) = \"select * from ord<caret>ers where id = $id\""));
        assertEquals("select * from ? where id = 1", injectedAtCaret("fun f(t: String) = \"select * from ${t.trim()} where i<caret>d = 1\""));
        assertEquals("select * from ? where id = ? and x = ?", injectedAtCaret(
                "fun f(t: String, id: Int) = \"select * from $t where id = ${id} and <caret>x = ?\""));
    }

    public void testARawStringWithTrimIndent() {
        String text = injectedAtCaret("val q = \"\"\"\n    select o.<caret>id\n      from orders o\n    \"\"\".trimIndent()");
        assertNotNull(text);
        assertTrue(text, text.contains("select o.id") && text.contains("from orders o"));
    }

    public void testAnEscapeInTheStringDoesNotHideTheStatementFromTheHeuristic() {
        String text = injectedAtCaret("val q = \"select *\\nfrom ord<caret>ers where id = ?\"");
        assertNotNull("the decision reads the decoded text, where \\n is whitespace", text);
        assertTrue(text, text.contains("from orders where id = ?"));
    }

    public void testAConcatenationIsOneInjection() {
        String source = "fun f(t: String, id: Int) = \"select * from \" + t + \" where id = \" + id + \" and x = ?\"";
        assertEquals("select * from ? where id = ? and x = ?", injectedAtCaret(source.replace("select * from", "sel<caret>ect * from")));
        assertEquals("select * from ? where id = ? and x = ?", injectedAtCaret(source.replace("and x = ?", "and <caret>x = ?")));
    }

    public void testACallThatTakesSqlInjectsItsFirstString() {
        assertEquals("select ? from dual", injectedAtCaret("fun f(c: java.sql.Connection) { c.prepareStatement(\"select ? from <caret>dual\") }"));
        assertNull(injectedAtCaret("fun f(t: Task) { t.update(\"order<caret>s\") }"));
        assertEquals("update orders set total = 1", injectedAtCaret("fun f(j: Jdbc) { j.update(\"update ord<caret>ers set total = 1\") }"));
    }

    public void testMarkersAndAnnotationsDecideExplicitly() {
        assertEquals("anything goes here", injectedAtCaret("// language=SQL\nval q = \"anything go<caret>es here\""));
        assertEquals("anything goes here", injectedAtCaret("fun f() {\n    //language=PostgreSQL\n    val q = \"anything go<caret>es here\"\n}"));
        assertEquals("anything goes here", injectedAtCaret("@Language(\"SQL\") val q = \"anything go<caret>es here\""));
        assertNull(injectedAtCaret("@Language(\"RegExp\") val q = \"anything go<caret>es here\""));
        assertNull(injectedAtCaret("// lattice:not-sql\nval q = \"select * from ord<caret>ers where id = ?\""));
        assertNull(injectedAtCaret("fun f(c: java.sql.Connection) {\n    // lattice:not-sql\n    c.prepareStatement(\"select ? from <caret>dual\")\n}"));
    }

    public void testANativeQueryAnnotationIsSql() {
        assertEquals("select * from orders where id = ?1", injectedAtCaret("@Query(value = \"select * from ord<caret>ers where id = ?1\", nativeQuery = true)\nfun f() {}"));
        assertNull(injectedAtCaret("@Query(\"from Ord<caret>er where id = ?1\")\nfun f() {}"));
    }

    public void testTheWholeFeatureCanBeSwitchedOff() {
        SqlEditorSettings.getInstance().setInjection(false);
        assertNull(injectedAtCaret("val q = \"select * from ord<caret>ers where id = ?\""));
    }

    // ---- editor features inside the string -------------------------------------------------------------------------

    private List<String> completeIn(String source) {
        myFixture.configureByText("A.kt", source);
        var items = myFixture.completeBasic();
        return items == null ? List.of() : Arrays.stream(items).map(item -> item.getLookupString()).toList();
    }

    public void testCompletionInAPlainRawAndTemplateString() {
        loadSchema();
        var expected = List.of("AUTHOR_ID", "ID", "TITLE");
        assertEquals(expected, completeIn("fun f(c: java.sql.Connection) { c.prepareStatement(\"select b.<caret> from books b\") }"));
        assertEquals(expected, completeIn("val q = \"\"\"\n  select b.<caret>\n    from books b\n\"\"\""));
        assertEquals(expected, completeIn("fun f(id: Int) = \"select b.<caret> from books b where b.id = $id\""));
    }

    public void testHoverInsideAStringShowsTheTable() {
        loadSchema();
        myFixture.configureByText("A.kt", "val q = \"select * from <caret>books where id = ?\"");
        var targets = IdeDocumentationTargetProvider.getInstance(getProject())
                .documentationTargets(myFixture.getEditor(), myFixture.getFile(), myFixture.getCaretOffset());
        assertEquals(targets.toString(), 1, targets.size());
        assertTrue(((SqlDocumentationTarget) targets.get(0)).html().contains("PUBLIC.BOOKS"));
    }

    /** What the intention changed is in the source file, whose editor the fixture hides while the caret is inside SQL. */
    private Document hostDocument() {
        var top = InjectedLanguageManager.getInstance(getProject()).getTopLevelFile(myFixture.getFile());
        return PsiDocumentManager.getInstance(getProject()).getDocument(top);
    }

    private boolean isSqlNow(PsiFile top) {
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        // comments are injection hosts too; the string is the one that is not a comment
        var host = PsiTreeUtil.findChildrenOfType(top, PsiLanguageInjectionHost.class).stream()
                .filter(candidate -> !(candidate instanceof PsiComment)).findFirst().orElseThrow();
        var files = InjectedLanguageManager.getInstance(getProject()).getInjectedPsiFiles(host);
        return files != null && files.stream().anyMatch(pair -> pair.first.getLanguage().isKindOf(LatticeSqlLanguage.INSTANCE));
    }

    private IntentionAction intention(String name) {
        return myFixture.getAvailableIntentions().stream().filter(action -> action.getText().equals(name)).findFirst().orElse(null);
    }

    public void testTheNotSqlIntentionMarksAnInjectedString() {
        myFixture.configureByText("A.kt", "fun f() {\n    val q = \"select * from ord<caret>ers where id = ?\"\n}");
        var action = intention("Do not treat this string as SQL");
        assertNotNull(action);
        var top = InjectedLanguageManager.getInstance(getProject()).getTopLevelFile(myFixture.getFile());
        var doc = hostDocument();
        myFixture.launchAction(action);
        assertTrue(doc.getText().contains("    // lattice:not-sql\n    val q = "));
        assertFalse(isSqlNow(top));
    }

    public void testTheAsSqlIntentionMarksAProseLikeString() {
        myFixture.configureByText("A.kt", "fun f() {\n    val q = \"anything go<caret>es here\"\n}");
        var action = intention("Treat this string as SQL");
        assertNotNull(action);
        var top = InjectedLanguageManager.getInstance(getProject()).getTopLevelFile(myFixture.getFile());
        var doc = hostDocument();
        myFixture.launchAction(action);
        assertTrue(doc.getText().contains("    // language=SQL\n    val q = "));
        assertTrue(isSqlNow(top));
    }

    // ---- validation inside strings -----------------------------------------------------------------------------------

    private List<String> problemsIn(String source) {
        myFixture.enableInspections(LatticeSqlSyntaxInspection.class,
                LatticeSqlUnknownTableInspection.class, LatticeSqlUnknownColumnInspection.class);
        myFixture.configureByText("A.kt", source);
        return myFixture.doHighlighting().stream().filter(info -> info.getSeverity().compareTo(HighlightSeverity.WEAK_WARNING) >= 0)
                .map(info -> info.getDescription()).filter(d -> d != null && (d.startsWith("Unknown") || d.contains("expected") || d.startsWith("Unmatched") || d.startsWith("Unclosed"))).toList();
    }

    public void testASqlStringIsValidatedLikeAFile() {
        loadSchema();
        assertEquals(List.of(), problemsIn("val q = \"select a.name from authors a join books b on b.author_id = a.id where b.title = ?\""));
        var found = problemsIn("val q = \"select * from authorz where id = ?\"");
        assertEquals(found.toString(), 1, found.size());
        assertTrue(found.get(0), found.get(0).contains("authorz"));
        assertEquals(1, problemsIn("val q = \"select titel from books where id = ?\"").size());
        assertEquals(1, problemsIn("val q = \"select * from books where\"").size());
        assertEquals(List.of(), problemsIn("val q = \"\"\"\n    select b.title\n      from books b\n     where b.id = ?\n    \"\"\".trimIndent()"));
        assertEquals(1, problemsIn("val q = \"\"\"\n    select b.title\n      from bookz b\n    \"\"\".trimIndent()").size());
    }

    public void testAStringWithTemplateEntriesIsNotJudged() {
        loadSchema();
        assertEquals(List.of(), problemsIn("fun f(cols: String) = \"select $cols from authorz where x = 1\""));
        assertEquals(List.of(), problemsIn("fun f(t: String) = \"select * from ${t.trim()} where\""));
        assertEquals(List.of(), problemsIn("fun f(t: String) = \"select * from \" + t + \" where\""));
    }

    public void testAStringWithAnEscapeIsNotJudged() {
        loadSchema();
        assertEquals(List.of(), problemsIn("val q = \"select *\\nfrom authorz\""));
    }
}
