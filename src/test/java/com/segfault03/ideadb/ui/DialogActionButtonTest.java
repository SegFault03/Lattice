package com.segfault03.ideadb.ui;

import com.segfault03.ideadb.dialog.AlterTablePanel;
import com.segfault03.ideadb.dialog.CreateTablePanel;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DialogActionButtonTest {
    @Test void footerActionsPaintCompleteTextWithThemeAndDefaultButtonPadding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var config = new ConnectionConfig(DatabaseType.HSQLDB, "Local development");
            List<JButton> buttons = List.of(new CreateTablePanel(config, "PUBLIC").createButton(),
                    new AlterTablePanel(config, "PUBLIC", "WARMUP_ORDERS", List.of()).applyButton());
            for (JButton button : buttons) {
                // Simulate a native delegate with content padding outside border/margin
                // insets. Checking only stringWidth + icon + insets misses this case.
                var ui = new PaddedButtonUI(); button.setUI(ui);
                var root = new JRootPane(); root.getContentPane().add(button);
                for (boolean isDefault : new boolean[]{false, true}) {
                    root.setDefaultButton(isDefault ? button : null);
                    for (float fontSize : new float[]{13, 16.25f, 19.5f}) {
                        button.setFont(button.getFont().deriveFont(fontSize));
                        for (String text : List.of("Apply changes", "Apply all changes", "Create table")) {
                            button.setText(text);
                            for (boolean enabled : new boolean[]{false, true}) {
                                button.setEnabled(enabled);
                                button.setSize(button.getPreferredSize());
                                var pixels = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
                                Graphics2D graphics = pixels.createGraphics();
                                try { button.paint(graphics); } finally { graphics.dispose(); }
                                assertEquals(text, ui.paintedText, () -> "Native painter clipped " + text
                                        + " at font " + fontSize + ", enabled=" + enabled + ", default=" + isDefault);
                                assertTrue(button.getWidth() >= ui.getPreferredSize(button).width,
                                        "Footer action must respect its delegate's full preferred width");
                            }
                        }
                    }
                }
            }
        });
    }

    private static final class PaddedButtonUI extends BasicButtonUI {
        private String paintedText;
        private int padding(JButton button) { return 18 + (button.isDefaultButton() ? 8 : 0); }
        @Override public Dimension getPreferredSize(JComponent component) {
            JButton button = (JButton) component;
            Dimension size = BasicGraphicsUtils.getPreferredButtonSize(button, button.getIconTextGap());
            return new Dimension(size.width + padding(button) * 2, size.height);
        }
        @Override public void paint(Graphics graphics, JComponent component) {
            JButton button = (JButton) component;
            Insets insets = button.getInsets();
            Rectangle available = new Rectangle(insets.left + padding(button), insets.top,
                    button.getWidth() - insets.left - insets.right - padding(button) * 2,
                    button.getHeight() - insets.top - insets.bottom);
            paintedText = SwingUtilities.layoutCompoundLabel(button, graphics.getFontMetrics(button.getFont()),
                    button.getText(), button.isEnabled() ? button.getIcon() : button.getDisabledIcon(),
                    button.getVerticalAlignment(), button.getHorizontalAlignment(),
                    button.getVerticalTextPosition(), button.getHorizontalTextPosition(),
                    available, new Rectangle(), new Rectangle(), button.getIconTextGap());
        }
    }
}
