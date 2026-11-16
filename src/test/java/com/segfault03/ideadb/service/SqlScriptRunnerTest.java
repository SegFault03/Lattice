package com.segfault03.ideadb.service;

import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.run.SqlScript;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SqlScriptRunnerTest {
    private final String url = "jdbc:h2:mem:script_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    private final ConnectionConfig config = new ConnectionConfig(DatabaseType.H2, "script test");

    private Connection open() throws Exception { return new org.h2.Driver().connect(url, new java.util.Properties()); }

    private record Run(SqlScriptRunner.Summary summary, List<SqlScriptRunner.Outcome> outcomes, List<Integer> rolledBack) {}

    private Run run(String text, boolean stopOnError, boolean transaction) {
        var statements = SqlScript.parse(text, SqlDialects.H2).statements();
        List<SqlScriptRunner.Outcome> outcomes = new ArrayList<>();
        List<Integer> rolledBack = new ArrayList<>();
        var summary = new SqlScriptRunner().run(this::open, config, null, statements, new SqlScriptRunner.Options(stopOnError, transaction),
                new SqlScriptRunner.Listener() {
                    @Override public void finished(SqlScript.Statement statement, SqlScriptRunner.Outcome outcome) { outcomes.add(outcome); }
                    @Override public void rolledBack(List<SqlScript.Statement> statements) { statements.forEach(s -> rolledBack.add(s.index())); }
                });
        return new Run(summary, outcomes, rolledBack);
    }

    private long count(String table) throws Exception {
        try (Connection connection = open(); var rows = connection.createStatement().executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test void runsEveryStatementAndDescribesWhatEachDid() throws Exception {
        var run = run("create table t (a int); insert into t values (1), (2); select * from t; update t set a = 3 where a = 9", true, false);
        assertEquals(4, run.summary().succeeded());
        assertEquals(List.of("OK", "2 rows affected", "2 rows returned", "OK"), run.outcomes().stream().map(SqlScriptRunner.Outcome::result).toList());
        assertTrue(run.summary().changedSomething());
        assertEquals(2, count("t"));
    }

    @Test void stopsAtTheFirstErrorAndSkipsTheRest() throws Exception {
        var run = run("create table t (a int); insert into t values (1); insert into missing values (1); insert into t values (2)", true, false);
        assertEquals(List.of(SqlScriptRunner.Status.OK, SqlScriptRunner.Status.OK, SqlScriptRunner.Status.FAILED, SqlScriptRunner.Status.NOT_RUN),
                run.outcomes().stream().map(SqlScriptRunner.Outcome::status).toList());
        assertTrue(run.outcomes().get(2).result().contains("MISSING"), run.outcomes().get(2).result());
        assertEquals(new SqlScriptRunner.Summary(2, 1, 1, false, false, run.summary().millis(), null), run.summary());
        assertEquals(1, count("t"));
    }

    @Test void continuesPastErrorsWhenAsked() throws Exception {
        var run = run("create table t (a int); insert into missing values (1); insert into t values (2)", false, false);
        assertEquals(2, run.summary().succeeded());
        assertEquals(1, run.summary().failed());
        assertEquals(1, count("t"));
    }

    @Test void oneTransactionRollsBackEverythingOnError() throws Exception {
        try (Connection connection = open()) { connection.createStatement().execute("create table t (a int)"); }
        var run = run("insert into t values (1); insert into t values (2); insert into missing values (3); insert into t values (4)", false, true);
        assertTrue(run.summary().rolledBack());
        assertFalse(run.summary().changedSomething());
        assertEquals(List.of(1, 2), run.rolledBack());
        assertEquals(1, run.summary().notRun(), "an error in a transaction stops the run even when continuing was asked");
        assertEquals(0, count("t"));
    }

    @Test void oneTransactionCommitsWhenEverythingSucceeds() throws Exception {
        try (Connection connection = open()) { connection.createStatement().execute("create table t (a int)"); }
        var run = run("insert into t values (1); insert into t values (2)", true, true);
        assertFalse(run.summary().rolledBack());
        assertEquals(2, count("t"));
    }

    @Test void aConnectionFailureIsReportedForTheWholeRun() {
        var statements = SqlScript.parse("select 1; select 2", SqlDialects.H2).statements();
        var summary = new SqlScriptRunner().run(() -> { throw new java.sql.SQLException("Access denied", "28000", 1045); }, config, null,
                statements, new SqlScriptRunner.Options(true, false), (statement, outcome) -> {});
        assertEquals("Access denied (1045)", summary.connectionError());
        assertEquals(2, summary.notRun());
    }

    @Test void cancellingBeforeAStatementSkipsIt() {
        var runner = new SqlScriptRunner();
        runner.cancel();
        List<SqlScriptRunner.Status> statuses = new ArrayList<>();
        var summary = runner.run(this::open, config, null, SqlScript.parse("select 1; select 2", SqlDialects.H2).statements(),
                new SqlScriptRunner.Options(true, false), (statement, outcome) -> statuses.add(outcome.status()));
        assertTrue(summary.cancelled());
        assertEquals(List.of(SqlScriptRunner.Status.CANCELLED, SqlScriptRunner.Status.CANCELLED), statuses);
    }
}
