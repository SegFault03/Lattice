package com.segfault03.ideadb.dialog;

import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.ui.Icons;
import com.segfault03.ideadb.ui.WrappingLabel;
import com.segfault03.ideadb.ui.WrapLayout;

import javax.swing.*;
import java.awt.*;

/** Shared header, provider badge and native footer geometry for both table editors. */
final class TableDialogChrome {
    private TableDialogChrome() {}

    static Font font(int style, int delta) {
        Font base = UIManager.getFont("Label.font");
        return base.deriveFont(style, Math.max(10f, base.getSize2D() + delta));
    }
    static Color secondary() { return JBColor.namedColor("Lattice.secondaryForeground", new JBColor(0x6B7078,0xA8ADB5)); }
    static Color border() { return JBColor.namedColor("Component.borderColor", new JBColor(0xD8DBE0,0x505358)); }
    static JLabel muted(String text) {
        JLabel label = new JLabel(text); label.setFont(font(Font.PLAIN,-1)); label.setForeground(secondary()); return label;
    }

    static JComponent header(ConnectionConfig config, String namespace, String text) {
        JPanel header = transparent(new BorderLayout(JBUI.scale(20),0));
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,border()),
                JBUI.Borders.empty(0,4,10,4)));
        JPanel identity = TableDialogLayout.column();
        JLabel title = new JLabel(text); title.setFont(font(Font.BOLD,5)); title.setToolTipText(text);
        title.getAccessibleContext().setAccessibleName("Table dialog title");
        JPanel titleRow = transparent(new BorderLayout(JBUI.scale(8),0));
        titleRow.add(new IconSlot(Icons.TABLE,16,"Table dialog icon"),BorderLayout.WEST);
        titleRow.add(title,BorderLayout.CENTER);
        identity.add(titleRow); identity.add(Box.createVerticalStrut(JBUI.scale(4)));
        JLabel context = new WrappingLabel(config.getName()+"   ›   "+namespace);
        context.setFont(font(Font.PLAIN,-1)); context.setForeground(secondary()); identity.add(context);
        header.add(identity,BorderLayout.CENTER);
        JPanel provider = transparent(new BorderLayout(JBUI.scale(8),0));
        provider.getAccessibleContext().setAccessibleName("Table database badge");
        provider.add(new IconSlot(Icons.dataSource(config.getType(),24),24,"Table database icon"),BorderLayout.WEST);
        JLabel engine = muted(config.getType().getDisplayName());
        engine.setToolTipText(config.getType().getDisplayName());
        engine.getAccessibleContext().setAccessibleName("Table database engine");
        provider.add(engine,BorderLayout.CENTER); header.add(provider,BorderLayout.EAST);
        return header;
    }

    static JPanel buttons(JButton secondary, JButton primary) { return new ButtonBar(secondary,primary); }
    static JPanel inline(Component... children) { return new Inline(children); }
    static JPanel constraints(JCheckBox... controls) {
        JPanel panel=transparent(new WrapLayout(FlowLayout.LEFT,0,JBUI.scale(4)));
        for(int i=0;i<controls.length;i++) {
            controls[i].setBorder(JBUI.Borders.emptyRight(i+1<controls.length?12:0));
            panel.add(controls[i]);
        }
        return panel;
    }
    private static JPanel transparent(LayoutManager layout) { JPanel p=new JPanel(layout);p.setOpaque(false);return p; }

    /** The reserved SVG space never depends on a lazy icon's initial reported size. */
    private static final class IconSlot extends JLabel {
        private final int size;
        IconSlot(Icon icon,int size,String name) { super(icon);this.size=size;getAccessibleContext().setAccessibleName(name);setOpaque(false); }
        @Override public Dimension getPreferredSize() { return JBUI.size(size,size); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics graphics) {
            Icon icon=getIcon();if(icon==null)return;
            Icon resolved=IconLoader.getIconSnapshot(icon);
            if(resolved!=null) resolved.paintIcon(this,graphics,0,Math.max(0,(getHeight()-JBUI.scale(size))/2));
        }
    }

    private static final class ButtonBar extends JPanel {
        private final JButton secondary,primary;
        ButtonBar(JButton secondary,JButton primary) {
            super(null);setOpaque(false);this.secondary=secondary;this.primary=primary;
            getAccessibleContext().setAccessibleName("Table dialog actions");add(secondary);add(primary);
        }
        @Override public Dimension getPreferredSize() {
            Dimension a=secondary.getPreferredSize(),b=primary.getPreferredSize();
            return new Dimension(a.width+JBUI.scale(8)+b.width,Math.max(a.height,b.height));
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public void doLayout() {
            Dimension a=secondary.getPreferredSize(),b=primary.getPreferredSize();
            int height=Math.max(a.height,b.height),y=Math.max(0,(getHeight()-height)/2);
            secondary.setBounds(0,y,a.width,height);primary.setBounds(a.width+JBUI.scale(8),y,b.width,height);
        }
    }

    /** Adjacent captions share a baseline with no FlowLayout padding at either edge. */
    private static final class Inline extends JPanel {
        Inline(Component... children){super(null);setOpaque(false);for(Component child:children)add(child);}
        @Override public Dimension getPreferredSize() {
            int width=0,height=0,count=0,ascent=0,descent=0;
            for(Component child:getComponents())if(child.isVisible()){
                Dimension size=child.getPreferredSize();width+=size.width;height=Math.max(height,size.height);count++;
                int baseline=child.getBaseline(size.width,size.height);
                if(baseline>=0){ascent=Math.max(ascent,baseline);descent=Math.max(descent,size.height-baseline);}
            }
            return new Dimension(width+Math.max(0,count-1)*JBUI.scale(8),Math.max(height,ascent+descent));
        }
        @Override public void doLayout() {
            int x=0,baseline=0;
            for(Component child:getComponents())if(child.isVisible()){
                Dimension size=child.getPreferredSize();baseline=Math.max(baseline,child.getBaseline(size.width,size.height));
            }
            for(Component child:getComponents())if(child.isVisible()){
                Dimension size=child.getPreferredSize();
                int width=Math.min(size.width,Math.max(0,getWidth()-x));
                int childBaseline=child.getBaseline(width,size.height);
                int y=childBaseline<0?Math.max(0,(getHeight()-size.height)/2):Math.max(0,baseline-childBaseline);
                child.setBounds(x,y,width,size.height);x+=width+JBUI.scale(8);
            }
        }
    }
}
