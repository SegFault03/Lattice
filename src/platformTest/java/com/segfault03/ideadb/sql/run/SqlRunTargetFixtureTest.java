package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.application.ReadAction;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

/** The statement an editor action works on in a .sql file: the caret's statement or the single selected one. */
public class SqlRunTargetFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private SqlRunTarget.Resolution resolve() {
        return ReadAction.compute(() -> SqlRunTarget.resolve(getProject(), myFixture.getEditor(), myFixture.getFile()));
    }

    private SqlRunTarget found() {
        SqlRunTarget.Resolution resolution = resolve();
        assertTrue(String.valueOf(resolution), resolution instanceof SqlRunTarget.Found);
        return ((SqlRunTarget.Found) resolution).target();
    }

    public void testSqlFileCaretStatement() {
        myFixture.configureByText("q.sql", "select 1;\nselect * from t where <caret>id = ?;\nselect 3;");
        SqlRunTarget target = found();
        assertEquals("select * from t where id = ?", target.sql());
        assertEquals(myFixture.getFile().getVirtualFile(), target.file());
        assertEquals(java.util.List.of("1"), target.parameters().keys());
    }

    public void testSqlFileCaretInLeadingWhitespaceIsAProblem() {
        myFixture.configureByText("q.sql", "  <caret>\nselect 1");
        assertEquals(new SqlRunTarget.Problem("Select a single statement"), resolve());
    }

    public void testSqlFileSelectionOfOneStatement() {
        myFixture.configureByText("q.sql", "select 1;\n<selection>  select 2  </selection>;\nselect 3;");
        assertEquals("select 2", found().sql());
    }

    public void testSqlFileSelectionOfTwoGivesProblem() {
        myFixture.configureByText("q.sql", "<selection>select 1;\nselect 2;</selection>\nselect 3;");
        assertEquals(new SqlRunTarget.Problem("Select a single statement"), resolve());
    }

    public void testSqlFileSelectionOfOnlyACommentGivesProblem() {
        myFixture.configureByText("q.sql", "<selection>-- nothing</selection>\nselect 3;");
        assertEquals(new SqlRunTarget.Problem("Select a single statement"), resolve());
    }

    public void testUnboundFileHasNullConnection() {
        connections.removeConnection(h2.getId());
        connections.addConnection(new com.segfault03.ideadb.model.ConnectionConfig(com.segfault03.ideadb.model.DatabaseType.H2, "a"));
        connections.addConnection(new com.segfault03.ideadb.model.ConnectionConfig(com.segfault03.ideadb.model.DatabaseType.H2, "b"));
        myFixture.configureByText("q.sql", "select <caret>1");
        SqlRunTarget target = found();
        assertNull(target.connection());
    }

    public void testBoundFileGetsItsConnectionAndDialect() {
        myFixture.configureByText("q.sql", "select <caret>1");
        SqlBindingsState.getInstance(getProject()).bindFileToConnection(myFixture.getFile().getVirtualFile(), h2.getId());
        SqlRunTarget target = found();
        assertEquals(h2.getId(), target.connection().getId());
        assertEquals(SqlDialects.of(h2.getType()), target.profile());
    }

    public void testNonSqlFileGivesNull() {
        myFixture.configureByText("notes.txt", "select <caret>1");
        assertNull(resolve());
    }
}
