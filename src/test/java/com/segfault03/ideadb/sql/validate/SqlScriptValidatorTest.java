package com.segfault03.ideadb.sql.validate;

import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.dialect.SqlDialectProfile;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.validate.SqlDiagnostic.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A corpus of valid statements that must stay quiet, and of seeded mistakes that must be found. */
class SqlScriptValidatorTest {
    private static List<SqlDiagnostic> check(String sql, SqlDialectProfile profile) {
        DatabaseType type = profile.databaseType() == null ? DatabaseType.H2 : profile.databaseType();
        return SqlScriptValidator.validate(sql, profile, ValidationFixtures.shop(type));
    }

    private static void quiet(SqlDialectProfile profile, String... statements) {
        for (String sql : statements) {
            var found = check(sql, profile);
            assertTrue(found.isEmpty(), () -> profile.displayName() + ": valid SQL was flagged:\n" + sql + "\n" + describe(sql, found));
        }
    }

    private static String describe(String sql, List<SqlDiagnostic> found) {
        StringBuilder text = new StringBuilder();
        for (var d : found) text.append("  ").append(d.kind()).append(" '").append(sql, d.start(), d.end()).append("': ").append(d.message()).append('\n');
        return text.toString();
    }

    /** The one expected finding, with the text it points at. */
    private static SqlDiagnostic only(String sql, SqlDialectProfile profile, Kind kind, String marked) {
        var found = check(sql, profile);
        assertEquals(1, found.size(), () -> "expected one finding in\n" + sql + "\n" + describe(sql, found));
        var d = found.get(0);
        assertEquals(kind, d.kind(), () -> describe(sql, found));
        assertEquals(marked, sql.substring(d.start(), d.end()), () -> describe(sql, found));
        return d;
    }

    private static final SqlDialectProfile[] ALL = {SqlDialects.ANSI, SqlDialects.MYSQL, SqlDialects.POSTGRESQL, SqlDialects.HSQLDB, SqlDialects.H2};

    // ---- valid SQL stays quiet ----------------------------------------------------------------------------------------

    @Test void everyDialectAcceptsOrdinaryQueries() {
        for (var profile : ALL) quiet(profile,
                "select * from orders",
                "SELECT id, total FROM orders WHERE status = 'open' AND total > 10 ORDER BY created_at DESC",
                "select o.id, o.total, c.name from orders o join customers c on c.id = o.customer_id where o.status <> 'x'",
                "select o.id from orders o left join items i on i.order_id = o.id where i.id is null",
                "select customer_id, count(*) as n, sum(total) total from orders group by customer_id having count(*) > 1 order by n desc",
                "select customer_id, sum(total) as spent from orders group by customer_id order by spent",
                "select distinct status from orders",
                "select count(*) from orders",
                "select 1",
                "select now()",
                "select * from orders where id in (select order_id from items where qty > 1)",
                "select * from orders o where exists (select 1 from items i where i.order_id = o.id)",
                "select * from orders o where total > (select avg(total) from orders where customer_id = o.customer_id)",
                "select x.id from (select id, total from orders) x where x.total > 1",
                "select x.cnt from (select count(*) as cnt from orders) as x",
                "select id from orders union select id from customers",
                "select id, total from orders union all select id, id from customers order by 1",
                "select * from orders limit 10",
                "select * from orders where created_at between '2020-01-01' and '2020-12-31'",
                "select case when total > 10 then 'big' else 'small' end as size from orders",
                "select coalesce(name, email) from customers",
                "select cast(total as decimal(10,2)) from orders",
                "select o.* from orders o",
                "select customers.* from customers",
                "select name from customers c join orders o on o.customer_id = c.id",
                "select o1.id from orders o1 join orders o2 on o1.customer_id = o2.customer_id and o1.id <> o2.id",
                "select * from orders order by 1, 2",
                "select * from public.orders",
                "select * from public.orders o where o.id = 1",
                "select audit.log.message from audit.log",
                "select l.message from audit.log l",
                "select * from order_summary",
                "select sku from items where qty is not null and sku like 'a%'",
                "select lower(name), upper(email) from customers where name is not null",
                "select * from orders where id = ? and status = :status",
                "select * from orders where customer_id = (select id from customers where email = ?)");
    }

