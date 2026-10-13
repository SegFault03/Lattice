package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.DatabaseType;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DataSourcePanelTest {
    @Test void sourceChoicesHaveIconsAndKeepOneSelectionWhileDispatchingEveryProvider() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<DatabaseType> selected = new ArrayList<>();
            AtomicReference<DataSourcePanel> holder = new AtomicReference<>();
            DataSourcePanel picker = new DataSourcePanel(type -> {
                selected.add(type);
                holder.get().setSelected(type);
            });
            holder.set(picker);
            picker.setSelected(DatabaseType.MYSQL);
            List<JToggleButton> buttons = choices(picker);
            assertEquals(DatabaseType.values().length, buttons.size());
            for (int index = 0; index < buttons.size(); index++) {
                JToggleButton button = buttons.get(index);
                assertEquals(DatabaseType.values()[index].getDisplayName(), button.getAccessibleContext().getAccessibleName());
                assertNotNull(button.getIcon());
                assertEquals(24, button.getIcon().getIconWidth());
                button.doClick(0);
                assertTrue(button.isSelected());
                assertEquals(1, buttons.stream().filter(AbstractButton::isSelected).count());
            }
            assertEquals(List.of(DatabaseType.values()), selected);
        });
    }

    @Test void narrowPickerScrollsVerticallyWithoutGrowingWithItsContent() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DataSourcePanel picker = new DataSourcePanel(type -> {});
            assertTrue(picker.getPreferredSize().width <= 180);
            picker.setSize(picker.getPreferredSize().width, 120);
            layout(picker);
            JScrollPane scroll = (JScrollPane) ((BorderLayout)picker.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
            assertTrue(scroll.getVerticalScrollBar().isVisible());
            scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
            layout(picker);
            assertTrue(scroll.getViewport().getViewPosition().y > 0);
            assertTrue(choices(picker).getLast().getBounds().intersects(scroll.getViewport().getViewRect()),
                    "The last source can be reached without expanding the dialog");
        });
    }

    private static List<JToggleButton> choices(Container container) {
        List<JToggleButton> result = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JToggleButton button) result.add(button);
            else if (child instanceof Container nested) result.addAll(choices(nested));
        }
        return result;
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
