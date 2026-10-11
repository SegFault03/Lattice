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
            row.setSize(192, 100);
            row.doLayout();
            assertEquals(first.getY(), second.getY(), "FlowLayout puts both controls on the same row");
            assertEquals(second.getY() + second.getHeight() + layout.getVgap(), row.getPreferredSize().height,
                    "Preferred height must describe the actual rows, without an extra blank row");
        });
    }

    @Test
    void resizingMeasuresNestedHeaderBeforeLayingOutTheEditor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel editor = new JPanel(new WidthAwareBorderLayout());
            JPanel heading = new JPanel(new WidthAwareBorderLayout());
            JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
            JPanel filters = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
            for (int i = 0; i < 4; i++) actions.add(control(80, 28));
            JPanel input = new JPanel(new BorderLayout(4, 0));
            input.add(control(50, 26), BorderLayout.WEST);
            input.add(control(220, 26), BorderLayout.CENTER);
            filters.add(input);
            filters.add(control(70, 28));
            heading.add(actions, BorderLayout.NORTH);
            heading.add(filters, BorderLayout.CENTER);
            editor.add(heading, BorderLayout.NORTH);
            JPanel content = new JPanel();
            editor.add(content, BorderLayout.CENTER);
            for (int width : new int[]{800, 190, 600, 190}) {
                editor.setSize(width, 600);
                layoutAll(editor);
                assertEquals(heading.getHeight(), content.getY(), "Grid must start after every header row");
                assertContained(actions);
                assertContained(filters);
                assertContained(input);
                assertEquals(filters.getY() + filters.getHeight(), heading.getHeight());
            }
        });
    }

    @Test
    void oversizedNestedRowsMeasureTheirFullHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel outer = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
            JPanel group = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
            group.add(control(80, 28));
            group.add(control(74, 28));
            outer.add(group);
            outer.setSize(140, 300);
            layoutAll(outer);
            assertContained(group);
            assertTrue(group.getComponent(1).getY() > group.getComponent(0).getY());
            assertEquals(group.getY() + group.getHeight() + 4, outer.getPreferredSize().height);
        });
    }

    private static JPanel control(int width, int height) {
        JPanel control = new JPanel();
        control.setPreferredSize(new Dimension(width, height));
        return control;
    }

    private static void layoutAll(Container container) {
        container.doLayout();
        for (Component child : container.getComponents())
            if (child instanceof Container nested) layoutAll(nested);
    }

    private static void assertContained(Container container) {
        Rectangle area = new Rectangle(0, 0, container.getWidth(), container.getHeight());
        for (Component child : container.getComponents()) assertTrue(area.contains(child.getBounds()),
                () -> child.getBounds() + " clipped by " + area);
    }
}
