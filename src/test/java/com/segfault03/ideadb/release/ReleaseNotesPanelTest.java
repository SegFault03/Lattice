package com.segfault03.ideadb.release;

import com.segfault03.ideadb.ui.ReleaseNotesPanel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseNotesPanelTest {
    @BeforeAll static void icons() {
        com.intellij.ui.IconManager.Companion.activate(new com.intellij.ui.icons.CoreIconManager());
        com.intellij.openapi.util.IconLoader.activate();
    }
    @Test void guidesReflowAndFullNotesExpandWithoutRequiringAProject() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int width : new int[]{520, 1100}) {
                var panel = new ReleaseNotesPanel(null); panel.setSize(width, 800);
                for (int i = 0; i < 6; i++) layout(panel);
                var scroll = (JScrollPane)panel.getComponent(0);
                assertTrue(scroll.getViewport().getViewSize().height > 800, "All three guides must remain scrollable");
                List<Component> all = descendants(panel);
                for (var feature : ReleaseNotesContent.features()) {
                    var title = all.stream().filter(c -> c instanceof JTextArea text && text.getText().equals(feature.title()))
                            .map(c -> (JTextArea)c).findFirst().orElseThrow();
                    assertTrue(title.getWidth() > 100); assertTrue(title.getHeight() >= title.getFontMetrics(title.getFont()).getHeight());
                    assertTrue(all.stream().anyMatch(c -> c instanceof JButton button &&
                            button.getAccessibleContext().getAccessibleName().startsWith(feature.alt())));
                }
                var more = all.stream().filter(c -> c instanceof JToggleButton button && button.getText().equals("All changes in this release"))
                        .map(c -> (JToggleButton)c).findFirst().orElseThrow();
                int before = scroll.getViewport().getViewSize().height;
                more.doClick(0); for (int i = 0; i < 6; i++) layout(panel);
                assertTrue(scroll.getViewport().getViewSize().height > before);
            }
        });
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container child) layout(child); }
    private static List<Component> descendants(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component c : parent.getComponents()) { result.add(c); if (c instanceof Container child) result.addAll(descendants(child)); }
        return result;
    }
}