    @Test void dataChangesStayQuiet() {
        for (var profile : ALL) quiet(profile,
                "insert into orders (customer_id, total) values (1, 2)",
                "insert into orders (customer_id, total) values (?, ?), (?, ?)",
                "insert into orders values (1, 2, 3, null, 'x')",
                "insert into items (order_id, sku) select id, 'x' from orders",
                "insert into items (id, order_id, sku, qty) select id, id, 'x', 1 from orders where status = 'open'",
                "update orders set total = 1 where id = 2",
                "update orders set total = total + 1, status = 'x' where customer_id = 3",
                "update orders o set o.total = 1 where o.id = 2",
                "delete from orders where id = 1",
                "delete from orders where customer_id in (select id from customers where email is null)",
                "delete from items",
                "truncate table orders");
    }

    @Test void commonConstructsOfEachDialectStayQuiet() {
        quiet(SqlDialects.MYSQL,
                "select `name`, `email` from `customers` where `id` = 1",
                "select * from `orders` o use index (primary) where o.id = 1",
                "select group_concat(sku separator ',') from items group by order_id",
                "select date_add(created_at, interval 1 day) from orders",
                "insert into customers (name, email) values ('a', 'b') on duplicate key update name = values(name)",
                "select sql_calc_found_rows * from orders limit 5",
                "update orders set total = 1 order by id limit 1",
                "select * from orders where match(status) against ('x' in boolean mode)",
                "select if(total > 1, 'a', 'b') from orders",
                "select * from shop.orders",
                "select @a := 1",
                "select ifnull(total, 0) from orders # comment");
        quiet(SqlDialects.POSTGRESQL,
                "select total::numeric, created_at::date from orders",
                "select * from orders where status = any(array['a','b'])",
                "select * from orders where created_at > now() - interval '1 day'",
                "select extract(year from created_at) as y, count(*) from orders group by y",
                "select date_trunc('month', created_at) from orders",
                "select customer_id, row_number() over (partition by customer_id order by total desc) from orders",
                "select sum(total) over (order by id rows between unbounded preceding and current row) from orders",
                "select distinct on (customer_id) customer_id, total from orders order by customer_id, total desc",
                "insert into customers (name, email) values ('a', 'b') on conflict (email) do update set name = excluded.name returning id",
                "update orders set total = 1 where id = 2 returning *",
                "select * from generate_series(1, 3) as g(n)",
                "select g.n from generate_series(1, 3) g(n)",
                "select * from orders o, lateral (select * from items i where i.order_id = o.id limit 1) li",
                "select * from orders for update",
                "select * from orders fetch first 5 rows only",
                "select name collate \"C\" from customers",
                "select * from pg_catalog.pg_tables",
                "select * from information_schema.tables where table_name = 'x'",
                "select * from pg_class",
                "select $1::int, $2",
                "select total at time zone 'utc' from orders",
                "select * from orders where created_at < current_timestamp",
                "with recent as (select * from orders where total > 1) select * from recent",
                "with a as (select id from orders), b as (select id from customers) select a.id from a join b on a.id = b.id",
                "with recursive t(n) as (select 1 union all select n + 1 from t where n < 5) select n from t",
                "select json_agg(name) from customers",
                "select string_agg(sku, ',') from items group by order_id",
                "select * from orders where (status, total) in (('a', 1))",
                "select * from customers tablesample system (10)",
                "do $$ begin perform 1; end $$");
        quiet(SqlDialects.H2,
                "select * from dual",
                "select * from system_range(1, 3)",
                "select x from system_range(1, 3) t(x)",
                "select dateadd('day', 1, created_at) from orders",
                "merge into customers (id, name) key (id) values (1, 'a')",
                "select * from orders limit 5 offset 2",
                "select * from information_schema.tables",
                "select current_date, current_timestamp from dual");
        quiet(SqlDialects.HSQLDB,
                "select * from information_schema.tables",
                "select * from orders limit 5",
                "select current_date from (values(0))",
                "select * from unnest(array[1,2]) as t(x)",
                "call now()",
                "set schema public");
    }

