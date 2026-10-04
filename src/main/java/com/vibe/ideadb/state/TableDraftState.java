package com.vibe.ideadb.state;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import java.util.*;

/** Pending table edits belong to the local project workspace, never the shared connection settings. */
@State(name = "LatticeTableDrafts", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class TableDraftState implements PersistentStateComponent<TableDraftState.State> {
    public static final class State { public Map<String, Draft> drafts = new LinkedHashMap<>(); }
    public static final class Draft {
        public List<String> columns = new ArrayList<>();
        public List<String> types = new ArrayList<>();
        public List<Row> originals = new ArrayList<>();
        public List<Row> rows = new ArrayList<>();
        public static Draft capture(List<String> columns, List<String> types, List<List<Object>> originals, List<List<Object>> rows) {
            Draft draft = new Draft();
            draft.columns.addAll(columns); draft.types.addAll(types);
            for (List<Object> row : originals) draft.originals.add(Row.capture(row));
            for (List<Object> row : rows) draft.rows.add(Row.capture(row));
            return draft;
        }
        public Draft withoutRows(List<Integer> selected) {
            Draft copy = new Draft(); copy.columns.addAll(columns); copy.types.addAll(types);
            for (int i = 0; i < originals.size(); i++) if (!selected.contains(i)) copy.originals.add(originals.get(i));
            for (int i = 0; i < rows.size(); i++) if (!selected.contains(i)) copy.rows.add(rows.get(i));
            return copy;
        }
        public boolean hasChanges() {
            if (originals.size() != rows.size()) return true;
            List<List<Object>> before = originalValues(), after = values();
            for (int row = 0; row < before.size(); row++) {
                for (int col = 0; col < columns.size(); col++) if (!Objects.deepEquals(before.get(row).get(col), after.get(row).get(col))) return true;
            }
            return false;
        }
        public List<List<Object>> originalValues() { return originals.stream().map(Row::values).toList(); }
        public List<List<Object>> values() { return rows.stream().map(Row::values).toList(); }
    }
    public static final class Row {
        public List<Cell> cells = new ArrayList<>();
        static Row capture(List<Object> values) {
            Row row = new Row(); for (Object value : values) row.cells.add(Cell.capture(value)); return row;
        }
        List<Object> values() { return cells.stream().map(Cell::value).toList(); }
    }
    public static final class Cell {
        public String kind = "null";
        public String text = "";
        static Cell capture(Object value) {
            Cell cell = new Cell();
            if (value == null) return cell;
            if (value instanceof byte[] bytes) { cell.kind = "bytes"; cell.text = Base64.getEncoder().encodeToString(bytes); return cell; }
            cell.kind = value.getClass().getName(); cell.text = value.toString();
            return cell;
        }
        Object value() {
            return switch (kind) {
                case "null" -> null;
                case "bytes" -> Base64.getDecoder().decode(text);
                case "java.lang.Byte" -> Byte.valueOf(text);
                case "java.lang.Short" -> Short.valueOf(text);
                case "java.lang.Integer" -> Integer.valueOf(text);
                case "java.lang.Long" -> Long.valueOf(text);
                case "java.lang.Float" -> Float.valueOf(text);
                case "java.lang.Double" -> Double.valueOf(text);
                case "java.lang.Boolean" -> Boolean.valueOf(text);
                case "java.math.BigDecimal" -> new java.math.BigDecimal(text);
                case "java.math.BigInteger" -> new java.math.BigInteger(text);
                case "java.sql.Date" -> java.sql.Date.valueOf(text);
                case "java.sql.Time" -> java.sql.Time.valueOf(text);
                case "java.sql.Timestamp" -> java.sql.Timestamp.valueOf(text);
                case "java.time.LocalDate" -> java.time.LocalDate.parse(text);
                case "java.time.LocalTime" -> java.time.LocalTime.parse(text);
                case "java.time.LocalDateTime" -> java.time.LocalDateTime.parse(text);
                case "java.time.OffsetDateTime" -> java.time.OffsetDateTime.parse(text);
                case "java.time.OffsetTime" -> java.time.OffsetTime.parse(text);
                case "java.util.UUID" -> UUID.fromString(text);
                default -> text;
            };
        }
    }
    private State state = new State();
    public static TableDraftState getInstance(Project project) { return project.getService(TableDraftState.class); }
    @Override public synchronized State getState() { return state; }
    @Override public synchronized void loadState(State state) { this.state = state; }
    public synchronized Draft get(String key) { return state.drafts.get(key); }
    public synchronized void put(String key, Draft draft) { state.drafts.put(key, draft); }
    public synchronized void remove(String key) { state.drafts.remove(key); }
}
