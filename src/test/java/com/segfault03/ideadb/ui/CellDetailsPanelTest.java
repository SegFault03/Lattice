package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.RowDefaults;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellDetailsPanelTest {
    static final class RecordingActions implements CellDetailsPanel.Actions {
        final List<String> applied = new ArrayList<>();
        int closes;
        public void apply(CellDetailsPanel.Cell cell, String text) { applied.add(text); }
        public void setNull(CellDetailsPanel.Cell cell) {}
        public void useDefault(CellDetailsPanel.Cell cell) {}
        public void revert(CellDetailsPanel.Cell cell) {}
        public void editTable() {}
        public void close() { closes++; }
        public void unpin() {}
    }
    static CellDetailsPanel.Cell cell(Object value, int row) {
        return new CellDetailsPanel.Cell("customers", row, 0,
                new ColumnMetadata("email", "VARCHAR", Types.VARCHAR, 180, 0, true, false, false, "'active'"),
                value, false, false, true, false, null, "id = " + (1001 + row), "");
    }
    static <T> T field(Object target, String name, Class<T> type) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(target)); }
        catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    static void click(CellDetailsPanel panel, String name) { field(panel, name, JButton.class).doClick(0); }

    @Test void untouchedNullDefaultAndOrdinaryValuesNeverBecomePendingChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Object value : new Object[]{null, RowDefaults.Value.USE_DEFAULT, "existing", ""}) {
                RecordingActions actions = new RecordingActions();
                CellDetailsPanel panel = new CellDetailsPanel(actions); panel.showCell(cell(value, 0));
                click(panel, "edit");
                assertFalse(field(panel, "apply", JButton.class).isEnabled());
                assertTrue(field(panel, "summary", JLabel.class).getText().endsWith("Unchanged"));
                click(panel, "apply");
                assertTrue(actions.applied.isEmpty());
                assertTrue(panel.finishEditing());
                assertFalse(panel.isEditing());
            }
        });
    }

    @Test void navigationKeepsDraftOnItsSourceAndOnlyExplicitStagingAppliesIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecordingActions actions = new RecordingActions(); CellDetailsPanel panel = new CellDetailsPanel(actions);
            panel.showCell(cell("before", 0)); click(panel, "edit");
            field(panel, "valueArea", JTextArea.class).setText("changed");
            assertFalse(panel.showCell(cell("another", 1)));
            assertEquals(0, panel.inspectedRow());
            assertEquals("changed", panel.draftText());
            assertFalse(panel.finishEditing(), "Commit/reload cannot implicitly stage this draft");
            assertTrue(actions.applied.isEmpty());
            click(panel, "apply");
            assertEquals(List.of("changed"), actions.applied);
            assertFalse(panel.isEditing());
        });
    }

    @Test void closeAndEscapeCancelDraftsWithoutApplyingThem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean escape : new boolean[]{false, true}) {
                RecordingActions actions = new RecordingActions(); CellDetailsPanel panel = new CellDetailsPanel(actions);
                panel.showCell(cell(null, 0)); click(panel, "edit");
                field(panel, "valueArea", JTextArea.class).setText("discard me");
                if (escape) panel.handleEscape(); else click(panel, "close");
                assertTrue(actions.applied.isEmpty()); assertEquals(1, actions.closes);
                assertFalse(panel.isEditing());
            }
        });
    }

    @Test void recoveredDraftStaysUnstagedAndCountTracksTheDocument() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecordingActions actions = new RecordingActions(); CellDetailsPanel panel = new CellDetailsPanel(actions);
            panel.restoreDraft(cell("before", 0), "A😀");
            assertEquals("2 characters", field(panel, "summary", JLabel.class).getText());
            assertTrue(actions.applied.isEmpty()); assertTrue(panel.hasDraftChange());
            click(panel, "cancel");
            assertEquals("before", field(panel, "valueArea", JTextArea.class).getText());
            assertEquals("6 characters", field(panel, "summary", JLabel.class).getText());
        });
    }

    @Test void scalarControlsStayQuietAndStructuredModesExposeToggleSemantics() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
            panel.showCell(cell("amelia@example.com", 0));
            assertFalse(field(panel, "formattedTab", JToggleButton.class).isVisible());
            assertFalse(field(panel, "wrap", JToggleButton.class).isVisible());
            panel.showCell(cell("{\"x\":1}", 0));
            JToggleButton formatted = field(panel, "formattedTab", JToggleButton.class);
            JToggleButton raw = field(panel, "rawTab", JToggleButton.class);
            assertTrue(formatted.isVisible()); assertTrue(formatted.isSelected());
            assertTrue(formatted.isFocusPainted()); assertTrue(raw.isFocusPainted());
            raw.doClick(0); assertTrue(raw.isSelected()); assertFalse(formatted.isSelected());
            assertEquals("{\"x\":1}", field(panel, "valueArea", JTextArea.class).getText());
        });
    }

    @Test void emptyStringRequiresAnExplicitValueStateAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecordingActions actions = new RecordingActions(); CellDetailsPanel panel = new CellDetailsPanel(actions);
            panel.showCell(cell(null, 0));
            JPopupMenu menu = panel.createValueMenu();
            for (java.awt.Component component : menu.getComponents())
                if (component instanceof JMenuItem item && "Set empty string".equals(item.getText())) item.doClick(0);
            assertEquals(List.of(""), actions.applied);
        });
    }
}
