package com.vibe.ideadb.service;

import com.vibe.ideadb.model.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ExportService {
    private static final ExportService INSTANCE = new ExportService();

    private ExportService() {
    }

    public static ExportService getInstance() {
        return INSTANCE;
    }

    public void exportToCsv(QueryResult result, File file) throws Exception {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            List<String> cols = result.getColumnNames();
            for (int i = 0; i < cols.size(); i++) {
                bw.write(escapeCsv(cols.get(i)));
                if (i < cols.size() - 1) bw.write(",");
            }
            bw.newLine();

            for (List<Object> row : result.getRows()) {
                for (int i = 0; i < row.size(); i++) {
                    Object val = row.get(i);
                    bw.write(escapeCsv(val != null ? textValue(val) : ""));
                    if (i < row.size() - 1) bw.write(",");
                }
                bw.newLine();
            }
        }
    }

    public void exportToJson(QueryResult result, File file) throws Exception {
        if (new java.util.HashSet<>(result.getColumnNames()).size()!=result.getColumnNames().size()) throw new IllegalArgumentException("JSON objects require unique column labels. Alias duplicate columns before exporting.");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            bw.write("[\n");
            List<String> cols = result.getColumnNames();
            List<List<Object>> rows = result.getRows();

            for (int r = 0; r < rows.size(); r++) {
                List<Object> row = rows.get(r);
                bw.write("  {\n");
                for (int c = 0; c < cols.size(); c++) {
                    bw.write("    \"" + escapeJson(cols.get(c)) + "\": ");
                    Object val = row.get(c);
                    if (val == null) {
                        bw.write("null");
                    } else if ((val instanceof Number && finite(val)) || val instanceof Boolean) {
                        bw.write(val.toString());
                    } else {
                        bw.write("\"" + escapeJson(textValue(val)) + "\"");
                    }
                    if (c < cols.size() - 1) bw.write(",");
                    bw.newLine();
                }
                bw.write("  }");
                if (r < rows.size() - 1) bw.write(",");
                bw.newLine();
            }
            bw.write("]\n");
        }
    }

    public void exportToSqlInsert(String tableName, QueryResult result, File file) throws Exception {
        exportToSqlInsert(new ConnectionConfig(DatabaseType.HSQLDB,"export"),null,tableName,result,file);
    }
    public void exportToSqlInsert(ConnectionConfig config,String schema,String tableName,QueryResult result,File file) throws Exception {
        try(BufferedWriter writer=new BufferedWriter(new FileWriter(file,StandardCharsets.UTF_8))) {
            for(List<Object> row:result.getRows()) writeInsert(writer,config,schema,tableName,result.getColumnNames(),result.getColumnTypes(),row);
        }
    }
    private void writeInsert(BufferedWriter writer, ConnectionConfig config,String schema,String table,List<String> columns,List<String> types,List<Object> row) throws Exception {
        writer.write("INSERT INTO " + DdlService.formatTable(config,schema,table) + " (");
        for(int col=0;col<columns.size();col++) {
            if(col>0) writer.write(", "); writer.write(DdlService.quoteIdentifier(config,columns.get(col)));
        }
        writer.write(") VALUES (");
        for(int col=0;col<row.size();col++) { if(col>0) writer.write(", "); writer.write(sqlLiteral(config,row.get(col),types.get(col))); }
        writer.write(");\n");
    }
    private static boolean finite(Object value) {
        return !(value instanceof Double number && !Double.isFinite(number)) && !(value instanceof Float single && !Float.isFinite(single));
    }
    private static String textValue(Object value) {
        return value instanceof byte[] bytes ? "base64:" + java.util.Base64.getEncoder().encodeToString(bytes) : value.toString();
    }
    private static String sqlLiteral(ConnectionConfig config,Object value,String type) {
        if(value==null) return "NULL";
        if(value instanceof byte[] bytes) return "X'" + java.util.HexFormat.of().formatHex(bytes) + "'";
        if(value instanceof Boolean bool) return bool ? "TRUE" : "FALSE";
        if(value instanceof Number) { if(!finite(value)) throw new IllegalArgumentException("Non-finite numbers cannot be exported as SQL literals"); return value.toString(); }
        if(config.getType()==DatabaseType.MYSQL && value instanceof java.sql.Timestamp timestamp && type.toUpperCase(java.util.Locale.ROOT).contains("TIMESTAMP")) {
            java.time.Instant instant=timestamp.toInstant();
            java.math.BigDecimal seconds=java.math.BigDecimal.valueOf(instant.getEpochSecond()).add(java.math.BigDecimal.valueOf(instant.getNano(),9));
            return "FROM_UNIXTIME(" + seconds.toPlainString() + ")";
        }
        String text=value.toString();
        if(config.getType()==DatabaseType.MYSQL) return "CONVERT(X'" + java.util.HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8)) + "' USING utf8mb4)";
        return "'" + text.replace("'","''") + "'";
    }

    public void exportCreateTable(String ddl, File file) throws Exception {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            bw.write(ddl);
            if (!ddl.endsWith("\n")) {
                bw.newLine();
            }
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String escapeJson(String value) {
        if(value==null) return "";
        StringBuilder escaped=new StringBuilder();
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(ch=='\\') escaped.append("\\\\");
            else if(ch=='\"') escaped.append("\\\"");
            else if(ch<0x20 || Character.isSurrogate(ch)) escaped.append(String.format("\\u%04x",(int)ch));
            else escaped.append(ch);
        }
        return escaped.toString();
    }
}
