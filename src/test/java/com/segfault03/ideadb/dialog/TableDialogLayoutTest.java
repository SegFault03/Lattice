package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.*;
import com.segfault03.ideadb.ui.DatabaseUi;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableDialogLayoutTest {
    @Test void previewAbsorbsHeightChangesAndTheFormScrollsOnlyBelowItsReadableMinimum() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel column=TableDialogLayout.column();
            JLabel help=new com.segfault03.ideadb.ui.WrappingLabel("A longer form hint that wraps when the inspector becomes narrow and unwraps when it grows again.");
            column.add(help);
            JPanel surface=new JPanel(new BorderLayout(0,8));surface.setBorder(BorderFactory.createEmptyBorder(12,14,12,14));
            JLabel caption=new JLabel("SQL preview");JTextPane sql=new JTextPane();surface.add(caption,BorderLayout.NORTH);surface.add(new JScrollPane(sql),BorderLayout.CENTER);
            column.add(TableDialogLayout.preview(surface,caption,sql,180));
            JScrollPane scroll=TableDialogLayout.inspector(column);
            for(int width:new int[]{500,220,700,221,500}) {
                scroll.setSize(width,400);for(int pass=0;pass<4;pass++)layout(scroll);
                int minimum=column.getMinimumSize().height;
                for(int height:new int[]{minimum+80,minimum+1,minimum,minimum+2,minimum-1,minimum+1,minimum+120}) {
                    scroll.setSize(width,height);for(int pass=0;pass<4;pass++)layout(scroll);
                    int actualMinimum=column.getMinimumSize().height;
                    int available=scroll.getViewport().getHeight();
                    assertEquals(available<actualMinimum,scroll.getVerticalScrollBar().isVisible(),"Scrollbar should follow the form minimum, not preferred preview height");
                    if(available>=actualMinimum)assertEquals(available,column.getHeight());
                    Component preview=column.getComponent(1);
                    assertTrue(preview.getHeight()>=preview.getMinimumSize().height);
                    assertTrue(preview.getY()+preview.getHeight()<=column.getHeight());
                }
            }
        });
    }

    @Test void inspectorHeaderMovesActionsBelowLongNamesWithoutClippingThem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel title=new JLabel("A_LONG_COLUMN_NAME_WITH_DETAILS");
            JButton rename=DatabaseUi.inspectorAction("Rename",null,"Rename column");
            JButton drop=DatabaseUi.inspectorAction("Drop column",null,"Drop column");
            JPanel header=TableDialogChrome.inspectorHeading(TableDialogChrome.inline(title),rename,drop);
            int natural=header.getPreferredSize().width;
            for(int width:new int[]{natural+20,natural-80,natural+5}) {
                int height=((TableDialogLayout.WidthAware)header).preferredSizeForWidth(width).height;
                header.setSize(width,height);layout(header);
                Point label=SwingUtilities.convertPoint(title,0,0,header),action=SwingUtilities.convertPoint(drop,0,0,header);
                assertEquals(width<natural,action.y>=label.y+title.getHeight());
                assertTrue(action.x+drop.getWidth()<=width);
                assertFullText(drop,drop.getText(),drop.getIcon(),drop.getIconTextGap(),new Insets(0,0,0,0));
            }
        });
    }

    @Test void aPreviousScrollbarCannotKeepItselfVisibleByWrappingTheHint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel column=TableDialogLayout.column();
            column.add(new com.segfault03.ideadb.ui.WrappingLabel("Hints should unwrap when the scrollbar can disappear and make the entire viewport width available again."));
            JScrollPane scroll=TableDialogLayout.inspector(column);
            int bar=scroll.getVerticalScrollBar().getPreferredSize().width;
            boolean checked=false;
            for(int width=180;width<420;width++) {
                column.setSize(width,400);for(int i=0;i<4;i++)layout(column);int full=column.getMinimumSize().height;
                column.setSize(width-bar,400);for(int i=0;i<4;i++)layout(column);int narrow=column.getMinimumSize().height;
                if(narrow<=full)continue;
                scroll.setSize(width,full-1);for(int i=0;i<4;i++)layout(scroll);
                assertTrue(scroll.getVerticalScrollBar().isVisible());
                scroll.setSize(width,full+1);for(int i=0;i<4;i++)layout(scroll);
                assertFalse(scroll.getVerticalScrollBar().isVisible(),"A scrollbar must not reserve its own width after the content fits: width="+width+", full="+full+", narrow="+narrow+", extent="+scroll.getViewport().getExtentSize()+", view="+column.getSize()+", tracks="+((Scrollable)column).getScrollableTracksViewportHeight()+", minimum="+column.getMinimumSize());
                checked=true;break;
            }
            assertTrue(checked,"The fixture must cross a real text-wrapping threshold");
        });
    }

    @Test void nativeButtonMarginsDoNotConsumeActionTextSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var button = DatabaseUi.action("Apply changes", UIManager.getIcon("OptionPane.informationIcon"), "Apply changes");
            assertEquals(new Insets(0, 0, 0, 0), button.getMargin());
            for (float size : new float[]{13, 16.25f, 19.5f}) {
                button.setFont(button.getFont().deriveFont(size));
                button.setSize(button.getPreferredSize());
                assertFullText(button, button.getText(), button.getIcon(), button.getIconTextGap(), new Insets(0,0,0,0));
            }
        });
    }

    @Test void bothDialogsKeepTextAfterFontGrowthAndResize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var config = new ConnectionConfig(DatabaseType.HSQLDB, "Local development");
            var alter = new AlterTablePanel(config, "PUBLIC", "WARMUP_ORDERS", List.of(
                    new ColumnMetadata("QUANTITY", "INTEGER", Types.INTEGER, 32, 0, false, false, false, null)));
            var create = new CreateTablePanel(config, "PUBLIC");
            for (JPanel panel : List.of(alter, create)) {
                var fonts = new java.util.IdentityHashMap<Component, Font>();
                for (Component child : descendants(panel)) if (child.getFont() != null) fonts.put(child, child.getFont());
                for (float scale : new float[]{1, 1.25f, 1.5f, 1}) {
                    // Realize once before changing fonts, so stale BoxLayout caches are exercised.
                    panel.setSize(panel.getPreferredSize()); layout(panel);
                    fonts.forEach((child, font) -> child.setFont(font.deriveFont(font.getSize2D() * scale)));
                    for (int width : new int[]{panel.getMinimumSize().width, panel.getPreferredSize().width, 1200}) {
                        panel.setSize(width, panel.getPreferredSize().height);
                        for (int pass = 0; pass < 4; pass++) layout(panel);
                        for (Component child : descendants(panel)) {
                            if (!visible(child, panel)) continue;
                            if (child instanceof JLabel label && label.getText() != null && !label.getText().isEmpty())
                                assertFullText(label, label.getText(), label.getIcon(), label.getIconTextGap(), new Insets(0,0,0,0));
                            if (child instanceof JButton button && button.getText() != null && !button.getText().isEmpty())
                                assertFullText(button, button.getText(), button.getIcon(), button.getIconTextGap(), new Insets(0,0,0,0));
                        }
                    }
                }
            }
        });
    }

    @Test void formRowsFollowChangedContentWithoutShrinkingOrHorizontalOverflow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel column = TableDialogLayout.column();
            JLabel label = new JLabel("Short"); TableDialogLayout.stack(column, label);
            column.setSize(500, 100); layout(column);
            label.setText("WARMUP_ORDERS"); label.setFont(label.getFont().deriveFont(24f));
            layout(column);
            assertEquals(500, label.getWidth()); assertEquals(label.getPreferredSize().height, label.getHeight());
            assertEquals(label.getPreferredSize(), column.getPreferredSize());
            label.setVisible(false); assertEquals(0, column.getPreferredSize().height);
        });
    }

    private static void assertFullText(JComponent component, String text, Icon icon, int gap, Insets margin) {
        Insets insets = component.getInsets();
        Rectangle available = new Rectangle(0, 0, component.getWidth() - insets.left - insets.right - margin.left - margin.right,
                component.getHeight() - insets.top - insets.bottom);
        String rendered = SwingUtilities.layoutCompoundLabel(component, component.getFontMetrics(component.getFont()), text, icon,
                SwingConstants.CENTER, SwingConstants.LEFT, SwingConstants.CENTER, SwingConstants.RIGHT,
                available, new Rectangle(), new Rectangle(), gap);
        assertEquals(text, rendered, () -> "Clipped " + text + " in " + component.getSize());
    }
    private static boolean visible(Component component, Container root) {
        for (Component c = component; c != root; c = c.getParent()) if (c == null || !c.isVisible()) return false;
        return true;
    }
    private static List<Component> descendants(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            result.add(child); if (child instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container c) layout(c);
    }
}
