package com.segfault03.ideadb.sql.settings;

import com.segfault03.ideadb.sql.lang.SqlSemanticColors.Role;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The two-column choice of color family: the families on the left, a light and a dark preview on the right. */
class SqlColorFamilyChooserTest {
    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container container) found.addAll(all(container, type));
        }
        return found;
    }

    private static Color tableColor(JTextPane pane) {
        String text = pane.getText();
        return StyleConstants.getForeground(pane.getStyledDocument().getCharacterElement(text.indexOf("orders")).getAttributes());
    }

    @Test void itStartsOnTheGivenFamilyWithOneRadioPerFamilyAndTwoPreviews() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var chooser = new SqlColorFamilyChooser(ColorFamily.LIME);
            assertEquals(ColorFamily.LIME, chooser.selected());
            var radios = all(chooser, AbstractButton.class);
            assertEquals(List.of("Coral", "Periwinkle", "Lime", "Neutral"), radios.stream().map(AbstractButton::getText).toList());
            assertEquals(List.of(false, false, true, false), radios.stream().map(AbstractButton::isSelected).toList());
            var panes = chooser.previewPanes();
            assertEquals(2, panes.size());
            assertEquals(SqlColorFamilyPreview.background(false), panes.get(0).getBackground(), "the light editor comes first");
            assertEquals(SqlColorFamilyPreview.background(true), panes.get(1).getBackground());
        });
    }

    @Test void choosingAFamilyColorsBothPreviewsInIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var chooser = new SqlColorFamilyChooser(ColorFamily.CORAL);
            assertEquals(SqlColorFamilyPreview.colorOf(Role.TABLE, ColorFamily.CORAL, false), tableColor(chooser.previewPanes().get(0)));
            all(chooser, AbstractButton.class).get(1).doClick(0);
            assertEquals(ColorFamily.PERIWINKLE, chooser.selected());
            assertEquals(SqlColorFamilyPreview.colorOf(Role.TABLE, ColorFamily.PERIWINKLE, false), tableColor(chooser.previewPanes().get(0)));
            assertEquals(SqlColorFamilyPreview.colorOf(Role.TABLE, ColorFamily.PERIWINKLE, true), tableColor(chooser.previewPanes().get(1)));
        });
    }

    @Test void listenersHearAChangeOnceAndNothingForTheSameFamily() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var chooser = new SqlColorFamilyChooser(ColorFamily.CORAL);
            AtomicInteger heard = new AtomicInteger();
            chooser.addChangeListener(heard::incrementAndGet);
            chooser.select(ColorFamily.CORAL);
            assertEquals(0, heard.get());
            chooser.select(ColorFamily.NEUTRAL);
            chooser.select(ColorFamily.NEUTRAL);
            assertEquals(1, heard.get());
            assertTrue(all(chooser, AbstractButton.class).get(3).isSelected(), "the radio follows a choice made in code");
        });
    }

    @Test void disablingTheChooserDisablesTheChoices() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var chooser = new SqlColorFamilyChooser(ColorFamily.CORAL);
            chooser.setEnabled(false);
            for (AbstractButton radio : all(chooser, AbstractButton.class)) assertFalse(radio.isEnabled());
            chooser.setEnabled(true);
            for (AbstractButton radio : all(chooser, AbstractButton.class)) assertTrue(radio.isEnabled());
        });
    }
}
