package com.segfault03.ideadb.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.release.ReleaseNotesContent;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline, native release guide, hosted in a normal Lattice editor tab. */
public final class ReleaseNotesPanel extends JPanel {
    private final Map<String, JComponent> sections = new LinkedHashMap<>();
    private final Stack content = new Stack(28);

    public ReleaseNotesPanel(Project project) {
        super(new BorderLayout());
        content.setBorder(JBUI.Borders.empty(32, 36));
        Stack introduction = new Stack(12);
        JLabel title = new JLabel("What's new in Lattice", Icons.LATTICE_LARGE, SwingConstants.LEFT);
        title.setIconTextGap(JBUI.scale(12));
        title.setFont(font().deriveFont(Font.BOLD, font().getSize2D() + 12));
        introduction.add(title);
        JLabel version = new JLabel("v" + ReleaseNotesContent.version() + "  ·  Changes since v" + ReleaseNotesContent.since());
        version.setForeground(secondary()); introduction.add(version);
        JTextArea summary = text(ReleaseNotesContent.summary());
        summary.setFont(font().deriveFont(font().getSize2D() + 2)); introduction.add(summary);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEADING, JBUI.scale(16), 0));
        navigation.setOpaque(false);
        String[] labels = {"Value inspector", "Alter table", "Connections"};
        var features = ReleaseNotesContent.features();
        for (int i = 0; i < features.size(); i++) {
            String id = features.get(i).id();
            ActionLink link = new ActionLink(labels[i]);
            link.addActionListener(event -> scrollTo(id)); navigation.add(link);
        }
        introduction.add(navigation); content.add(introduction);
        for (var feature : features) {
            FeaturePanel section = new FeaturePanel(feature, project);
            sections.put(feature.id(), section); content.add(section);
        }
        Stack closing = new Stack(14);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, JBUI.scale(12), 0)); actions.setOpaque(false);
        JButton open = new JButton("Open Lattice", Icons.LATTICE);
        open.setEnabled(project != null);
        open.addActionListener(event -> {
            var toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Lattice");
            if (toolWindow != null) toolWindow.activate(null);
        });
        actions.add(open);
        ActionLink changelog = new ActionLink("Full changelog on GitHub");
        changelog.addActionListener(event -> BrowserUtil.browse("https://github.com/SegFault03/Lattice/blob/main/docs/CHANGELOG.md"));
        actions.add(changelog); closing.add(actions);
        JToggleButton more = new JToggleButton("All changes in this release");
        JPanel moreRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)); moreRow.setOpaque(false); moreRow.add(more);
        closing.add(moreRow);
        Stack details = new Stack(8);
        for (String line : ReleaseNotesContent.changelog().split("\\R")) {
            if (line.startsWith("### ")) details.add(heading(line.substring(4), 1));
            else if (line.startsWith("- ")) details.add(text("• " + line.substring(2).replace("`", "")));
        }
        details.setVisible(false); closing.add(details);
        more.addActionListener(event -> { details.setVisible(more.isSelected()); content.revalidate(); });
        content.add(closing);
        JCheckBox notices = new JCheckBox("Notify me about new Lattice releases");
        notices.setOpaque(false); notices.setEnabled(project != null);
        if (project != null) {
            var announcements = com.segfault03.ideadb.release.ReleaseNotesAnnouncement.getInstance();
            notices.setSelected(announcements.isEnabled());
            notices.addActionListener(event -> announcements.setEnabled(notices.isSelected()));
        }
        closing.add(notices);
        ReadingSurface surface = new ReadingSurface(); surface.add(content);
        JBScrollPane scroll = new JBScrollPane(surface);
        scroll.setBorder(JBUI.Borders.empty()); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(24)); add(scroll);
        getAccessibleContext().setAccessibleName("What's new in Lattice " + ReleaseNotesContent.version());
    }

    private void scrollTo(String id) {
        JComponent section = sections.get(id);
        if (section != null) content.scrollRectToVisible(section.getBounds());
    }
    private static Font font() { return UIManager.getFont("Label.font"); }
    private static Color secondary() { return JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE)); }
    private static JLabel heading(String title, int extra) {
        JLabel label = new JLabel(title); label.setFont(font().deriveFont(Font.BOLD, font().getSize2D() + extra)); return label;
    }
    private static JTextArea text(String value) {
        JTextArea area = new JTextArea(value); area.setEditable(false); area.setOpaque(false);
        area.setLineWrap(true); area.setWrapStyleWord(true); area.setFont(font());
        area.setForeground(JBColor.foreground()); area.setBorder(JBUI.Borders.empty()); return area;
    }

    private final class ReadingSurface extends JPanel implements Scrollable {
        ReadingSurface() { super(null); }
        @Override public Dimension getPreferredSize() {
            int width = Math.min(getWidth() > 0 ? getWidth() : JBUI.scale(1000), JBUI.scale(1120));
            content.setSize(width, 1); return new Dimension(width, content.getPreferredSize().height);
        }
        @Override public void doLayout() {
            int width = Math.min(getWidth(), JBUI.scale(1120)); content.setSize(width, 1);
            content.setBounds((getWidth() - width) / 2, 0, width, content.getPreferredSize().height);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(JBUI.scale(1000), JBUI.scale(700)); }
        @Override public int getScrollableUnitIncrement(Rectangle rect, int orientation, int direction) { return JBUI.scale(24); }
        @Override public int getScrollableBlockIncrement(Rectangle rect, int orientation, int direction) { return Math.max(JBUI.scale(24), rect.height - JBUI.scale(24)); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class Stack extends JPanel {
        private final int gap;
        Stack(int gap) { super(null); this.gap = gap; setOpaque(false); }
        @Override public Dimension getPreferredSize() { return arrange(false); }
        @Override public void doLayout() { arrange(true); }
        private Dimension arrange(boolean layout) {
            Insets in = getInsets(); int width = Math.max(1, getWidth() - in.left - in.right), y = in.top;
            boolean first = true;
            for (Component component : getComponents()) if (component.isVisible()) {
                if (!first) y += JBUI.scale(gap); first = false;
                component.setSize(width, 1); int height = component.getPreferredSize().height;
                if (layout) component.setBounds(in.left, y, width, height); y += height;
            }
            return new Dimension(width + in.left + in.right, y + in.bottom);
        }
    }

    private static final class FeaturePanel extends JPanel {
        private final Stack explanation = new Stack(12);
        private final ScreenshotButton screenshot;
        FeaturePanel(ReleaseNotesContent.Feature feature, Project project) {
            super(null); setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(JBUI.Borders.customLineTop(new JBColor(0xD4D9E1, 0x41454D)), JBUI.Borders.emptyTop(24)));
            JTextArea title = text(feature.title());
            title.setFont(font().deriveFont(Font.BOLD, font().getSize2D() + 4));
            explanation.add(title); explanation.add(text(feature.description()));
            for (int i = 0; i < feature.steps().size(); i++) explanation.add(text((i + 1) + ". " + feature.steps().get(i)));
            screenshot = new ScreenshotButton(feature, project); add(explanation); add(screenshot);
        }
        @Override public Dimension getPreferredSize() { return arrange(false); }
        @Override public void doLayout() { arrange(true); }
        private Dimension arrange(boolean layout) {
            Insets in = getInsets(); int width = Math.max(1, getWidth() - in.left - in.right), gap = JBUI.scale(28);
            boolean stacked = width < JBUI.scale(760);
            int textWidth = stacked ? width : Math.min(JBUI.scale(340), width * 2 / 5);
            int imageWidth = stacked ? width : width - textWidth - gap;
            explanation.setSize(textWidth, 1); screenshot.setSize(imageWidth, 1);
            int textHeight = explanation.getPreferredSize().height, imageHeight = screenshot.getPreferredSize().height;
            if (layout) {
                explanation.setBounds(in.left, in.top, textWidth, textHeight);
                screenshot.setBounds(in.left + (stacked ? 0 : textWidth + gap),
                        in.top + (stacked ? textHeight + gap : 0), imageWidth, imageHeight);
            }
            return new Dimension(width + in.left + in.right, in.top + in.bottom +
                    (stacked ? textHeight + gap + imageHeight : Math.max(textHeight, imageHeight)));
        }
    }

    private static final class ScreenshotButton extends JButton {
        private final BufferedImage image;
        ScreenshotButton(ReleaseNotesContent.Feature feature, Project project) {
            try (var input = ReleaseNotesPanel.class.getResourceAsStream(feature.image())) {
                if (input == null) throw new IllegalStateException("Missing screenshot: " + feature.image());
                image = ImageIO.read(input);
            } catch (IOException error) { throw new IllegalStateException("Cannot read screenshot", error); }
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); setToolTipText("Click to enlarge");
            getAccessibleContext().setAccessibleName(feature.alt() + ". Click to enlarge.");
            addActionListener(event -> new DialogWrapper(project) {
                { setTitle(feature.title()); init(); }
                @Override protected JComponent createCenterPanel() {
                    JBScrollPane scroll = new JBScrollPane(new JLabel(new ImageIcon(image)));
                    Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                    scroll.setPreferredSize(new Dimension(Math.min(image.getWidth(), screen.width * 4 / 5),
                            Math.min(image.getHeight(), screen.height * 3 / 4))); return scroll;
                }
                @Override protected Action[] createActions() { return new Action[]{getOKAction()}; }
            }.show());
        }
        @Override public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : JBUI.scale(600);
            return new Dimension(width, Math.max(1, (int)((long)width * image.getHeight() / image.getWidth())));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(image, 1, 1, Math.max(1, getWidth() - 2), Math.max(1, getHeight() - 2), null);
            g.setColor(isFocusOwner() ? new JBColor(0x3574F0, 0x548AF7) : new JBColor(0xD4D9E1, 0x41454D));
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1); g.dispose();
        }
    }
}
