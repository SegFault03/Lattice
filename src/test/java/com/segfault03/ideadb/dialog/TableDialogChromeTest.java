package com.segfault03.ideadb.dialog;

import com.segfault03.ideadb.model.*;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableDialogChromeTest {
    @Test void loadingSourceArtworkCannotTakeSpaceFromTheEngineName() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (DatabaseType engine : DatabaseType.values()) for (JPanel panel : panels(engine)) {
                JLabel name = label(panel, "Table database engine");
                JLabel artwork = descendants(panel).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .filter(c -> "Table database icon".equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElse(name);
                var lazyIcon = new LateIcon(); artwork.setIcon(lazyIcon);
                panel.setSize(panel.getPreferredSize()); layout(panel);
                // Platform SVGs may resolve after the parent has already allocated its EAST region.
                // A later icon resolution must not steal the text's existing width.
                lazyIcon.loaded = true;
                paint(panel);
                assertComplete(name);
            }
        });
    }

    @Test void footerButtonsHaveIdenticalHeightTopAndTextBaseline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (JPanel panel : panels(DatabaseType.HSQLDB)) {
                JButton primary = panel instanceof CreateTablePanel create ? create.createButton() : ((AlterTablePanel)panel).applyButton();
                JButton secondary = panel instanceof CreateTablePanel create ? create.cancelButton() : ((AlterTablePanel)panel).closeButton();
                for (float size : new float[]{13,16.25f,19.5f}) {
                    for (JButton button : List.of(primary,secondary)) button.setFont(button.getFont().deriveFont(size));
                    for (boolean enabled : new boolean[]{false,true}) {
                        primary.setEnabled(enabled);
                        panel.setSize(panel.getPreferredSize()); for (int i=0;i<3;i++) layout(panel);
                        assertEquals(primary.getHeight(),secondary.getHeight(),"Footer button heights differ");
                        assertEquals(primary.getY(),secondary.getY(),"Footer button top edges differ");
                        int a=primary.getBaseline(primary.getWidth(),primary.getHeight());
                        int b=secondary.getBaseline(secondary.getWidth(),secondary.getHeight());
                        if(a>=0 && b>=0) assertEquals(a,b,"Footer text baselines differ");
                        assertTrue(primary.getWidth()>=primary.getUI().getPreferredSize(primary).width);
                        assertTrue(secondary.getWidth()>=secondary.getUI().getPreferredSize(secondary).width);
                    }
                }
            }
        });
    }

    @Test void inspectorAndTableMetadataShareTheirIntendedEdgesAndBaselines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var config=new ConnectionConfig(DatabaseType.HSQLDB,"Local development");
            var create=new CreateTablePanel(config,"PUBLIC");
            var alter=new AlterTablePanel(config,"PUBLIC","WARMUP_ORDERS",List.of(
                    new ColumnMetadata("QUANTITY","INTEGER",java.sql.Types.INTEGER,32,0,false,false,false,null)));
            for(JPanel panel:List.of(create,alter)) {
                panel.setSize(panel.getPreferredSize());for(int i=0;i<4;i++)layout(panel);
                JTextField name=descendants(panel).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(c->"Column name".equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                JLabel heading=label(panel,"Selected column heading");
                assertEquals(SwingUtilities.convertPoint(name,0,0,panel).x,SwingUtilities.convertPoint(heading,0,0,panel).x);
                JCheckBox nullable=descendants(panel).stream().filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
                        .filter(c->c.getText().equals("Allow NULL values")).findFirst().orElseThrow();
                assertEquals(SwingUtilities.convertPoint(name,0,0,panel).x,SwingUtilities.convertPoint(nullable,0,0,panel).x);
            }
            var input=create.tableNameField();int baseline=SwingUtilities.convertPoint(input,0,input.getBaseline(input.getWidth(),input.getHeight()),create).y;
            for(String caption:List.of("Table column count","Table primary key count")) {
                JLabel label=label(create,caption);
                assertEquals(baseline,SwingUtilities.convertPoint(label,0,label.getBaseline(label.getWidth(),label.getHeight()),create).y);
            }
        });
    }

    @Test void largerThemeFontsKeepTheSourceVisibleAndListRowsTallEnough() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String[] keys={"Label.font","Button.font","TextField.font","ComboBox.font","CheckBox.font","List.font"};
            Map<String,Object> original=new java.util.HashMap<>();for(String key:keys)original.put(key,UIManager.get(key));
            try {
                for(int size:new int[]{13,16,20}) {
                    for(String key:keys)UIManager.put(key,new javax.swing.plaf.FontUIResource(Font.DIALOG,Font.PLAIN,size));
                    for(JPanel panel:panels(DatabaseType.POSTGRESQL)) {
                        panel.setSize(panel.getMinimumSize());for(int i=0;i<4;i++)layout(panel);
                        assertComplete(label(panel,"Table database engine"));
                        if(panel instanceof CreateTablePanel create) {
                            JList<CreateTablePanel.DraftColumn> list=create.columnList();
                            Component row=list.getCellRenderer().getListCellRendererComponent(list,list.getModel().getElementAt(0),0,true,false);
                            row.setSize(210,row.getPreferredSize().height);layout((Container)row);
                            for(Component child:descendants((Container)row)) if(child instanceof JLabel text && text.getText()!=null && !text.getText().isEmpty()) {
                                assertTrue(text.getHeight()>=text.getFontMetrics(text.getFont()).getHeight(),"Column row clipped vertically");
                            }
                        }
                    }
                }
            } finally {original.forEach(UIManager::put);}
        });
    }

    @Test void nativePrimarySurfacesDoNotPaintOutsideTheirRoundedBounds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for(JPanel panel:panels(DatabaseType.HSQLDB)) {
                JButton button=panel instanceof CreateTablePanel create?create.createButton():((AlterTablePanel)panel).applyButton();
                for(boolean enabled:new boolean[]{false,true}) {
                    button.setEnabled(enabled);assertFalse(button.isOpaque());
                    button.updateUI();assertFalse(button.isOpaque(),"Theme changes must keep the native surface transparent outside its border");
                }
            }
        });
    }

    @Test void styledSqlForegroundFollowsThemeChangesWithoutEditingTheDefinition() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object original=UIManager.get("Label.foreground");
            try {
                UIManager.put("Label.foreground",Color.BLACK);
                var config=new ConnectionConfig(DatabaseType.HSQLDB,"Local development");
                var create=new CreateTablePanel(config,"PUBLIC");
                var alter=new AlterTablePanel(config,"PUBLIC","WARMUP_ORDERS",List.of(
                        new ColumnMetadata("id","INTEGER",java.sql.Types.INTEGER,32,0,false,false,false,null)));
                JTextField input=descendants(alter).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(c->"Column name".equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
                input.setText("id2");alter.showPreview(alter.batch(),List.of("ALTER TABLE \"WARMUP_ORDERS\" RENAME COLUMN \"id\" TO \"id2\";"));
                for(JPanel panel:List.of(create,alter)) {
                    JTextPane sql=descendants(panel).stream().filter(JTextPane.class::isInstance).map(JTextPane.class::cast).findFirst().orElseThrow();
                    Color foreground=javax.swing.text.StyleConstants.getForeground(sql.getStyledDocument().getCharacterElement(sql.getText().indexOf('"')).getAttributes());
                    UIManager.put("Label.foreground",Color.WHITE);
                    assertEquals(Color.WHITE.getRGB(),foreground.getRGB(),"SQL kept a light-theme foreground in the dark theme");
                    UIManager.put("Label.foreground",Color.BLACK);assertEquals(Color.BLACK.getRGB(),foreground.getRGB());
                }
            } finally {UIManager.put("Label.foreground",original);}
        });
    }

    private static List<JPanel> panels(DatabaseType engine) {
        var config = new ConnectionConfig(engine,"Local development");
        return List.of(new CreateTablePanel(config,"PUBLIC"),new AlterTablePanel(config,"PUBLIC","WARMUP_ORDERS",List.of()));
    }
    private static JLabel label(Container panel,String name) {
        return descendants(panel).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                .filter(c->name.equals(c.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
    }
    private static void assertComplete(JLabel label) {
        Insets insets=label.getInsets();
        String painted=SwingUtilities.layoutCompoundLabel(label,label.getFontMetrics(label.getFont()),label.getText(),label.getIcon(),
                label.getVerticalAlignment(),label.getHorizontalAlignment(),label.getVerticalTextPosition(),label.getHorizontalTextPosition(),
                new Rectangle(0,0,label.getWidth()-insets.left-insets.right,label.getHeight()-insets.top-insets.bottom),
                new Rectangle(),new Rectangle(),label.getIconTextGap());
        assertEquals(label.getText(),painted,"Engine name clipped after artwork resolution");
    }
    private static void paint(JComponent panel) {
        var image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics=image.createGraphics();try{panel.paint(graphics);}finally{graphics.dispose();}
    }
    private static List<Component> descendants(Container parent) {
        List<Component> result=new ArrayList<>();for(Component c:parent.getComponents()) {
            result.add(c);if(c instanceof Container child)result.addAll(descendants(child));
        }return result;
    }
    private static void layout(Container p){p.doLayout();for(Component c:p.getComponents())if(c instanceof Container child)layout(child);}
    private static final class LateIcon implements Icon {
        boolean loaded;
        public int getIconWidth(){return loaded?24:0;}
        public int getIconHeight(){return loaded?24:0;}
        public void paintIcon(Component c,Graphics g,int x,int y){loaded=true;g.setColor(Color.MAGENTA);g.fillRect(x,y,24,24);}
    }
}
