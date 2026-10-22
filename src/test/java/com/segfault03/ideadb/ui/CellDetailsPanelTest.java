package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.RowDefaults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellDetailsPanelTest {
    @BeforeAll static void enableSvgIcons() {
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        com.intellij.openapi.util.IconLoader.activate();
    }

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

    @Test void inspectorUsesNativeTextSizesAtEveryUiScale() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            float previousScale = com.intellij.ui.scale.JBUIScale.scale(1f);
            Font previousLabel = UIManager.getFont("Label.font"), previousButton = UIManager.getFont("Button.font");
            try {
                for (float scale : new float[]{1f, 1.25f, 1.5f}) {
                    com.intellij.ui.scale.JBUIScale.setUserScaleFactor(scale);
                    Font label = previousLabel.deriveFont(13f * scale);
                    Font button = previousButton.deriveFont(14f * scale);
                    UIManager.put("Label.font", label); UIManager.put("Button.font", button);
                    CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions()); panel.showCell(cell("value", 0));
                    assertEquals(label.getSize2D(), field(panel, "identity", JTextArea.class).getFont().getSize2D(), .01f);
                    assertEquals(label.getSize2D(), field(panel, "summary", JLabel.class).getFont().getSize2D(), .01f);
                    assertEquals(label.getSize2D(), label(panel, "Nullable").getFont().getSize2D(), .01f);
                    assertEquals(label.getSize2D(), field(panel, "valueArea", JTextArea.class).getFont().getSize2D(), .01f);
                    assertEquals(button.getSize2D(), field(panel, "edit", JButton.class).getFont().getSize2D(), .01f);
                }
            } finally {
                com.intellij.ui.scale.JBUIScale.setUserScaleFactor(previousScale);
                UIManager.put("Label.font", previousLabel); UIManager.put("Button.font", previousButton);
            }
        });
    }

    @Test void inspectorActionsMatchTableToolbarGeometryAtEveryUiScale() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            float previousScale = com.intellij.ui.scale.JBUIScale.scale(1f);
            Font previousButton = UIManager.getFont("Button.font");
            try {
                for (float scale : new float[]{1f, 1.25f, 1.5f}) {
                    com.intellij.ui.scale.JBUIScale.setUserScaleFactor(scale);
                    UIManager.put("Button.font", previousButton.deriveFont(13f * scale));
                    CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions()); panel.showCell(cell("value", 0));
                    for (String name : new String[]{"edit", "copy", "editTable"}) {
                        JButton action = field(panel, name, JButton.class);
                        Icon icon = switch (name) {
                            case "edit" -> com.intellij.icons.AllIcons.Actions.Edit;
                            case "copy" -> com.intellij.icons.AllIcons.Actions.Copy;
                            default -> Icons.TABLE;
                        };
                        JButton toolbar = DatabaseUi.action(action.getText(), icon, "Table action");
                        assertEquals(toolbar.getPreferredSize(), action.getPreferredSize(), name + " differs from table sizing at " + scale);
                        assertEquals(toolbar.getInsets(), action.getInsets());
                        assertEquals(toolbar.getMargin(), action.getMargin());
                        assertEquals(toolbar.getIconTextGap(), action.getIconTextGap());
                        assertEquals(icon.getIconWidth(), action.getIcon().getIconWidth(), name + " icon was enlarged");
                        assertEquals(icon.getIconHeight(), action.getIcon().getIconHeight());
                    }
                    JButton tableIcon = DatabaseUi.action("", com.intellij.icons.AllIcons.General.Pin_tab, "Table icon");
                    for (boolean pinned : new boolean[]{false, true, false}) {
                        if (pinned != field(panel, "pin", JButton.class).isSelected()) click(panel, "pin");
                        for (String name : new String[]{"pin", "close"}) {
                            JButton action = field(panel, name, JButton.class);
                            assertEquals(tableIcon.getPreferredSize(), action.getPreferredSize(), name + " target differs from table controls");
                            assertEquals(tableIcon.getIcon().getIconWidth(), action.getIcon().getIconWidth(), name + " icon differs at " + scale + ", pinned=" + pinned);
                            assertEquals(tableIcon.getIcon().getIconHeight(), action.getIcon().getIconHeight());
                        }
                    }
                    JLabel tableName = field(panel, "eyebrow", JLabel.class);
                    assertEquals(Icons.TABLE.getIconWidth(), tableName.getIcon().getIconWidth());
                    assertEquals(Icons.COLUMN.getIconWidth(), label(panel, "Column").getIcon().getIconWidth());
                }
            } finally {
                com.intellij.ui.scale.JBUIScale.setUserScaleFactor(previousScale);
                UIManager.put("Button.font", previousButton);
            }
        });
    }

    @Test void completeHeaderHoverTargetsFitEveryAncestorAtEveryUiScale() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            float previousScale = com.intellij.ui.scale.JBUIScale.scale(1f);
            try {
                for (float scale : new float[]{1f, 1.25f, 1.5f}) {
                    com.intellij.ui.scale.JBUIScale.setUserScaleFactor(scale);
                    CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions()); panel.showCell(cell("value", 0));
                    for (int width : new int[]{320, 388}) {
                        panel.setSize(com.intellij.util.ui.JBUI.scale(width), com.intellij.util.ui.JBUI.scale(730));
                        for (int pass = 0; pass < 5; pass++) layout(panel);
                        for (String action : new String[]{"pin", "close"}) {
                            JButton button = field(panel, action, JButton.class);
                            button.getModel().setRollover(true);
                            Component child = button;
                            Rectangle hover = new Rectangle(0, 0, button.getWidth(), button.getHeight());
                            while (child != panel) {
                                Container parent = child.getParent();
                                hover.translate(child.getX(), child.getY());
                                assertTrue(new Rectangle(0, 0, parent.getWidth(), parent.getHeight()).contains(hover),
                                        action + " hover target clips at " + parent.getClass().getName() + " at scale " + scale);
                                child = parent;
                            }
                            button.getModel().setRollover(false);
                        }
                    }
                }
            } finally { com.intellij.ui.scale.JBUIScale.setUserScaleFactor(previousScale); }
        });
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

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
            JButton empty = field(panel, "emptyString", JButton.class);
            assertTrue(empty.isVisible(), "Empty string must be available directly, without a More menu");
            empty.doClick(0);
            assertEquals(List.of(""), actions.applied);
        });
    }

    @Test void subtitleUsesCoordinatesAndMetadataStaysVisibleAcrossSelections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
            panel.showCell(cell("first", 1));
            assertEquals("Row 2 · Column 1", field(panel, "identity", JTextArea.class).getText());
            assertTrue(field(panel, "lower", JPanel.class).isVisible());
            assertNotNull(label(panel, "Nullable"));
            panel.showColumn(new CellDetailsPanel.Column("customers", 0, cell("", 0).column(), false));
            click(panel, "pin"); assertTrue(panel.isPinned());
            assertEquals(0, panel.inspectedColumn());
            panel.clearPin(); panel.showCell(cell("second", 4));
            assertEquals("Row 5 · Column 1", field(panel, "identity", JTextArea.class).getText());
            assertTrue(field(panel, "lower", JPanel.class).isVisible());
        });
    }

    @Test void metadataKeepsReadableContrastEvenWhenTheNativeInfoColorIsTooFaint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            boolean dark = !com.intellij.ui.JBColor.isBright();
            Object previous = UIManager.get("Label.infoForeground");
            try {
                UIManager.put("Label.infoForeground", new java.awt.Color(0x777777));
                for (boolean useDark : new boolean[]{false, true}) {
                    com.intellij.ui.JBColor.setDark(useDark);
                    CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
                    panel.showColumn(new CellDetailsPanel.Column("customers", 0, cell("", 0).column(), false));
                    JLabel key = label(panel, "Nullable");
                    double a = luminance(key.getForeground()), b = luminance(panel.getBackground());
                    assertTrue((Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5);
                }
            } finally { UIManager.put("Label.infoForeground", previous); com.intellij.ui.JBColor.setDark(dark); }
        });
    }

    private static JLabel label(java.awt.Container parent, String text) {
        for (java.awt.Component component : parent.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) return label;
            if (component instanceof java.awt.Container nested) {
                JLabel found = label(nested, text); if (found != null) return found;
            }
        }
        return null;
    }
    private static double luminance(java.awt.Color color) {
        double[] values = {color.getRed() / 255d, color.getGreen() / 255d, color.getBlue() / 255d};
        for (int i = 0; i < values.length; i++) values[i] = values[i] <= .04045 ? values[i] / 12.92 : Math.pow((values[i] + .055) / 1.055, 2.4);
        return .2126 * values[0] + .7152 * values[1] + .0722 * values[2];
    }
}
