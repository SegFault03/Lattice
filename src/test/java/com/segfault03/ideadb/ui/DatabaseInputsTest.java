package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseInputsTest {
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
