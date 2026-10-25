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
        int closes, defaults;
        public void apply(CellDetailsPanel.Cell cell, String text) { applied.add(text); }
        public void setNull(CellDetailsPanel.Cell cell) {}
        public void useDefault(CellDetailsPanel.Cell cell) { defaults++; }
        public void revert(CellDetailsPanel.Cell cell) {}
        public void editTable() {}
        public void close() { closes++; }
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

    @Test void recoveredTextSurvivesUnavailableMetadataAndCannotStageUntilEditable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var actions = new RecordingActions();
            var panel = new CellDetailsPanel(actions);
            var known = cell("original", 0);
            var unavailable = new CellDetailsPanel.Cell(known.tableName(), 0, 0, null, "original",
                    false, false, false, false, null, "Row 1", "Loading columns");
            panel.restoreDraft(unavailable, "recovered text");
            assertTrue(panel.isEditing()); assertEquals("recovered text", panel.draftText());
            assertFalse(field(panel, "apply", JButton.class).isEnabled());
            panel.getActionMap().get("apply").actionPerformed(null);
            assertTrue(actions.applied.isEmpty(), "Keyboard staging must also require metadata");
            panel.setBusy(true); panel.showCell(known);
            assertEquals("recovered text", panel.draftText());
            assertFalse(field(panel, "apply", JButton.class).isEnabled());
            panel.setBusy(false); click(panel, "cancel");
            assertFalse(panel.isEditing()); assertFalse(panel.hasDraftChange());
            assertEquals("original", field(panel, "valueArea", JTextArea.class).getText());
            panel.restoreDraft(known, "recovered text"); click(panel, "apply");
            assertEquals(List.of("recovered text"), actions.applied);
            assertFalse(panel.isEditing());
        });
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    @Test void bodyActionsAndValueKeepHeaderAlignmentAfterIdeScrollBorderCleanup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
            panel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.GRAY));
            for (int width : new int[]{320, 388, 520}) {
                for (String value : new String[]{"2", "{\"quantity\":2}"}) {
                    panel.showCell(cell(value, 1));
                    SwingUtilities.updateComponentTreeUI(panel);
                    // The editor host normalizes scroll-pane borders when embedding the panel.
                    com.intellij.util.ui.UIUtil.removeScrollBorder(panel);
                    panel.setSize(width, 730);
                    for (int pass = 0; pass < 4; pass++) layout(panel);
                    int titleX = SwingUtilities.convertPoint(field(panel, "title", JTextArea.class), 0, 0, panel).x;
                    for (String name : new String[]{"valueHeading", "valueScroll", "edit", "tableNoteLabel"}) {
                        JComponent component = field(panel, name, JComponent.class);
                        assertEquals(titleX, SwingUtilities.convertPoint(component, 0, 0, panel).x,
                                name + " must align with the header after IDE border cleanup at width " + width);
                    }
                    JComponent valueScroll = field(panel, "valueScroll", JComponent.class);
                    int valueRight = SwingUtilities.convertPoint(valueScroll, valueScroll.getWidth(), 0, panel).x;
                    int gutter = titleX - panel.getInsets().left;
                    assertTrue(valueRight <= width - panel.getInsets().right - gutter,
                            "The value must retain its right gutter");
                }
            }
        });
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

    @Test void inspectorHeaderOffersOnlyCloseAndHasNoPinAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
            panel.showCell(cell("value", 0));
            JPanel controls = field(panel, "controls", JPanel.class);
            assertEquals(1, controls.getComponentCount());
            assertSame(field(panel, "close", JButton.class), controls.getComponent(0));
            assertEquals("Close details panel", controls.getComponent(0).getAccessibleContext().getAccessibleName());
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

    @Test void databaseDefaultIsExplicitAndUnavailableDefaultsCannotDispatch() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RecordingActions actions = new RecordingActions(); CellDetailsPanel panel = new CellDetailsPanel(actions);
            var persisted = cell(null, 0);
            panel.showCell(persisted);
            JButton defaults = field(panel, "useDefault", JButton.class);
            assertTrue(defaults.isVisible()); assertFalse(defaults.isEnabled());
            defaults.doClick(0); assertEquals(0, actions.defaults);
            panel.showCell(new CellDetailsPanel.Cell(persisted.tableName(), 0, 0, persisted.column(), null,
                    true, false, true, false, null, "New row", ""));
            assertTrue(defaults.isEnabled()); defaults.doClick(0);
            assertEquals(1, actions.defaults); assertTrue(actions.applied.isEmpty());
            var noDefault = new ColumnMetadata("value", "VARCHAR", Types.VARCHAR, 100, 0, true, false, false, null);
            panel.showCell(new CellDetailsPanel.Cell("customers", 0, 0, noDefault, null,
                    true, false, true, false, null, "New row", ""));
            assertFalse(defaults.isEnabled()); defaults.doClick(0); assertEquals(1, actions.defaults);
            assertEquals(Color.WHITE, defaults.getForeground());
            defaults.setSize(defaults.getPreferredSize());
            var image = new java.awt.image.BufferedImage(defaults.getWidth(), defaults.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); defaults.paint(graphics); graphics.dispose();
            Color surface = new Color(image.getRGB(4, defaults.getHeight() / 2));
            assertTrue(Math.max(surface.getRed(), Math.max(surface.getGreen(), surface.getBlue())) < 120,
                    "Unavailable database defaults must use a deep grey surface");
        });
    }

    @Test void formattedRawEditingAndCancelKeepTheSameValueViewportBounds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String json = "{\"items\":[" + java.util.stream.IntStream.range(0, 20)
                    .mapToObj(i -> "{\"id\":" + i + "}").collect(java.util.stream.Collectors.joining(",")) + "]}";
            for (Dimension size : new Dimension[]{new Dimension(320, 600), new Dimension(388, 730), new Dimension(520, 800)}) {
                CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
                panel.showCell(cell(json, 0)); panel.setSize(size);
                for (int pass = 0; pass < 4; pass++) layout(panel);
                JScrollPane value = field(panel, "valueScroll", JScrollPane.class);
                Rectangle original = SwingUtilities.convertRectangle(value.getParent(), value.getBounds(), panel);
                click(panel, "edit");
                for (int pass = 0; pass < 4; pass++) layout(panel);
                assertEquals(original, SwingUtilities.convertRectangle(value.getParent(), value.getBounds(), panel),
                        "Entering raw edit mode must preserve the formatted viewport at " + size);
                field(panel, "valueArea", JTextArea.class).setText("changed\n".repeat(30));
                for (int pass = 0; pass < 4; pass++) layout(panel);
                assertEquals(original, SwingUtilities.convertRectangle(value.getParent(), value.getBounds(), panel));
                click(panel, "cancel");
                for (int pass = 0; pass < 4; pass++) layout(panel);
                assertEquals(original, SwingUtilities.convertRectangle(value.getParent(), value.getBounds(), panel));
            }
        });
    }

    @Test void mouseClicksHideFocusCuesAndKeyboardTraversalRestoresThem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
            for (String name : new String[]{"edit", "copy", "apply", "cancel", "useDefault", "editTable",
                    "formattedTab", "rawTab", "wrap", "close"}) {
                AbstractButton button = field(panel, name, AbstractButton.class);
                var mouse = new java.awt.event.MouseEvent(button, java.awt.event.MouseEvent.MOUSE_PRESSED,
                        0, 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1);
                for (var listener : button.getMouseListeners()) listener.mousePressed(mouse);
                assertFalse(DatabaseUi.isFocusVisible(button), name + " must not retain a mouse focus ring");
                var traversal = new java.awt.event.FocusEvent(button, java.awt.event.FocusEvent.FOCUS_GAINED,
                        false, null, java.awt.event.FocusEvent.Cause.TRAVERSAL_FORWARD);
                for (var listener : button.getFocusListeners()) listener.focusGained(traversal);
                assertTrue(DatabaseUi.isFocusVisible(button), name + " must keep keyboard focus visible");
            }
        });
    }

    @Test void primaryActionsShareARowAndStagingRetainsReadableTextAndEqualHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
            panel.showCell(cell("{\"x\":1}", 0)); panel.setSize(388, 730);
            for (int pass = 0; pass < 4; pass++) layout(panel);
            JButton edit = field(panel, "edit", JButton.class), defaults = field(panel, "useDefault", JButton.class),
                    copy = field(panel, "copy", JButton.class), apply = field(panel, "apply", JButton.class),
                    cancel = field(panel, "cancel", JButton.class);
            assertEquals(edit.getY(), defaults.getY()); assertEquals(edit.getY(), copy.getY());
            assertTrue(edit.getX() < defaults.getX() && defaults.getX() < copy.getX());
            edit.doClick(0);
            for (int pass = 0; pass < 4; pass++) layout(panel);
            assertFalse(apply.isEnabled()); assertTrue(cancel.isVisible());
            assertEquals(copy.getHeight(), apply.getHeight()); assertEquals(copy.getHeight(), cancel.getHeight());
            assertEquals(copy.getY(), apply.getY()); assertEquals(copy.getY(), cancel.getY());
            assertTrue(apply.getY() >= 0 && apply.getY() + apply.getHeight() <= apply.getParent().getHeight());
            field(panel, "valueArea", JTextArea.class).append(" ");
            assertTrue(apply.isEnabled(), "Typing into a JSON draft must enable staging");
            field(panel, "valueArea", JTextArea.class).setText("changed");
            assertTrue(apply.isEnabled()); assertEquals(Color.WHITE, apply.getForeground());
            cancel.doClick(0); assertFalse(panel.isEditing());
        });
    }

    @Test void buttonAndTabTextHonorComponentAntialiasingAtDesktopScales() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CellDetailsPanel panel = new CellDetailsPanel(new RecordingActions());
            for (String name : new String[]{"edit", "apply", "cancel", "useDefault", "formattedTab", "rawTab", "wrap"}) {
                AbstractButton button = field(panel, name, AbstractButton.class);
                button.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                for (boolean enabled : new boolean[]{true, false}) {
                    button.setEnabled(enabled);
                    for (double scale : new double[]{1, 1.25, 1.5}) {
                        var image = new java.awt.image.BufferedImage((int)(180 * scale), (int)(36 * scale),
                                java.awt.image.BufferedImage.TYPE_INT_RGB);
                        Graphics2D graphics = image.createGraphics();
                        graphics.setColor(Color.MAGENTA); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                        graphics.scale(scale, scale); graphics.setFont(button.getFont());
                        // Native Swing can supply AA through the component rather than the incoming Graphics.
                        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
                        try {
                            var paint = button.getUI().getClass().getDeclaredMethod("paintText", Graphics.class,
                                    AbstractButton.class, Rectangle.class, String.class);
                            paint.setAccessible(true);
                            paint.invoke(button.getUI(), graphics, button, new Rectangle(4, 4, 170, 26), button.getText());
                        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                        finally { graphics.dispose(); }
                        int blended = 0;
                        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                            int pixel = image.getRGB(x, y);
                            if (pixel != Color.MAGENTA.getRGB() && pixel != button.getForeground().getRGB()) blended++;
                        }
                        assertTrue(blended > 0, name + " must retain smooth text edges at scale " + scale + ", enabled=" + enabled);
                    }
                }
            }
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
}
