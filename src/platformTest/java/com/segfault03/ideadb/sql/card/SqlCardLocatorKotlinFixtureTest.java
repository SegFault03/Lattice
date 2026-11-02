package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.application.ReadAction;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

import java.util.List;

/** Cards for SQL injected into Kotlin strings. */
public class SqlCardLocatorKotlinFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[0]; }

    private List<SqlCard> cards(String source) {
        myFixture.configureByText("A.kt", source);
        return ReadAction.compute(() -> SqlCardLocator.cards(getProject(), myFixture.getFile()));
    }

    public void testRawStringIsOneCard() {
        List<SqlCard> cards = cards("val q = \"\"\"\n    select o.id\n      from orders o\n    \"\"\".trimIndent()");
        assertEquals(1, cards.size());
        assertEquals(2, cards.get(0).segments().size());
    }

    public void testTemplateBecomesPlaceholder() {
        List<SqlCard> cards = cards("fun f(id: Int) = \"select * from t where id = $id\"");
        assertEquals(1, cards.size());
        assertEquals("select * from t where id = ?", cards.get(0).sql());
    }
}
