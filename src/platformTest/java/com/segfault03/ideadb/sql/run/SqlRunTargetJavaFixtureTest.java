package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.application.ReadAction;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;

import java.util.List;

/** The SQL an editor action works on inside Java strings, where the injected text has placeholders for dynamic parts. */
public class SqlRunTargetJavaFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private SqlRunTarget.Resolution resolve() {
        return ReadAction.compute(() -> SqlRunTarget.resolve(getProject(), myFixture.getEditor(), myFixture.getFile()));
    }

    private SqlRunTarget found(String members) {
        myFixture.configureByText("A.java", "class A {\n" + members + "\n}");
        SqlRunTarget.Resolution resolution = resolve();
        assertTrue(String.valueOf(resolution), resolution instanceof SqlRunTarget.Found);
        return ((SqlRunTarget.Found) resolution).target();
    }

    public void testJavaConcatenationBecomesPositionalParameters() {
        SqlRunTarget target = found("String f(int id) { return \"SELECT * FROM t WHERE id = \" + id + \"\"; }".replace("\"SELECT", "\"SEL<caret>ECT"));
        assertTrue(target.sql(), target.sql().endsWith("= ?"));
        assertEquals(List.of("1"), target.parameters().keys());
    }

    public void testJavaTextBlock() {
        SqlRunTarget target = found("String q = \"\"\"\n    select a;\n    from <caret>t where id = ?\n    \"\"\";");
        assertTrue(target.sql(), target.sql().contains("from t where id = ?"));
        assertEquals(List.of("1"), target.parameters().keys());
    }

    public void testCaretInAnInjectedStringUsesTheWholeTextAndTheHostFile() {
        SqlRunTarget target = found("String q = \"select * from a; sel<caret>ect * from b\";");
        assertEquals("select * from a; select * from b", target.sql());
        assertEquals("A.java", target.file().getName());
    }

    public void testSelectionInsideOneStatementOfAnInjectedString() {
        SqlRunTarget target = found("String q = \"select * from a; <selection>select * from b</selection>; select * from c\";");
        assertEquals("select * from b", target.sql());
    }

    public void testSelectionOfTwoStatementsInAnInjectedStringGivesProblem() {
        myFixture.configureByText("A.java", "class A { String q = \"<selection>select * from a; select * from b</selection>; select * from c\"; }");
        assertEquals(new SqlRunTarget.Problem("Select a single statement"), resolve());
    }

    public void testSelectionAcrossFragmentsUsesTheWholeText() {
        SqlRunTarget target = found("String f(int id) { return \"select * <selection>from t where id = \" + id + \" and</selection> x = 1\"; }");
        assertEquals("select * from t where id = ? and x = 1", target.sql());
    }

    public void testBoundJavaFileKeepsItsConnectionOnTheHostFile() {
        myFixture.configureByText("A.java", "class A { String q = \"select * from <caret>t\"; }");
        SqlBindingsState.getInstance(getProject()).bindFileToConnection(myFixture.getFile().getVirtualFile(), h2.getId());
        SqlRunTarget.Resolution resolution = resolve();
        assertTrue(String.valueOf(resolution), resolution instanceof SqlRunTarget.Found);
        assertEquals(h2.getId(), ((SqlRunTarget.Found) resolution).target().connection().getId());
    }

    public void testNonSqlStringGivesNull() {
        myFixture.configureByText("A.java", "class A { String s = \"Select an item fr<caret>om the list\"; }");
        assertNull(resolve());
    }

    public void testCommentOutsideTheStringGivesNull() {
        myFixture.configureByText("A.java", "class A { String q = \"select 1\"; // sel<caret>ect 2\n}");
        assertNull(resolve());
    }
}
