package com.segfault03.ideadb.provider;

import java.sql.*;
import java.util.*;

/** Uses the server's format_type and pg_get_* functions; no pg_dump installation is required. */
final class PostgresCreateStatement {
    private PostgresCreateStatement() {}
    private record Sequence(String name, String options, long value, boolean called) {}
    static String read(Connection c, String schema, String table, SqlDialect dialect) throws SQLException {
        String target = dialect.formatTable(new DatabaseNamespace(null,schema),table);
        int major = c.getMetaData().getDatabaseMajorVersion(), minor = c.getMetaData().getDatabaseMinorVersion();
        boolean persistence = major > 9 || major == 9 && minor >= 1;
        long oid; String kind; String storage; String tablespace;
        try (var s = c.prepareStatement("SELECT c.oid,c.relkind," + (persistence ? "c.relpersistence" : "'p'") + ",array_to_string(c.reloptions,','),t.spcname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_catalog.pg_tablespace t ON t.oid=c.reltablespace WHERE n.nspname=? AND c.relname=?")) {
            s.setString(1,schema); s.setString(2,table);
            try (var r=s.executeQuery()) { if (!r.next()) throw new SQLException("Table no longer exists"); oid=r.getLong(1); kind=r.getString(2); storage=r.getString(4); tablespace=r.getString(5);
                if (r.getString(3).equals("t")) throw new SQLException("Export temporary tables from the owning SQL session"); }
        }
        var output = new StringBuilder("-- Catalog reconstruction; referenced tables, types, collations and trigger functions must already exist.\nSET search_path TO " + dialect.quoteIdentifier(schema) + ", pg_catalog;\n");
        if (kind.equals("v") || kind.equals("m")) {
            try (var s=c.prepareStatement("SELECT pg_catalog.pg_get_viewdef(?::oid,true)")) { s.setLong(1,oid); try(var r=s.executeQuery()) { r.next(); output.append("CREATE ").append(kind.equals("m") ? "MATERIALIZED " : "").append("VIEW ").append(target).append(" AS ").append(r.getString(1)); } }
            return output.toString();
        }
        String identity = major >= 10 ? "a.attidentity" : "''", generated = major >= 12 ? "a.attgenerated" : "''";
        String collate = persistence ? "CASE WHEN a.attcollation<>t.typcollation THEN quote_ident(cn.nspname)||'.'||quote_ident(co.collname) END" : "NULL";
        String joins = persistence ? " LEFT JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid=co.collnamespace" : "";
        var definitions = new ArrayList<String>(); var serials = new ArrayList<Sequence>(); var identities = new LinkedHashMap<String,Sequence>(); var comments = new ArrayList<String>();
        try (var s=c.prepareStatement("SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,pg_catalog.pg_get_expr(d.adbin,d.adrelid)," + identity + "," + generated + ",pg_catalog.pg_get_serial_sequence(?,a.attname),pg_catalog.col_description(a.attrelid,a.attnum)," + collate + " FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum" + joins + " WHERE a.attrelid=? AND a.attnum>0 AND NOT a.attisdropped AND a.attislocal ORDER BY a.attnum")) {
            s.setString(1,target); s.setLong(2,oid);
            try (var rows=s.executeQuery()) {
                while (rows.next()) {
                    String name=rows.getString(1), id=rows.getString(5), gen=rows.getString(6), seq=rows.getString(7), value=rows.getString(4);
                    var column=new StringBuilder(dialect.quoteIdentifier(name)).append(' ').append(rows.getString(2));
                    if (rows.getString(9)!=null) column.append(" COLLATE ").append(rows.getString(9));
                    if (!gen.isEmpty()) column.append(" GENERATED ALWAYS AS (").append(value).append(") ").append(gen.equals("v") ? "VIRTUAL" : "STORED");
                    else if (!id.isEmpty()) {
                        Sequence sequence=sequence(c,seq,major); identities.put(name,sequence);
                        column.append(" GENERATED ").append(id.equals("a") ? "ALWAYS" : "BY DEFAULT").append(" AS IDENTITY (SEQUENCE NAME ").append(seq).append(' ').append(sequence.options()).append(')');
                    } else {
                        if (seq!=null) { serials.add(sequence(c,seq,major)); value="nextval(" + literal(seq) + "::regclass)"; }
                        if (value!=null) column.append(" DEFAULT ").append(value);
                    }
                    if (rows.getBoolean(3)) column.append(" NOT NULL");
                    definitions.add(column.toString());
                    if (rows.getString(8)!=null) comments.add("COMMENT ON COLUMN " + target + "." + dialect.quoteIdentifier(name) + " IS " + literal(rows.getString(8)) + ";");
                }
            }
        }
        try (var s=c.prepareStatement("SELECT conname,pg_catalog.pg_get_constraintdef(oid,true) FROM pg_catalog.pg_constraint WHERE conrelid=? AND conislocal AND contype<>'n' ORDER BY conname")) {
            s.setLong(1,oid); try (var rows=s.executeQuery()) { while(rows.next()) definitions.add("CONSTRAINT " + dialect.quoteIdentifier(rows.getString(1)) + " " + rows.getString(2)); }
        }
        for (Sequence seq:serials) output.append("CREATE SEQUENCE ").append(seq.name()).append(' ').append(seq.options()).append(";\n");
        String parents = parents(c,oid,dialect);
        boolean partition = false;
        if (major>=10) try (var s=c.prepareStatement("SELECT relispartition,pg_catalog.pg_get_expr(relpartbound,oid) FROM pg_catalog.pg_class WHERE oid=?")) {
            s.setLong(1,oid); try(var rows=s.executeQuery()) { rows.next(); partition=rows.getBoolean(1);
                if (partition) output.append("CREATE TABLE ").append(target).append(" PARTITION OF ").append(parents).append(' ').append(rows.getString(2)).append(";\n"); }
        }
        if (!partition) {
            boolean unlogged=false;
            if (persistence) try(var s=c.prepareStatement("SELECT relpersistence='u' FROM pg_catalog.pg_class WHERE oid=?")) { s.setLong(1,oid); try(var rows=s.executeQuery()) { rows.next(); unlogged=rows.getBoolean(1); } }
            output.append("CREATE ").append(unlogged ? "UNLOGGED " : "").append("TABLE ").append(target).append(" (\n  ").append(String.join(",\n  ",definitions)).append("\n)");
            if (!parents.isEmpty()) output.append(" INHERITS (").append(parents).append(')');
            if (kind.equals("p")) try(var s=c.prepareStatement("SELECT pg_catalog.pg_get_partkeydef(?::oid)")) { s.setLong(1,oid); try(var rows=s.executeQuery()) { rows.next(); output.append(" PARTITION BY ").append(rows.getString(1)); } }
            if (storage!=null && !storage.isBlank()) output.append(" WITH (").append(storage).append(')');
            if (tablespace!=null) output.append(" TABLESPACE ").append(dialect.quoteIdentifier(tablespace));
            output.append(";\n");
        }
        for (Sequence seq:serials) {
            try(var s=c.prepareStatement("SELECT a.attname FROM pg_catalog.pg_attribute a WHERE a.attrelid=? AND pg_catalog.pg_get_serial_sequence(?,a.attname)=?")) {
                s.setLong(1,oid); s.setString(2,target); s.setString(3,seq.name());
                try(var rows=s.executeQuery()) { rows.next(); output.append("ALTER SEQUENCE ").append(seq.name()).append(" OWNED BY ").append(target).append('.').append(dialect.quoteIdentifier(rows.getString(1))).append(";\n"); }
            }
        }
        for (Sequence seq:concat(serials,identities.values())) output.append("SELECT pg_catalog.setval(").append(literal(seq.name())).append(',').append(seq.value()).append(',').append(seq.called()).append(");\n");
        try (var s=c.prepareStatement("SELECT pg_catalog.pg_get_indexdef(i.indexrelid) FROM pg_catalog.pg_index i WHERE i.indrelid=? AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.conindid=i.indexrelid) AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits h WHERE h.inhrelid=i.indexrelid) ORDER BY i.indexrelid")) {
            s.setLong(1,oid); try(var rows=s.executeQuery()) { while(rows.next()) output.append(rows.getString(1)).append(";\n"); }
        }
        try (var s=c.prepareStatement("SELECT pg_catalog.pg_get_triggerdef(oid) FROM pg_catalog.pg_trigger WHERE tgrelid=? AND NOT " + (major>=9 ? "tgisinternal" : "tgisconstraint") + " ORDER BY tgname")) {
            s.setLong(1,oid); try(var rows=s.executeQuery()) { while(rows.next()) output.append(rows.getString(1)).append(";\n"); }
        }
        try (var s=c.prepareStatement("SELECT pg_catalog.obj_description(?::oid,'pg_class')")) { s.setLong(1,oid); try(var rows=s.executeQuery()) { rows.next(); if(rows.getString(1)!=null) comments.add("COMMENT ON TABLE " + target + " IS " + literal(rows.getString(1)) + ";"); } }
        output.append(String.join("\n",comments)); return output.toString();
    }
    private static Collection<Sequence> concat(Collection<Sequence> first, Collection<Sequence> second) { var result=new ArrayList<>(first); result.addAll(second); return result; }
    private static String parents(Connection c,long oid,SqlDialect dialect) throws SQLException {
        var parents=new ArrayList<String>();
        try(var s=c.prepareStatement("SELECT n.nspname,p.relname FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class p ON p.oid=i.inhparent JOIN pg_catalog.pg_namespace n ON n.oid=p.relnamespace WHERE i.inhrelid=? ORDER BY i.inhseqno")) {
            s.setLong(1,oid); try(var rows=s.executeQuery()) { while(rows.next()) parents.add(dialect.formatTable(new DatabaseNamespace(null,rows.getString(1)),rows.getString(2))); }
        }
        return String.join(",",parents);
    }
    private static Sequence sequence(Connection c,String name,int major) throws SQLException {
        long increment,min,max,start,cache,value; boolean cycle,called;
        try(var s=c.createStatement(); var rows=s.executeQuery("SELECT last_value,is_called" + (major<10 ? ",increment_by,min_value,max_value,start_value,cache_value,is_cycled" : "") + " FROM " + name)) {
            rows.next(); value=rows.getLong(1); called=rows.getBoolean(2);
            if(major<10) return new Sequence(name,options(rows.getLong(3),rows.getLong(4),rows.getLong(5),rows.getLong(6),rows.getLong(7),rows.getBoolean(8)),value,called);
        }
        try(var s=c.prepareStatement("SELECT seqincrement,seqmin,seqmax,seqstart,seqcache,seqcycle FROM pg_catalog.pg_sequence WHERE seqrelid=?::regclass")) {
            s.setString(1,name); try(var rows=s.executeQuery()) { rows.next(); increment=rows.getLong(1); min=rows.getLong(2); max=rows.getLong(3); start=rows.getLong(4); cache=rows.getLong(5); cycle=rows.getBoolean(6); }
        }
        return new Sequence(name,options(increment,min,max,start,cache,cycle),value,called);
    }
    private static String options(long inc,long min,long max,long start,long cache,boolean cycle) { return "INCREMENT BY " + inc + " MINVALUE " + min + " MAXVALUE " + max + " START WITH " + start + " CACHE " + cache + (cycle ? " CYCLE" : " NO CYCLE"); }
    private static String literal(String value) { return "E'" + value.replace("\\","\\\\").replace("'","''").replace("\n","\\n").replace("\r","\\r") + "'"; }
}