    @Test void lessCommonButValidShapesStayQuiet() {
        for (var profile : ALL) quiet(profile,
                "select o.id from orders as o",
                "select a.id, b.id from orders a, orders b where a.id < b.id",
                "select count(distinct customer_id) from orders",
                "select sum(case when status = 'a' then total else 0 end) from orders",
                "select * from orders where total > all (select total from orders where status = 'x')",
                "select (select max(total) from orders) as m",
                "select o.id, (select count(*) from items i where i.order_id = o.id) as n from orders o",
                "select * from (select * from orders) as t join customers c on c.id = t.customer_id",
                "select id from (select id from orders union select id from customers) u",
                "select * from orders where status in ('a','b') and not exists (select 1 from items where order_id = orders.id)",
                "select * from orders order by total desc nulls last",
                "select 'a' || name from customers",
                "select * from customers where id = any (select customer_id from orders)",
                "select concat(name, ' ', email) as full from customers order by full",
                "select sum(total) filter (where status = 'a') from orders",
                "select date '2020-01-01', time '10:00', timestamp '2020-01-01 10:00:00'",
                "select a.id from orders a cross join customers b",
                "select * from orders o where o.id = 1 union select * from orders o where o.id = 2",
                "select * from orders o where exists (select 1 from items o where o.qty > 1)",
                "select * from orders o inner join customers c on (c.id = o.customer_id)",
                "select (id) from orders",
                "select 1 as \"x y\"",
                "values (1, 2), (3, 4)",
                "select * from (values (1, 'a')) as v(id, name)",
                "select id as order_id from orders order by order_id",
                "select * from orders o where o.total > (select avg(o2.total) from orders o2)",
                "select name as \"Name\" from customers",
                "select * from orders where created_at > current_date - 7",
                "select * from orders where id = 1 limit 1 for update",
                "select * from orders limit 1; ; select * from customers;;",
                "explain select * from orders",
                "insert into orders (id) select id from customers",
                "insert into orders select * from orders where 1 = 0",
                "update orders set total = total * 1.1 where status = 'a' and customer_id in (select id from customers)",
                "delete from orders o where o.id = 1",
                "select * from orders where id = 1 -- trailing comment",
                "select total, count(*) over () from orders",
                "select o.id from public.orders o join public.customers c on c.id = o.customer_id",
                "select u.id from orders u where u.id not in (select order_id from items)",
                "select 2 * 3, -1, +1, (1 + 2) / 3 from orders",
                "select * from orders where status != 'a' and total >= 1 and total <= 9");
    }

    @Test void moreDialectConstructsStayQuiet() {
        quiet(SqlDialects.POSTGRESQL,
                "select * from only orders",
                "select name from customers where name ilike '%a%'",
                "select * from orders where total::text like '1%'",
                "select o.id from orders o order by o.id desc nulls first limit 5 offset 5",
                "select customer_id, array_agg(id order by id) from orders group by customer_id",
                "select * from jsonb_to_recordset('[]'::jsonb) as x(a int, b text)",
                "update orders o set total = s.total from order_summary s where s.customer_id = o.customer_id",
                "insert into items (order_id, sku) select o.id, 'x' from orders o returning id",
                "select data.* from (select 1 as a) data",
                "select * from orders o where o.created_at at time zone 'UTC' > now()",
                "select * from customers where name ~ '^a'",
                "select count(*) from orders group by rollup (customer_id, status)");
        quiet(SqlDialects.MYSQL,
                "select * from orders o straight_join customers c on c.id = o.customer_id",
                "select * from orders where status regexp '^a'",
                "select name, email from customers where name like binary 'a%'",
                "select o.id from orders o force index (primary) where o.id = 1",
                "select * from orders where created_at > date_sub(now(), interval 7 day)",
                "update orders o join customers c on c.id = o.customer_id set o.status = 'x' where c.name = 'a'",
                "select * from orders where id = @id",
                "replace into customers (id, name) values (1, 'a')",
                "select customer_id, group_concat(distinct status order by status desc separator '|') from orders group by customer_id");
        quiet(SqlDialects.H2,
                "select \"ID\", \"TOTAL\" from \"ORDERS\" where \"STATUS\" = 'a'",
                "select * from table(x int = (1, 2))",
                "select * from orders where id = 1 for update",
                "select top 5 * from orders",
                "select x from system_range(1, 5) where x > 2");
    }

