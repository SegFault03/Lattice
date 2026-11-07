package com.segfault03.ideadb.state;

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
        public int inspectorRow = -1;
        public int inspectorColumn = -1;
        public String inspectorText;
        public String inspectorKind = "TEXT";
        Draft copy() {
            Draft copy = new Draft(); copy.columns.addAll(columns); copy.types.addAll(types);
            for (Row row : originals) copy.originals.add(row.copy());
            for (Row row : rows) copy.rows.add(row.copy());
            copy.inspectorRow = inspectorRow; copy.inspectorColumn = inspectorColumn;
            copy.inspectorText = inspectorText; copy.inspectorKind = inspectorKind;
            return copy;
        }
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
        Row copy() { Row copy = new Row(); for (Cell cell : cells) copy.cells.add(cell.copy()); return copy; }
        static Row capture(List<Object> values) {
            Row row = new Row(); for (Object value : values) row.cells.add(Cell.capture(value)); return row;
        }
        List<Object> values() { return cells.stream().map(Cell::value).toList(); }
    }
    public static final class Cell {
        public String kind = "null";
        public String text = "";
        Cell copy() { Cell copy = new Cell(); copy.kind = kind; copy.text = text; return copy; }
        static Cell capture(Object value) {
            Cell cell = new Cell();
            if (value == null) return cell;
            if (value instanceof byte[] bytes) { cell.kind = "bytes"; cell.text = Base64.getEncoder().encodeToString(bytes); return cell; }
            if (value instanceof com.segfault03.ideadb.model.TruncatedValue cut) {
                // Kept as a TruncatedValue on restore so a draft can never turn the preview into an editable real value.
                cell.kind = "truncated"; cell.text = cut.fullLength() + ":" + (cut.binary() ? "b:" + Base64.getEncoder().encodeToString((byte[]) cut.preview()) : "s:" + cut.preview());
                return cell;
            }
            cell.kind = value.getClass().getName(); cell.text = value instanceof Enum<?> enumeration ? enumeration.name() : value.toString();
            return cell;
        }
        Object value() {
            return switch (kind) {
                case "null" -> null;
                case "com.segfault03.ideadb.model.RowDefaults$Value" -> com.segfault03.ideadb.model.RowDefaults.Value.USE_DEFAULT;
                case "truncated" -> {
                    String[] parts = text.split(":", 3);
                    yield new com.segfault03.ideadb.model.TruncatedValue(parts[1].equals("b") ? Base64.getDecoder().decode(parts[2]) : parts[2], Long.parseLong(parts[0]));
                }
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
    /** A snapshot: the live drafts keep changing on the EDT while the IDE serializes state elsewhere. */
    @Override public synchronized State getState() {
        State snapshot = new State();
        state.drafts.forEach((key, draft) -> snapshot.drafts.put(key, draft.copy()));
        return snapshot;
    }
    @Override public synchronized void loadState(State state) { this.state = state; }
    public synchronized Draft get(String key) { return state.drafts.get(key); }
    public synchronized void put(String key, Draft draft) { state.drafts.put(key, draft); }
    public synchronized boolean move(String source,String target) {
        if(source.equals(target)) return true;
        if(state.drafts.containsKey(target)) return false;
        Draft draft=state.drafts.remove(source); if(draft!=null) state.drafts.put(target,draft); return true;
    }
    public synchronized void remove(String key) { state.drafts.remove(key); }
    /** Draft keys are {@code table:<connection id>:<database>:<table>}. */
    public synchronized void removeConnection(String connectionId) { state.drafts.keySet().removeIf(key -> key.startsWith("table:" + connectionId + ":")); }
}
