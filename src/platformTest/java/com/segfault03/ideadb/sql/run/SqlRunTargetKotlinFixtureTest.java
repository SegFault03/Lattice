package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.application.ReadAction;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

import java.util.List;

/** The SQL an editor action works on inside Kotlin strings, including templates. */
public class SqlRunTargetKotlinFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private SqlRunTarget.Resolution resolve() {
        return ReadAction.compute(() -> SqlRunTarget.resolve(getProject(), myFixture.getEditor(), myFixture.getFile()));
    }

    public void testKotlinTemplate() {
        myFixture.configureByText("A.kt", "fun f(id: Int) = \"select * from t wh<caret>ere id = $id\"");
        SqlRunTarget.Resolution resolution = resolve();
        assertTrue(String.valueOf(resolution), resolution instanceof SqlRunTarget.Found);
        SqlRunTarget target = ((SqlRunTarget.Found) resolution).target();
        assertEquals("select * from t where id = ?", target.sql());
        assertEquals(List.of("1"), target.parameters().keys());
        assertEquals("A.kt", target.file().getName());
    }

    public void testNonSqlStringGivesNull() {
        myFixture.configureByText("A.kt", "val s = \"Select an item fr<caret>om the list\"");
        assertNull(resolve());
    }
}