    @Test void statementsOfEveryKindAreNotMistakenForTypos() {
        for (var profile : ALL) quiet(profile,
                "merge into customers c using orders o on (c.id = o.customer_id) when matched then update set name = 'x'",
                "call refresh_totals()",
                "show tables",
                "set schema public",
                "use shop",
                "describe orders",
                "analyze orders",
                "vacuum",
                "copy orders to stdout",
                "grant select on orders to reader",
                "revoke all on orders from reader",
                "explain analyze select * from orders",
                "lock table orders in exclusive mode",
                "start transaction",
                "savepoint s1",
                "release savepoint s1",
                "comment on table orders is 'x'",
                "refresh materialized view order_summary",
                "commit",
                "rollback to savepoint s1",
                "create index idx_o on orders (customer_id)",
                "create unique index idx_u on customers (email)",
                "alter table orders add constraint fk foreign key (customer_id) references customers (id)",
                "drop index idx_o",
                "truncate orders",
                "create sequence seq1",
                "\\connect shop");
    }

    @Test void joinsWithUsingAndNaturalHaveSharedColumns() {
        for (var profile : ALL) quiet(profile,
                "select customer_id, status from orders join order_summary using (customer_id)",
                "select customer_id from orders natural join order_summary",
                "select id from orders join customers on customers.id = orders.customer_id".replace("select id", "select orders.id"));
    }

    @Test void aliasesOfTheSelectListAreNotColumns() {
        for (var profile : ALL) quiet(profile,
                "select total * 2 as double_total from orders order by double_total",
                "select total * 2 double_total from orders group by double_total having double_total > 1",
                "select status s, count(*) c from orders group by s order by c");
    }

    @Test void scriptsWithSeveralStatementsCommentsAndStrings() {
        quiet(SqlDialects.POSTGRESQL,
                "-- orders report\nselect * from orders;\n/* second */\nselect id from customers; -- end\n",
                "select 'from nowhere where x = y' from orders",
                "select \"id\" from \"orders\" where \"status\" = 'a'");
        quiet(SqlDialects.MYSQL, "select 'unknown_column_name' from orders; select \"x\" from orders");
    }

    @Test void whatTheScriptCreatesIsKnown() {
        for (var profile : ALL) quiet(profile,
                "create table temp_t (a int, b int); insert into temp_t (a, b) values (1, 2); select a, b from temp_t where a = 1",
                "create table t2 as select * from orders; select * from t2",
                "create view v as select id from orders; select id from v",
                "alter table orders add column note text; select note from orders",
                "drop table if exists not_there",
                "create table if not exists fresh (id int)",
                "select * into backup_orders from orders; select * from backup_orders");
    }

    @Test void renamedTablesExistUnderTheirNewName() {
        for (var profile : ALL) quiet(profile,
                "alter table orders rename to archived_orders; select * from archived_orders",
                "rename table orders to archived_orders; select * from archived_orders");
    }

    @Test void aSchemaSwitchMeansUnqualifiedNamesAreNotJudged() {
        quiet(SqlDialects.MYSQL, "use otherdb; select * from things");
        quiet(SqlDialects.POSTGRESQL, "set search_path to other; select * from things");
    }

    @Test void templatesAndStoredRoutinesAreNotJudged() {
        quiet(SqlDialects.POSTGRESQL, "select * from ${schema}.orders; select {{ var }} from customers");
        quiet(SqlDialects.MYSQL, "DELIMITER //\ncreate procedure p() begin select nothing from nowhere; end //\nDELIMITER ;\nselect also_unknown from nowhere");
        quiet(SqlDialects.HSQLDB, "create procedure p() begin atomic select zzz from nowhere; end; select yyy from nowhere");
        quiet(SqlDialects.POSTGRESQL, "create function f() returns int as $$ select zzz from nowhere $$ language sql");
    }

    @Test void transactionControlIsNotAProceduralBlock() {
        quiet(SqlDialects.POSTGRESQL, "begin; select * from orders; commit");
        var found = check("begin transaction; select nope from orders; commit", SqlDialects.POSTGRESQL);
        assertEquals(1, found.size());
        assertEquals(Kind.UNKNOWN_COLUMN, found.get(0).kind());
    }

