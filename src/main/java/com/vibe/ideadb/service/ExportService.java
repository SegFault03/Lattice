package com.vibe.ideadb.service;

import com.vibe.ideadb.model.QueryResult;

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
                    bw.write(escapeCsv(val != null ? val.toString() : ""));
                    if (i < row.size() - 1) bw.write(",");
                }
                bw.newLine();
            }
        }
    }

    public void exportToJson(QueryResult result, File file) throws Exception {
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
                    } else if (val instanceof Number || val instanceof Boolean) {
                        bw.write(val.toString());
                    } else {
                        bw.write("\"" + escapeJson(val.toString()) + "\"");
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
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            List<String> cols = result.getColumnNames();
            List<List<Object>> rows = result.getRows();

            for (List<Object> row : rows) {
                bw.write("INSERT INTO " + tableName + " (");
                for (int i = 0; i < cols.size(); i++) {
                    bw.write(cols.get(i));
                    if (i < cols.size() - 1) bw.write(", ");
                }
                bw.write(") VALUES (");
                for (int i = 0; i < row.size(); i++) {
                    Object val = row.get(i);
                    if (val == null) {
                        bw.write("NULL");
                    } else if (val instanceof Number) {
                        bw.write(val.toString());
                    } else {
                        bw.write("'" + escapeSql(val.toString()) + "'");
                    }
                    if (i < row.size() - 1) bw.write(", ");
                }
                bw.write(");\n");
            }
        }
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

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String escapeSql(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }
}
