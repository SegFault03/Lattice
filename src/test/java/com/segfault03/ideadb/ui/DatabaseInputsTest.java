package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseInputsTest {
    @Test void naturalComboWidthIncludesTheCaptionArrowAndThemePadding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object previous = UIManager.get("ComboBox.padding");
            try {
                UIManager.put("ComboBox.padding", new Insets(0, 8, 0, 12));
                JComboBox<String> combo = DatabaseInputs.comboBox(new String[]{"Auto: Off", "10s"});
                for (int fontSize : new int[]{13, 22}) {
                    combo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fontSize));
                    int arrow = java.util.Arrays.stream(combo.getComponents()).filter(JButton.class::isInstance)
                            .mapToInt(child -> child.getPreferredSize().width).max().orElse(DatabaseInputs.height(combo));
                    int text = combo.getFontMetrics(combo.getFont()).stringWidth("Auto: Off");
                    Insets border = combo.getInsets();
                    assertTrue(combo.getPreferredSize().width >= text + arrow + border.left + border.right + 20);
                    assertEquals(combo.getPreferredSize(), combo.getMinimumSize(), "Move the control to overflow instead of squeezing its caption");
                }
            } finally { UIManager.put("ComboBox.padding", previous); }
        });
    }

    @Test void overflowPickerUsesTheRealSelectionAndDetachesItsModelWhenClosed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> source = DatabaseInputs.comboBox(new String[]{"Auto: Off", "10s"});
            DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) source.getModel();
            java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
            source.addActionListener(event -> changes.incrementAndGet());
            JComboBox<String> popup = DatabaseInputs.mirrorComboBox(source);
            popup.setSelectedItem("10s");
            assertEquals("10s", source.getSelectedItem());
            assertEquals(1, changes.get(), "The production selection listener must run once");
            popup.removeNotify();
            assertFalse(java.util.Arrays.asList(model.getListDataListeners()).contains(popup));
            // The first selection initializes the source popup's accessible list.
            // Reopening/closing overflow must not accumulate further model listeners.
            int listeners = model.getListDataListeners().length;
            for (int i = 0; i < 3; i++) {
                JComboBox<String> reopened = DatabaseInputs.mirrorComboBox(source);
                reopened.setSelectedItem(i % 2 == 0 ? "Auto: Off" : "10s");
                reopened.removeNotify();
                assertEquals(listeners, model.getListDataListeners().length);
            }
        });
    }

    @Test void openingAndLeavingAnAutomaticCellPreservesDatabaseGeneration() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object automatic = com.segfault03.ideadb.model.RowDefaults.Value.USE_DEFAULT;
            JTable table = new JTable(new DefaultTableModel(new Object[][]{{automatic}}, new Object[]{"ID [Auto]"}));
            table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
                @Override protected void setValue(Object value) { setText(value == automatic ? "(Auto)" : value.toString()); }
            });
            DatabaseInputs.styleTableEditors(table);
            assertTrue(table.editCellAt(0, 0));
            assertEquals("(Auto)", ((JTextField)table.getEditorComponent()).getText());
            assertTrue(table.getCellEditor().stopCellEditing());
            assertSame(automatic, table.getValueAt(0, 0));
            assertTrue(table.editCellAt(0, 0));
            ((JTextField)table.getEditorComponent()).setText("42");
            assertTrue(table.getCellEditor().stopCellEditing());
            assertEquals("42", table.getValueAt(0, 0));
        });
    }
    @Test void nativeTextAndPasswordDelegatesHaveTheSameTextOrigin() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField text = DatabaseInputs.textField("sample");
            JPasswordField password = DatabaseInputs.passwordField();
            password.setText("sample");
            text.setUI(new com.intellij.ide.ui.laf.darcula.ui.DarculaTextFieldUI());
            password.setUI(new com.intellij.ide.ui.laf.darcula.ui.DarculaPasswordFieldUI());
            for (JTextField field : new JTextField[]{text, password}) {
                field.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                field.setSize(220, field.getPreferredSize().height);
            }
            try {
                assertEquals(password.modelToView2D(0).getX(), text.modelToView2D(0).getX(), 0.01);
                assertEquals(text.getInsets().left, text.modelToView2D(0).getX(), 0.01);
            } catch (javax.swing.text.BadLocationException error) { throw new AssertionError(error); }
        });
    }

    @Test void browseButtonIsDistinctAndPreservesItsActionAndDisabledState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            com.intellij.openapi.ui.TextFieldWithBrowseButton browse = DatabaseInputs.browseField();
            JButton button = (JButton)java.util.Arrays.stream(browse.getComponents())
                    .filter(JButton.class::isInstance).findFirst().orElseThrow();
            assertNotNull(button.getIcon());
            assertTrue(button.isContentAreaFilled());
            assertTrue(button.getInsets().left > 0);
            assertEquals("Browse files…", button.getToolTipText());
            java.util.concurrent.atomic.AtomicInteger clicks = new java.util.concurrent.atomic.AtomicInteger();
            browse.addActionListener(event -> clicks.incrementAndGet());
            button.doClick(0);
            assertEquals(1, clicks.get());
            browse.setEnabled(false);
            assertFalse(button.isEnabled());
            assertFalse(browse.getTextField().isEnabled());
        });
    }
    @Test void fontGrowthAndLookAndFeelRefreshKeepInputsConsistentWithoutClipping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent[] fields = {DatabaseInputs.textField("Connection"), DatabaseInputs.passwordField(),
                    DatabaseInputs.comboBox(new String[]{"Auto: Off"}), DatabaseInputs.browseField()};
            for (int fontSize : new int[]{13, 22}) {
                Font font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
                for (JComponent field : fields) {
                    field.setFont(font);
                    field.updateUI();
                    Dimension size = field.getPreferredSize();
                    assertEquals(DatabaseInputs.height(field), size.height);
                    assertEquals(fields[0].getPreferredSize().height, size.height);
                    Insets insets = field.getInsets();
                    assertEquals(insets.top, insets.bottom);
                    assertTrue(size.height - insets.top - insets.bottom >= field.getFontMetrics(font).getHeight());
                }
            }
        });
    }

    @Test void tableEditorsPreserveTypedValidationCommitAndCancellation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DefaultTableModel model = new DefaultTableModel(new Object[][]{{7}}, new Object[]{"size"}) {
                @Override public Class<?> getColumnClass(int column) { return Integer.class; }
            };
            JTable table = new JTable(model);
            DatabaseInputs.styleTableEditors(table);
            assertTrue(table.editCellAt(0, 0));
            JTextField editor = (JTextField)table.getEditorComponent();
            assertTrue(editor.isOpaque(), "The live table editor must paint its theme surface");
            assertEquals(UIManager.getColor("TextField.background"), editor.getBackground());
            assertEquals(UIManager.getColor("TextField.foreground"), editor.getForeground());
            editor.setText("invalid integer");
            assertFalse(table.getCellEditor().stopCellEditing());
            assertEquals(7, model.getValueAt(0, 0));
            assertEquals("error", editor.getClientProperty("JComponent.outline"));
            editor.setText("42");
            assertTrue(table.getCellEditor().stopCellEditing());
            assertEquals(42, model.getValueAt(0, 0));
            assertInstanceOf(Integer.class, model.getValueAt(0, 0));
            assertTrue(table.editCellAt(0, 0));
            assertNull(((JTextField)table.getEditorComponent()).getClientProperty("JComponent.outline"));
            ((JTextField)table.getEditorComponent()).setText("99");
            table.getCellEditor().cancelCellEditing();
            assertEquals(42, model.getValueAt(0, 0));
        });
    }
}