    @Test void nothingIsJudgedAgainstAnIncompleteSchema() {
        // "unread" is a known table whose columns were not read: its columns cannot be judged
        for (var profile : ALL) quiet(profile, "select anything from unread", "select u.anything from unread u", "select x.anything from other.x");
        var found = SqlScriptValidator.validate("select nope from orders", SqlDialects.H2, null);
        assertTrue(found.isEmpty(), "without a schema only the shape of statements is checked");
    }

    @Test void largeScriptsAreNotValidated() {
        String big = "select nope from orders;\n".repeat(SqlScriptValidator.MAX_LENGTH / 20);
        assertTrue(SqlScriptValidator.validate(big, SqlDialects.H2, ValidationFixtures.shop(DatabaseType.H2)).isEmpty());
    }

    // ---- seeded mistakes are found ---------------------------------------------------------------------------------------

    @Test void unknownTables() {
        for (var profile : ALL) {
            var d = only("select * from orderz", profile, Kind.UNKNOWN_TABLE, "orderz");
            assertEquals("orders", d.replacement(), "a one-letter slip suggests the real name");
            only("select * from orders o join custmers c on c.id = o.customer_id", profile, Kind.UNKNOWN_TABLE, "custmers");
            only("update orderz set total = 1", profile, Kind.UNKNOWN_TABLE, "orderz");
            only("delete from nothing_like_it where id = 1", profile, Kind.UNKNOWN_TABLE, "nothing_like_it");
            only("insert into itemz (id) values (1)", profile, Kind.UNKNOWN_TABLE, "itemz");
            only("select * from audit.logg", profile, Kind.UNKNOWN_TABLE, "logg");
        }
        assertNull(only("select * from totally_different", SqlDialects.H2, Kind.UNKNOWN_TABLE, "totally_different").replacement());
    }

    @Test void unknownColumns() {
        for (var profile : ALL) {
            var d = only("select totl from orders", profile, Kind.UNKNOWN_COLUMN, "totl");
            assertEquals("total", d.replacement());
            only("select o.totl from orders o", profile, Kind.UNKNOWN_COLUMN, "totl");
            only("select * from orders where statuz = 'x'", profile, Kind.UNKNOWN_COLUMN, "statuz");
            only("select c.name, o.nope from customers c join orders o on o.customer_id = c.id", profile, Kind.UNKNOWN_COLUMN, "nope");
            only("select * from orders o join customers c on c.id = o.customer_idd", profile, Kind.UNKNOWN_COLUMN, "customer_idd");
            only("select id from orders order by created", profile, Kind.UNKNOWN_COLUMN, "created");
            only("update orders set totl = 1 where id = 2", profile, Kind.UNKNOWN_COLUMN, "totl");
            only("update orders set total = 1 where idd = 2", profile, Kind.UNKNOWN_COLUMN, "idd");
            only("insert into orders (customer_id, totl) values (1, 2)", profile, Kind.UNKNOWN_COLUMN, "totl");
            only("delete from orders where statuz = 'a'", profile, Kind.UNKNOWN_COLUMN, "statuz");
            only("select customers.nick from customers", profile, Kind.UNKNOWN_COLUMN, "nick");
            only("select * from orders where id in (select order_id from items where quantity > 1)", profile, Kind.UNKNOWN_COLUMN, "quantity");
        }
    }

    @Test void ambiguousColumns() {
        for (var profile : ALL) {
            only("select id from orders o join customers c on c.id = o.customer_id", profile, Kind.AMBIGUOUS_COLUMN, "id");
            only("select * from orders o join items i on i.order_id = o.id where id = 1", profile, Kind.AMBIGUOUS_COLUMN, "id");
            only("select o1.id from orders o1 join orders o2 on o1.id = o2.id where total > 1", profile, Kind.AMBIGUOUS_COLUMN, "total");
        }
    }

    @Test void aliasMistakes() {
        for (var profile : ALL) {
            var d = only("select orders.id from orders o", profile, Kind.ALIAS, "orders");
            assertEquals("o", d.replacement());
            only("select z.id from orders o", profile, Kind.ALIAS, "z");
            only("select * from orders o join customers o on o.id = 1", profile, Kind.ALIAS, "o");
            only("select * from orders join orders on 1 = 1", profile, Kind.ALIAS, "orders");
            only("select o.id from orders o where p.id = 1", profile, Kind.ALIAS, "p");
        }
    }

