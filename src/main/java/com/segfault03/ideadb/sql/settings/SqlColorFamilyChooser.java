package com.segfault03.ideadb.sql.settings;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.segfault03.ideadb.sql.settings.SqlEditorSettings.ColorFamily;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The choice of color family for table, view and alias names, in two columns with a divider between them: the families,
 * each with a line saying what it looks like, on the left; on the right the same SQL on a light and on a dark editor,
 * colored in the family chosen, so both kinds of theme are seen before choosing.
 */
public final class SqlColorFamilyChooser extends JPanel {
    private static final Map<ColorFamily, String> DESCRIPTIONS = new EnumMap<>(Map.of(
            ColorFamily.CORAL, "Warm red-pink; stands apart from every column color",
            ColorFamily.PERIWINKLE, "Soft blue-violet",
            ColorFamily.LIME, "Yellow-green; close to the green of text columns",
            ColorFamily.NEUTRAL, "The editor's text color: bold names, faded aliases; follows any theme"));

    private final Map<ColorFamily, JBRadioButton> radios = new EnumMap<>(ColorFamily.class);
    private final List<Runnable> listeners = new ArrayList<>();
    private final JPanel light = new JPanel(new java.awt.BorderLayout());
    private final JPanel dark = new JPanel(new java.awt.BorderLayout());
    private ColorFamily selected;

    public SqlColorFamilyChooser(@NotNull ColorFamily initial) {
        super(new GridBagLayout());
        setOpaque(false);
        selected = initial;
        add(options(), column(0, 0.0, GridBagConstraints.NORTHWEST, JBUI.insets(0, 0, 0, 20)));
        add(divider(), column(1, 0.0, GridBagConstraints.CENTER, JBUI.emptyInsets()));
        add(previews(), column(2, 1.0, GridBagConstraints.NORTHWEST, JBUI.insets(0, 20, 0, 0)));
        select(initial);
    }

    private static GridBagConstraints column(int x, double weight, int anchor, java.awt.Insets insets) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.weightx = weight;
        constraints.weighty = 1.0;
        constraints.anchor = anchor;
        constraints.fill = x == 1 ? GridBagConstraints.VERTICAL : GridBagConstraints.NONE;
        constraints.insets = insets;
        return constraints;
    }

    private JPanel options() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
        ButtonGroup group = new ButtonGroup();
        for (ColorFamily family : ColorFamily.values()) {
            JBRadioButton radio = new JBRadioButton(family.toString());
            radio.setOpaque(false);
            radio.setAlignmentX(Component.LEFT_ALIGNMENT);
            radio.addActionListener(event -> select(family));
            group.add(radio);
            radios.put(family, radio);
            JBLabel description = new JBLabel(DESCRIPTIONS.get(family));
            description.setComponentStyle(UIUtil.ComponentStyle.SMALL);
            description.setForeground(UIUtil.getContextHelpForeground());
            description.setBorder(JBUI.Borders.emptyLeft(22));
            description.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(radio);
            column.add(description);
            column.add(Box.createVerticalStrut(JBUI.scale(10)));
        }
        return column;
    }

    private static JPanel divider() {
        JPanel line = new JPanel();
        line.setBackground(JBColor.border());
        line.setPreferredSize(new Dimension(1, 1));
        line.setMinimumSize(new Dimension(1, 1));
        return line;
    }

    private JPanel previews() {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
        column.add(caption("Light editor"));
        column.add(light);
        column.add(Box.createVerticalStrut(JBUI.scale(10)));
        column.add(caption("Dark editor"));
        column.add(dark);
        return column;
    }

    private static JBLabel caption(String text) {
        JBLabel label = new JBLabel(text);
        label.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setBorder(JBUI.Borders.emptyBottom(3));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    public @NotNull ColorFamily selected() { return selected; }

    /** Chooses a family and shows it in both previews. */
    public void select(@NotNull ColorFamily family) {
        boolean changed = family != selected;
        selected = family;
        radios.get(family).setSelected(true);
        show(light, SqlColorFamilyPreview.create(family, false));
        show(dark, SqlColorFamilyPreview.create(family, true));
        if (changed) for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    /** Runs when the user, or {@link #select}, changes the family. */
    public void addChangeListener(@NotNull Runnable listener) { listeners.add(listener); }

    private static void show(JPanel holder, JTextPane preview) {
        preview.setAlignmentX(Component.LEFT_ALIGNMENT);
        holder.removeAll();
        holder.add(preview, java.awt.BorderLayout.CENTER);
        holder.setAlignmentX(Component.LEFT_ALIGNMENT);
        holder.revalidate();
        holder.repaint();
    }

    /** The two preview panes, light first. */
    @NotNull List<JTextPane> previewPanes() {
        List<JTextPane> panes = new ArrayList<>();
        for (JPanel holder : List.of(light, dark)) panes.add((JTextPane) holder.getComponent(0));
        return panes;
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (JBRadioButton radio : radios.values()) radio.setEnabled(enabled);
    }
}
