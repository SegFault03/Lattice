package com.segfault03.ideadb.sql.inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlStringHeuristicTest {
    private static void sql(String text) { assertTrue(SqlStringHeuristic.looksLikeSql(text), "should be SQL: " + text); }
    private static void notSql(String text) { assertFalse(SqlStringHeuristic.looksLikeSql(text), "should not be SQL: " + text); }

    @Test void statementsWithAClearSecondSignAreSql() {
        sql("select * from orders");
        sql("SELECT id, name FROM customers");
        sql("select o.id from orders o");
        sql("select count(*) from orders where total > 10");
        sql("select name from customers where id = ?");
        sql("select name from customers where id = :id");
        sql("  \n select name\n  from customers\n order by name");
        sql("select a.x, b.y from a join b on a.id = b.id");
        sql("-- latest\nselect * from orders");
        sql("/* c */ select * from \"Order Items\"");
        sql("SELECT name FROM customers");
        sql("select distinct country from customers where active = 1");
        sql("select * from (select id from t) x");
        sql("select coalesce(a, b) as v, round(sum(x), 2) total from t");
    }

    @Test void otherStatementKindsAreRecognizedByTheirShape() {
        sql("insert into orders (id, total) values (?, ?)");
        sql("INSERT INTO orders VALUES (1, 2)");
        sql("insert into archive select * from orders");
        sql("update orders set total = ? where id = ?");
        sql("update orders o set o.total = 0");
        sql("delete from orders where id = ?");
        sql("DELETE FROM orders;");
        sql("with recent as (select * from orders) select * from recent");
        sql("create table t (id int primary key)");
        sql("create table if not exists t (id int)");
        sql("create index idx on orders (customer_id)");
        sql("create view v as select * from orders");
        sql("alter table orders add column note text");
        sql("drop table if exists orders");
        sql("truncate table orders");
        sql("explain select * from orders where id = 1");
        sql("merge into t using s on (t.id = s.id) when matched then update set t.x = s.x");
    }

    @Test void proseThatStartsWithAVerbIsNot() {
        notSql("Select an item from the list");
        notSql("Select an option from the menu to continue");
        notSql("Select a file from disk.");
        notSql("Update the settings to apply your changes");
        notSql("Delete from the cart");
        notSql("Insert into the slot");
        notSql("Create table of contents");
        notSql("With this option you can select items from the list");
        notSql("Select the best matching option from the list below");
        notSql("Drop it like it's hot.");
        notSql("Select your country:");
    }

    @Test void thingsThatAreNotStatementsAreNot() {
        notSql("");
        notSql("select");
        notSql("hello world");
        notSql("/api/orders/select/from");
        notSql("select * ");
        notSql("selected from orders");
        notSql("SELECT 1");
        notSql("order by name");
    }

    @Test void startsLikeStatementIsOnlyAboutTheLeadingWord() {
        assertTrue(SqlStringHeuristic.startsLikeStatement("select 1"));
        assertTrue(SqlStringHeuristic.startsLikeStatement("  -- c\n update t set x = 1"));
        assertTrue(SqlStringHeuristic.startsLikeStatement("insert into t values (1)"));
        assertFalse(SqlStringHeuristic.startsLikeStatement("name"));
        assertFalse(SqlStringHeuristic.startsLikeStatement("selection"));
        assertFalse(SqlStringHeuristic.startsLikeStatement("updated_at"));
    }

    @Test void aPlaceholderWhereTheTableShouldBeStillReadsAsSql() {
        sql("select * from ? where id = 1");
        sql("select * from orders where ? = 1");
        sql("select ? from orders where id = ?");
    }
}
