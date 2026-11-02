package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ReadAction;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

/** Copy SQL on a Kotlin template string. */
public class SqlCardActionsKotlinFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    public void testCopyTemplate() throws Exception {
        myFixture.configureByText("A.kt", "fun f(id: Int) = \"select * from t where id = $id\"");
        SqlCard card = ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile())).get(0);
        AnAction copy = ActionManager.getInstance().getAction(SqlCardActionsJavaFixtureTest.COPY);
        copy.actionPerformed(SqlCardActionsJavaFixtureTest.event(myFixture, copy, card));
        assertEquals("select * from t where id = ?", SqlCardActionsJavaFixtureTest.clipboard());
    }
}
