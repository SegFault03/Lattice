package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.service.SqlScriptRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlScriptPanelTest {
    @BeforeAll static void enableSvgIcons() {
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        com.intellij.openapi.util.IconLoader.activate();
    }

    private static ConnectionConfig config() {
        ConnectionConfig config = new ConnectionConfig(DatabaseType.MYSQL, "shop db");
        config.setDatabaseName("shop");
        return config;
    }

    @Test void startsEmptyAndAsksForAFile() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new SqlScriptPanel(null, config(), "shop", () -> {});
            assertEquals(SqlScriptPanel.Phase.EMPTY, panel.phase());
            assertEquals("Choose a .sql file to check and run", panel.summary.getText());
            assertEquals("shop", panel.database.getSelectedItem());
            assertEquals("Problems", panel.tabs.getTitleAt(0));
            panel.dispose();
        });
    }

    @Test void showsTheScriptCountsStatementsAndReportsSyntaxProblems() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger changes = new AtomicInteger();
            var panel = new SqlScriptPanel(null, config(), "shop", changes::incrementAndGet);
            panel.setScript("select 1;\nselect (2;\n", null);
            panel.check();
            assertEquals(SqlScriptPanel.Phase.READY, panel.phase());
            assertEquals(2, panel.script().statements().size());
            assertEquals("select 1;\nselect (2;\n", panel.preview.getText());
            assertTrue(panel.errorCount() > 0);
            assertEquals(2, panel.problems.rows.get(0).line());
            assertTrue(panel.summary.getText().startsWith("2 statements"), panel.summary.getText());
            assertTrue(panel.tabs.getTitleAt(0).startsWith("Problems ("), panel.tabs.getTitleAt(0));
            assertTrue(changes.get() >= 2);
            panel.dispose();
        });
    }

    @Test void transactionForcesStopOnError() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new SqlScriptPanel(null, config(), null, () -> {});
            panel.continueOnError.setSelected(true);
            panel.singleTransaction.setSelected(true);
            assertTrue(panel.stopOnError.isSelected());
            assertFalse(panel.continueOnError.isEnabled());
            panel.singleTransaction.setSelected(false);
            assertTrue(panel.continueOnError.isEnabled());
            panel.dispose();
        });
    }

    @Test void runLogAndSummaryFollowTheRun() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new SqlScriptPanel(null, config(), "shop", () -> {});
            panel.setScript("insert into a values (1);\ninsert into b values (2);\ninsert into c values (3)", null);
            var statements = panel.script().statements();
            panel.log.start(statements);
            panel.log.finished(statements.get(0), new SqlScriptRunner.Outcome(SqlScriptRunner.Status.OK, "1 row affected", 4));
            panel.log.finished(statements.get(1), new SqlScriptRunner.Outcome(SqlScriptRunner.Status.FAILED, "Table 'shop.b' doesn't exist (1146)", 2));
            panel.log.finished(statements.get(2), new SqlScriptRunner.Outcome(SqlScriptRunner.Status.NOT_RUN, "not run", 0));
            panel.finish(new SqlScriptRunner.Summary(1, 1, 1, false, false, 0, 840, null));
            assertEquals("Stopped at statement 2 of 3: 1 succeeded, 1 failed, 1 not run  ·  0.84 s", panel.summary.getText());
            assertEquals("Run log (3)", panel.tabs.getTitleAt(1));
            assertEquals("", panel.log.getValueAt(2, 5), "a statement that did not run has no time");
            assertTrue(panel.logText().contains("2\t2\tinsert into b values (2)\tTable 'shop.b' doesn't exist (1146)\t2 ms"), panel.logText());
            panel.dispose();
        });
    }

    @Test void aStoppedRunResumesAtTheFailedStatementOnlyWhileEarlierStatementsAreUnchanged() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new SqlScriptPanel(null, config(), "shop", () -> {});
            panel.setScript("insert into a values (1);\ninsert into b values (2);\ninsert into c values (3)", null);
            panel.finish(new SqlScriptRunner.Summary(1, 1, 1, false, false, 0, 10, null));
            assertEquals(1, panel.resumeFrom());
            panel.setScript("insert into a values (1);\ninsert into bb values (2);\ninsert into c values (3)", null);
            assertEquals(1, panel.resumeFrom(), "fixing the failed statement keeps the resume point");
            panel.setScript("insert into a values (9);\ninsert into bb values (2);\ninsert into c values (3)", null);
            assertEquals(-1, panel.resumeFrom(), "an edited committed statement must not be skipped");
            panel.finish(new SqlScriptRunner.Summary(1, 1, 1, false, true, 0, 10, null));
            assertEquals(-1, panel.resumeFrom(), "a rolled-back run starts over");
            panel.dispose();
        });
    }

    @Test void postgresDdlIsTransactional() {
        assertNull(SqlScriptPanel.implicitDdlCommit(DatabaseType.POSTGRESQL));
        assertEquals("MySQL commits CREATE, ALTER and DROP immediately", SqlScriptPanel.implicitDdlCommit(DatabaseType.MYSQL));
    }
}