    @Test void syntaxErrors() {
        for (var profile : ALL) {
            var d = only("selct * from orders", profile, Kind.SYNTAX, "selct");
            assertEquals("select", d.replacement());
            only("SELCT * FROM orders", profile, Kind.SYNTAX, "SELCT");
            only("select * from orders where (id = 1", profile, Kind.SYNTAX, "(");
            only("select * from orders where id = 1)", profile, Kind.SYNTAX, ")");
            only("select * from orders where status = 'open", profile, Kind.SYNTAX, "'open");
            only("select from orders", profile, Kind.SYNTAX, "select");
            only("select id, from orders", profile, Kind.SYNTAX, ",");
            only("select id,, total from orders", profile, Kind.SYNTAX, ",");
            only("select * from orders where;", profile, Kind.SYNTAX, "where");
            only("select * from orders where id = 1 and;", profile, Kind.SYNTAX, "and");
            only("select * from orders where id =;", profile, Kind.SYNTAX, "=");
            only("select * from orders where id = 1 or or status = 'a'", profile, Kind.SYNTAX, "or");
            only("select * from;", profile, Kind.SYNTAX, "from");
            only("select * from orders order by;", profile, Kind.SYNTAX, "by");
            only("select * from orders where id = 1 group by customer_id where total > 1", profile, Kind.SYNTAX, "where");
            only("select * from orders order by id where id = 1", profile, Kind.SYNTAX, "where");
            only("select * from orders where id = 1 where total = 2", profile, Kind.SYNTAX, "where");
            only("select id total status from orders", profile, Kind.SYNTAX, "status");
            only("insert into orders (id);", profile, Kind.SYNTAX, ")");
            only("update orders where id = 1;", profile, Kind.SYNTAX, "1");
        }
    }

    // ---- the statement being typed -----------------------------------------------------------------------------------------

    @Test void aTrailingStatementThatEndsWhereSomethingMustFollowIsStillBeingTyped() {
        for (var profile : ALL) quiet(profile,
                "select * from orders where",
                "select * from orders where id = 1 and",
                "select * from orders where id =",
                "select * from orders where id = 1 or ",
                "select * from",
                "select * from orders join",
                "select * from orders order by",
                "select * from orders group by",
                "select id,",
                "select",
                "select distinct",
                "insert into orders (id)",
                "update orders",
                "select 1; select * from orders where");
    }

    @Test void aTerminatedOrEarlierStatementWithTheSameShapeIsStillReported() {
        for (var profile : ALL) {
            only("select * from orders where;", profile, Kind.SYNTAX, "where");
            only("select * from orders where; select 1", profile, Kind.SYNTAX, "where");
            only("select id, ;", profile, Kind.SYNTAX, ",");
            only("select;", profile, Kind.SYNTAX, "select");
            only("insert into orders (id);", profile, Kind.SYNTAX, ")");
            only("update orders;", profile, Kind.SYNTAX, "orders");
        }
    }

    @Test void aTrailingStatementIsStillCheckedWhereTheGapIsNotAtItsEnd() {
        for (var profile : ALL) {
            only("select from orders", profile, Kind.SYNTAX, "select");
            only("select id, from orders", profile, Kind.SYNTAX, ",");
            only("select * from orders where group by id", profile, Kind.SYNTAX, "where");
            only("select * from orders where (id = 1", profile, Kind.SYNTAX, "(");
            only("select * from orders where status = 'open", profile, Kind.SYNTAX, "'open");
        }
    }

    @Test void aTrailingStatementStillGetsUnknownNameChecks() {
        for (var profile : ALL) {
            only("select * from orderz where", profile, Kind.UNKNOWN_TABLE, "orderz");
            only("select totl from orders where id =", profile, Kind.UNKNOWN_COLUMN, "totl");
        }
    }

    // ---- string prefixes ---------------------------------------------------------------------------------------------------

