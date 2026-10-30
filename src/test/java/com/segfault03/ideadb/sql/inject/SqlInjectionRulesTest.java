package com.segfault03.ideadb.sql.inject;

import com.segfault03.ideadb.sql.inject.SqlInjectionRules.Surroundings;
import com.segfault03.ideadb.sql.inject.SqlInjectionRules.Tiers;
import com.segfault03.ideadb.sql.inject.SqlMarkers.Mark;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlInjectionRulesTest {
    private static final Tiers ALL = new Tiers(true, true, true, SqlKnownCalls.parse(SqlKnownCalls.DEFAULT));
    private static Surroundings call(String callee) { return new Surroundings(Mark.NONE, false, callee, false); }

    @Test void anExplicitMarkWinsOverContent() {
        assertTrue(SqlInjectionRules.shouldInject(new Surroundings(Mark.SQL, false, null, false), "anything at all", ALL));
        assertTrue(SqlInjectionRules.shouldInject(new Surroundings(Mark.NONE, true, null, false), "x y z", ALL));
    }

    @Test void aSuppressingMarkWinsOverEverything() {
        assertFalse(SqlInjectionRules.shouldInject(new Surroundings(Mark.NOT_SQL, true, "executeQuery", true), "select * from orders", ALL));
    }

    @Test void aKnownCallInjectsItsStringEvenWhenTheTextIsOdd() {
        assertTrue(SqlInjectionRules.shouldInject(call("prepareStatement"), "select ? from dual", ALL));
        assertTrue(SqlInjectionRules.shouldInject(call("prepareStatement"), "call do_it(?)", ALL));
        assertTrue(SqlInjectionRules.shouldInject(call("createNativeQuery"), "SELECT 1", ALL));
        assertTrue(SqlInjectionRules.shouldInject(call("PrepareStatement"), "x", ALL), "method names match ignoring case");
    }

    @Test void commonNamesNeedTheStringToBeginLikeAStatement() {
        assertTrue(SqlInjectionRules.shouldInject(call("update"), "update orders set total = 1", ALL));
        assertFalse(SqlInjectionRules.shouldInject(call("update"), "orders", ALL));
        assertFalse(SqlInjectionRules.shouldInject(call("execute"), "some task name", ALL));
        assertTrue(SqlInjectionRules.shouldInject(call("query"), "select * from t", ALL));
        assertFalse(SqlInjectionRules.shouldInject(call("fetch"), "customers", ALL));
    }

    @Test void aNativeQueryAnnotationCountsAsAKnownCall() {
        assertTrue(SqlInjectionRules.shouldInject(new Surroundings(Mark.NONE, false, null, true), "select * from orders where id = ?1", ALL));
        assertFalse(SqlInjectionRules.shouldInject(new Surroundings(Mark.NONE, false, null, true), "select * from orders",
                new Tiers(true, false, false, ALL.calls())));
    }

    @Test void contentAloneOnlyInjectsWhenItLooksLikeSql() {
        assertTrue(SqlInjectionRules.shouldInject(Surroundings.none(), "select * from orders where id = ?", ALL));
        assertFalse(SqlInjectionRules.shouldInject(Surroundings.none(), "Select an item from the list", ALL));
        assertFalse(SqlInjectionRules.shouldInject(call("println"), "select * from orders where id = ?", new Tiers(true, true, false, ALL.calls())));
    }

    @Test void everyTierCanBeSwitchedOff() {
        var off = new Tiers(false, false, false, ALL.calls());
        assertFalse(SqlInjectionRules.shouldInject(new Surroundings(Mark.SQL, true, "prepareStatement", true), "select * from orders where id = ?", off));
        assertFalse(SqlInjectionRules.shouldInject(call("prepareStatement"), "select 1", new Tiers(true, false, true, ALL.calls())));
        assertFalse(SqlInjectionRules.shouldInject(new Surroundings(Mark.SQL, false, null, false), "x y z", new Tiers(false, true, true, ALL.calls())));
    }

    @Test void blankStringsAreNeverInjected() {
        assertFalse(SqlInjectionRules.shouldInject(new Surroundings(Mark.SQL, true, "executeQuery", false), "   ", ALL));
    }

    @Test void theKnownCallListIsEditable() {
        var calls = SqlKnownCalls.parse("# mine\nrunSql\n  Db.fetchRows  \n\nprepareStatement, other");
        assertTrue(calls.contains("runSql") && calls.contains("fetchRows") && calls.contains("prepareStatement") && calls.contains("other"));
        assertFalse(calls.contains("executeQuery"));
        assertFalse(calls.contains("# mine"));
    }

    @Test void markersAreReadFromCommentsAndAnnotationValues() {
        assertEquals(Mark.SQL, SqlMarkers.ofComment("// language=SQL"));
        assertEquals(Mark.SQL, SqlMarkers.ofComment("//language=PostgreSQL"));
        assertEquals(Mark.SQL, SqlMarkers.ofComment("/* language = mysql */"));
        assertEquals(Mark.NONE, SqlMarkers.ofComment("// language=JSON"));
        assertEquals(Mark.NONE, SqlMarkers.ofComment("// just a note"));
        assertEquals(Mark.NOT_SQL, SqlMarkers.ofComment("// lattice:not-sql"));
        assertEquals(Mark.NOT_SQL, SqlMarkers.ofComment("// Lattice : not-sql because prose"));
        assertTrue(SqlMarkers.isSqlLanguage("SQL") && SqlMarkers.isSqlLanguage(" H2 ") && !SqlMarkers.isSqlLanguage("Regexp"));
    }

    @Test void theAssemblerJoinsLiteralsAndMarksUnknownPartsWithPlaceholders() {
        var parts = List.of(
                SqlInjectionAssembler.Part.<String>literal("a", 1, 15, "select * from "),
                SqlInjectionAssembler.Part.<String>dynamic(),
                SqlInjectionAssembler.Part.<String>literal("b", 1, 14, " where id = "),
                SqlInjectionAssembler.Part.<String>dynamic());
        var assembled = new SqlInjectionAssembler<>(parts);
        assertEquals("select * from ? where id = ?", assembled.text());
        assertEquals(2, assembled.places().size());
        assertNull(assembled.places().get(0).prefix());
        assertEquals("?", assembled.places().get(1).prefix());
        assertEquals("?", assembled.places().get(1).suffix());
    }

    @Test void adjacentUnknownPartsAndEmptyLiteralsCollapseToOnePlaceholder() {
        var parts = List.of(
                SqlInjectionAssembler.Part.<String>dynamic(), SqlInjectionAssembler.Part.<String>dynamic(),
                SqlInjectionAssembler.Part.<String>literal("a", 1, 1, ""),
                SqlInjectionAssembler.Part.<String>literal("b", 1, 8, " from t"));
        var assembled = new SqlInjectionAssembler<>(parts);
        assertEquals("? from t", assembled.text());
        assertEquals(1, assembled.places().size());
        assertEquals("?", assembled.places().get(0).prefix());
        assertEquals("", new SqlInjectionAssembler<String>(List.of(SqlInjectionAssembler.Part.<String>dynamic())).text());
    }
}
