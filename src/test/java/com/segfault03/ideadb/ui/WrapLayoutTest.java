package com.segfault03.ideadb.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class WrapLayoutTest {
    @Test
    void preferredHeightDoesNotReserveAnEmptyRowWhenControlsFitAtTheBoundary() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WrapLayout layout = new WrapLayout(FlowLayout.LEFT, 4, 4);
            JPanel row = new JPanel(layout);
            JPanel first = new JPanel();
            first.setPreferredSize(new Dimension(100, 28));
            JPanel second = new JPanel();
            second.setPreferredSize(new Dimension(80, 28));
            row.add(first);
            row.add(second);
            row.setSize(188, 100);
            row.doLayout();
            assertEquals(first.getY(), second.getY(), "FlowLayout puts both controls on the same row");
            assertEquals(second.getY() + second.getHeight() + layout.getVgap(), row.getPreferredSize().height,
                    "Preferred height must describe the actual rows, without an extra blank row");
        });
    }
}
