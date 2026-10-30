package com.segfault03.ideadb.sql.schema;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.testFramework.PlatformTestUtil;
import com.segfault03.ideadb.sql.H2SqlFixtureTestCase;

public class SqlSchemaDaemonRestarterFixtureTest extends H2SqlFixtureTestCase {
    @Override protected String[] schemaStatements() { return new String[]{"CREATE TABLE AUTHORS (ID INTEGER NOT NULL PRIMARY KEY)"}; }

    private boolean upToDate() {
        var analyzer = (DaemonCodeAnalyzerImpl) DaemonCodeAnalyzer.getInstance(getProject());
        return analyzer.getFileStatusMap().allDirtyScopesAreNull(myFixture.getEditor().getDocument());
    }

    public void testAFinishedSchemaReadHighlightsOpenEditorsAgain() {
        loadSchema();
        myFixture.configureByText("q.sql", "select * from authors");
        myFixture.doHighlighting();
        assertTrue("the editor was analyzed", upToDate());

        SqlSchemaListener.publish(h2.getId());
        PlatformTestUtil.waitWithEventsDispatching("The daemon was never restarted", () -> !upToDate(), 30);
    }
}