    @Test void everyStringPrefixTheLexerAcceptsIsTerminated() {
        for (var profile : ALL) quiet(profile,
                "SELECT b'1010'",
                "SELECT B'1010'",
                "select x'ff', X'ff', n'abc', N'abc'",
                "select b'1010';",
                "select e'a', E'a'");
        only("select b'1010", SqlDialects.POSTGRESQL, Kind.SYNTAX, "b'1010");
    }

    // ---- words that follow a table are clauses, not aliases ------------------------------------------------------------

    @Test void aClauseWordAfterATableIsNotItsAlias() {
        for (var profile : new SqlDialectProfile[] {SqlDialects.ANSI, SqlDialects.MYSQL, SqlDialects.POSTGRESQL}) quiet(profile,
                "select orders.id from orders limit 10",
                "select orders.id from orders offset 5",
                "select orders.id from orders order by orders.id limit 10 offset 5",
                "select orders.id from orders union select customers.id from customers",
                "select orders.id from orders except select customers.id from customers",
                "select orders.id from orders intersect select customers.id from customers",
                "select orders.id from orders fetch first 5 rows only",
                "select orders.id from orders for update",
                "select orders.id from orders where orders.id = 1 limit 1",
                "select o.id from orders o limit 10",
                "select orders.id from public.orders limit 10",
                "select orders.id from orders join items on items.order_id = orders.id limit 3");
        quiet(SqlDialects.ANSI,
                "select orders.id from orders qualify orders.id > 1",
                "select orders.id from orders minus select customers.id from customers",
                "select orders.id from orders lock in share mode");
        for (var profile : ALL) only("select orders.id from orders as o limit 10", profile, Kind.ALIAS, "orders");
    }

    @Test void aQuotedWordAfterATableIsStillItsAlias() {
        for (var profile : new SqlDialectProfile[] {SqlDialects.ANSI, SqlDialects.POSTGRESQL}) quiet(profile,
                "select \"limit\".id from orders \"limit\"");
    }

    // ---- columns the engine adds ------------------------------------------------------------------------------------------

    @Test void systemColumnsAreNotUnknownColumns() {
        quiet(SqlDialects.POSTGRESQL,
                "select ctid, xmin, xmax, cmin, cmax, tableoid, oid from orders",
                "select o.ctid, o.xmin, o.tableoid from orders o",
                "select orders.ctid from orders",
                "select CTID from orders where ctid = '(0,1)'",
                "select id from orders order by ctid",
                "select * from orders o join items i on i.ctid = o.ctid",
                "delete from orders where ctid in (select ctid from orders limit 1)");
        quiet(SqlDialects.H2, "select _rowid_ from orders", "select o._ROWID_ from orders o", "select * from orders where _ROWID_ = 1");
        quiet(SqlDialects.MYSQL, "select _rowid from orders", "select o._rowid from orders o", "select * from orders order by _rowid");
        quiet(SqlDialects.ANSI, "select ctid, _rowid, _rowid_ from orders");
    }

    @Test void anEnginesSystemColumnsAreOnlyThatEnginesOwn() {
        only("select _rowid from orders", SqlDialects.POSTGRESQL, Kind.UNKNOWN_COLUMN, "_rowid");
        only("select ctid from orders", SqlDialects.MYSQL, Kind.UNKNOWN_COLUMN, "ctid");
        only("select o.ctid from orders o", SqlDialects.H2, Kind.UNKNOWN_COLUMN, "ctid");
    }

    @Test void parenthesislessNiladicFunctionsAreNotColumns() {
        for (var profile : ALL) quiet(profile,
                "select sysdate from orders",
                "select systimestamp from orders",
                "select current_date, current_time, current_timestamp, localtime, localtimestamp from orders",
                "select current_user, session_user, user from orders",
                "select * from orders where created_at < sysdate and status <> user");
    }

    @Test
    void sqlInAStringIsCompleteSoAMissingFinalPieceIsReported() {
        String sql = "select * from orders where";
        assertTrue(SqlScriptValidator.validate(sql, SqlDialects.H2, ValidationFixtures.shop(DatabaseType.H2)).isEmpty());
        assertEquals(1, SqlScriptValidator.validate(sql, SqlDialects.H2, ValidationFixtures.shop(DatabaseType.H2), false).size());
    }
}
