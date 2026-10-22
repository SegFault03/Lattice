package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.IconUtil;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.RowDefaults;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.Objects;

/** Native Swing details and staging surface for the selected table cell. */
final class CellDetailsPanel extends JPanel {
    record Cell(String tableName, int modelRow, int modelColumn, ColumnMetadata column, Object value,
                boolean newRow, boolean modified, boolean editable, boolean view, String validationError,
                String rowLabel, String editabilityHint) {}
    record Column(String tableName, int modelColumn, ColumnMetadata column, boolean view) {}
    interface Actions {
        void apply(Cell cell, String text); void setNull(Cell cell); void useDefault(Cell cell);
        void revert(Cell cell); void editTable(); void close(); void unpin();
    }

    private final Actions actions;
    private final JTextArea title = selectableText();
    private final JLabel eyebrow = new JLabel();
    private final JTextArea identity = selectableText();
    private final JLabel summary = new JLabel();
    private final JLabel valueHeading = new JLabel("Value");
    private final JTextArea valueArea = new JTextArea() {
        @Override public void updateUI() {
            javax.swing.text.Caret previous = getCaret();
            int dot = previous == null ? 0 : previous.getDot();
            int mark = previous == null ? 0 : previous.getMark();
            super.updateUI();
            if (previous != null) SwingUtilities.invokeLater(() -> {
                javax.swing.text.Caret current = getCaret();
                if (current == null || getDocument() == null) return;
                int length = getDocument().getLength();
                current.setDot(Math.min(mark, length));
                current.moveDot(Math.min(dot, length));
            });
        }
    };
    private final JScrollPane valueScroll = new JScrollPane(valueArea);
    private final SqlLineNumbers lineNumbers = new SqlLineNumbers(valueArea);
    private final JPanel header = new JPanel(new GridBagLayout()) {
        @Override public Dimension getPreferredSize() { prepareHeaderWidth(); return super.getPreferredSize(); }
        @Override public void doLayout() { prepareHeaderWidth(); super.doLayout(); }
    };
    private final Body content = new Body();
    private final JScrollPane bodyScroll = new JScrollPane(content);
    private final JPanel lower = new JPanel();
    private JPanel valueBlock;
    private JPanel tableNote;
    private JPanel staging;
    private final JPanel tableNoteText = new JPanel(new BorderLayout());
    private final WrappingLabel tableNoteLabel = new WrappingLabel("Review or change table structure");
    private final JPanel metadata = new JPanel();
    private final JPanel footer = new JPanel(new BorderLayout());
    private final JPanel bottomDock = new JPanel(new BorderLayout());
    private final WrappingLabel footerLabel = new WrappingLabel("");
    private final HeaderIconButton pin = new HeaderIconButton(AllIcons.General.Pin_tab);
    private final HeaderIconButton close = new HeaderIconButton(AllIcons.Actions.CloseDarkGrey);
    private final JToggleButton formattedTab = new JToggleButton("Formatted");
    private final JToggleButton rawTab = new JToggleButton("Raw");
    private final JToggleButton wrap = new JToggleButton("Wrap");
    private final JButton edit = new InspectorButton("Edit value", IconUtil.scale(AllIcons.Actions.Edit, null, 1.5f), ButtonStyle.PRIMARY);
    private final JButton copy = new QuietButton("Copy", IconUtil.scale(AllIcons.Actions.Copy, null, 1.5f));
    private final JButton setNull = new QuietButton("Set NULL", null);
    private final JButton emptyString = new QuietButton("Empty string", null);
    private final JButton useDefault = new QuietButton("Use default", null);
    private final JButton revert = new QuietButton("Revert", AllIcons.Actions.Rollback);
    private final JPanel valueStates = new JPanel(new ActionLayout());
    private final JButton apply = new InspectorButton("Stage change", null, ButtonStyle.PRIMARY);
    private final JButton cancel = new QuietButton("Cancel", null);
    private final JButton editTable = new InspectorButton("Edit table", null, ButtonStyle.SECONDARY);
    private Cell cell;
    private Column column;
    private boolean pinned, columnMode, editing, busy, rawMode;
    private String draft;
    private final WrappingLabel validation = new WrappingLabel("");
    private final JPanel tabs = new JPanel(new ActionLayout());
    private final JPanel controls = new JPanel(new GridLayout(1, 2, JBUI.scale(10), 0));
    private LookAndFeel lastLookAndFeel;
    private boolean structuredValue;

    private static final class Body extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return JBUI.scale(16); }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return visibleRect.height - JBUI.scale(32); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Width is supplied before measuring children, so wrapped text never uses stale heights. */
    private static final class ColumnLayout implements LayoutManager {
        public void addLayoutComponent(String name, Component component) {}
        public void removeLayoutComponent(Component component) {}
        public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets(); int width = Math.max(1, parent.getWidth() - in.left - in.right);
            int height = in.top + in.bottom;
            for (Component child : parent.getComponents()) if (child.isVisible()) {
                child.setSize(width, child.getHeight()); height += child.getPreferredSize().height;
            }
            return new Dimension(width + in.left + in.right, height);
        }
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets(); int y = in.top, width = Math.max(1, parent.getWidth() - in.left - in.right);
            for (Component child : parent.getComponents()) if (child.isVisible()) {
                child.setSize(width, child.getHeight()); int height = child.getPreferredSize().height;
                child.setBounds(in.left, y, width, height); y += height;
            }
        }
    }

    /** Wrapping controls with explicit inter-control gaps and no invisible outer padding. */
    private static final class ActionLayout implements LayoutManager {
        public void addLayoutComponent(String name, Component component) {}
        public void removeLayoutComponent(Component component) {}
        public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
        public Dimension preferredLayoutSize(Container parent) { return arrange(parent, false); }
        public void layoutContainer(Container parent) { arrange(parent, true); }
        private Dimension arrange(Container parent, boolean layout) {
            Insets in = parent.getInsets(); int gap = JBUI.scale(8);
            int width = parent.getWidth() > 0 ? Math.max(1, parent.getWidth() - in.left - in.right) : Integer.MAX_VALUE;
            int x = 0, y = 0, rowHeight = 0, usedWidth = 0;
            for (Component child : parent.getComponents()) if (child.isVisible()) {
                Dimension size = child.getPreferredSize(); int childWidth = Math.min(width, size.width);
                if (x > 0 && x + gap + childWidth > width) { y += rowHeight + gap; x = 0; rowHeight = 0; }
                if (x > 0) x += gap;
                if (layout) child.setBounds(in.left + x, in.top + y, childWidth, size.height);
                x += childWidth; rowHeight = Math.max(rowHeight, size.height); usedWidth = Math.max(usedWidth, x);
            }
            return new Dimension(usedWidth + in.left + in.right, y + rowHeight + in.top + in.bottom);
        }
    }

    /** A stable header rail: controls never move when the column name wraps. */
    private void prepareHeaderWidth() {
        if (title == null || controls == null || header == null || header.getWidth() <= 0) return;
        Insets in = header.getInsets();
        int width = Math.max(1, header.getWidth() - in.left - in.right);
        title.setSize(width, title.getHeight());
        identity.setSize(width, identity.getHeight());
    }

    private static final class MetadataRow extends JPanel {
        private final JLabel key;
        private final JTextArea value = selectableText();
        MetadataRow(String name, String text) {
            super(null); setOpaque(false); setBorder(JBUI.Borders.empty(4, 32, 4, 0));
            key = new JLabel(name); key.putClientProperty("lattice.secondary", Boolean.TRUE);
            key.setForeground(new JBColor(0x657185, 0xABB3BE));
            value.setText(text); value.putClientProperty("lattice.secondary", "—".equals(text)); value.setToolTipText(text); value.getAccessibleContext().setAccessibleName(name);
            add(key); add(value);
        }
        private int valueWidth(int width) { return Math.max(1, width < JBUI.scale(220) || key.getText().isEmpty() ? width : width - JBUI.scale(120)); }
        @Override public Dimension getPreferredSize() {
            Insets in = getInsets(); int width = Math.max(1, getWidth() - in.left - in.right); value.setSize(valueWidth(width), value.getHeight());
            int h = value.getPreferredSize().height;
            if (!key.getText().isEmpty()) h = width < JBUI.scale(220) ? h + key.getPreferredSize().height + JBUI.scale(4)
                    : Math.max(h, key.getPreferredSize().height);
            return new Dimension(width, h + in.top + in.bottom);
        }
        @Override public void doLayout() {
            Insets in = getInsets(); int width = Math.max(1, getWidth() - in.left - in.right); boolean stacked = width < JBUI.scale(220);
            int keyHeight = key.getPreferredSize().height;
            key.setBounds(in.left, in.top, key.getText().isEmpty() ? 0 : stacked ? width : JBUI.scale(104), keyHeight);
            int x = stacked || key.getText().isEmpty() ? 0 : JBUI.scale(120);
            int y = in.top + (stacked && !key.getText().isEmpty() ? keyHeight + JBUI.scale(4) : 0);
            value.setBounds(in.left + x, y, valueWidth(width), Math.max(1, getHeight() - y - in.bottom));
        }
    }

    private static JTextArea selectableText() {
        JTextArea text = new JTextArea(); text.setEditable(false); text.setOpaque(false);
        text.setLineWrap(true); text.setWrapStyleWord(true); text.setBorder(BorderFactory.createEmptyBorder());
        text.setFont(UIManager.getFont("Label.font")); return text;
    }

    private enum ButtonStyle { PRIMARY, SECONDARY, QUIET }

    /** Stable rounded surfaces across Windows/Linux LAFs, using native button semantics. */
    private static class InspectorButton extends JButton {
        private final ButtonStyle style;
        InspectorButton(String text, Icon icon, ButtonStyle style) {
            super(text, icon); this.style = style;
            setBorder(style == ButtonStyle.PRIMARY ? JBUI.Borders.empty(2, 10) : JBUI.Borders.empty(2, 8));
            setIconTextGap(JBUI.scale(6)); setRolloverEnabled(true);
            getModel().addChangeListener(e -> repaint());
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
        }
        @Override public void updateUI() {
            setUI(new BasicButtonUI()); setOpaque(false); setContentAreaFilled(false); setBorderPainted(false);
        }
        @Override public Color getForeground() {
            return style == ButtonStyle.PRIMARY && isEnabled() ? Color.WHITE : super.getForeground();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hover = isEnabled() && (getModel().isRollover() || getModel().isPressed());
            Color surface = style == ButtonStyle.PRIMARY
                    ? !isEnabled() ? new JBColor(0xDCE4F2, 0x343F53)
                    : getModel().isPressed() ? new JBColor(0x124DCE, 0x2054CD)
                    : hover ? new JBColor(0x1B57DF, 0x326DFA) : new JBColor(0x1F62F2, 0x2864F6)
                    : hover ? new JBColor(0xE8ECF2, 0x35383F)
                    : style == ButtonStyle.SECONDARY ? new JBColor(0xFFFFFF, 0x2B2D30) : null;
            int arc = JBUI.scale(10);
            if (surface != null) {
                g.setColor(surface); g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                if (style != ButtonStyle.QUIET) {
                    g.setColor(style == ButtonStyle.PRIMARY ? new JBColor(0x1753D5, 0x3B73FC) : new JBColor(0xD4D9E1, 0x484C54));
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
            }
            if (isFocusOwner()) {
                g.setColor(new JBColor(0x356EDE, 0x8AAFFF)); g.setStroke(new BasicStroke(JBUI.scale(2f)));
                g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc, arc);
            }
            g.dispose(); super.paintComponent(graphics);
        }
        @Override public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, Math.max(JBUI.scale(28), size.height));
        }
    }
    private static final class QuietButton extends InspectorButton {
        QuietButton(String text, Icon icon) { super(text, icon, ButtonStyle.QUIET); }
    }

    /** Paint only the neutral surface; the IDE supplies the standard action glyphs. */
    private static final class HeaderIconButton extends JButton {
        HeaderIconButton(Icon icon) {
            super(icon); setOpaque(false); setContentAreaFilled(false); setBorderPainted(false);
            setRolloverEnabled(true); setFocusable(true); setBorder(JBUI.Borders.empty());
            getModel().addChangeListener(e -> repaint());
        }
        void setActive(boolean active) { setSelected(active); repaint(); }
        @Override public void updateUI() {
            setUI(new BasicButtonUI()); setOpaque(false); setContentAreaFilled(false); setBorderPainted(false);
            setBorder(JBUI.Borders.empty());
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected() || getModel().isRollover() || getModel().isPressed() || isFocusOwner()) {
                g.setColor(getModel().isPressed() ? new JBColor(0xD3D7DF, 0x42454C)
                        : isSelected() ? new JBColor(0xE3E6EC, 0x35383F) : new JBColor(0xE9ECF1, 0x303339));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(6), JBUI.scale(6));
            }
            g.dispose(); super.paintComponent(graphics);
        }
        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(32), JBUI.scale(32)); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
    }

    CellDetailsPanel(Actions actions) {
        this.actions = actions;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(JBUI.scale(388), 400));
        setMinimumSize(new Dimension(JBUI.scale(320), 220));
        // Each region owns its padding. The header has a wider layout area so its
        // right-aligned hover targets stay inside every ancestor's paint bounds.
        eyebrow.setIcon(IconUtil.scale(Icons.TABLE, null, 1.5f)); eyebrow.setIconTextGap(JBUI.scale(6));
        title.getAccessibleContext().setAccessibleName("Inspected column");
        identity.getAccessibleContext().setAccessibleName("Inspected row and column");
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0; labelConstraints.weightx = 1;
        labelConstraints.fill = GridBagConstraints.HORIZONTAL;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.gridy = 0; labelConstraints.gridwidth = 1; labelConstraints.insets = JBUI.emptyInsets();
        header.add(eyebrow, labelConstraints);
        labelConstraints.gridy = 1; labelConstraints.gridwidth = 2; labelConstraints.insets = JBUI.insetsTop(8);
        header.add(title, labelConstraints);
        labelConstraints.gridy = 2; labelConstraints.gridwidth = 2; labelConstraints.insets = JBUI.insetsTop(8);
        header.add(identity, labelConstraints);
        pin.setToolTipText("Pin details"); pin.getAccessibleContext().setAccessibleName("Pin cell details");
        pin.addActionListener(e -> { pinned = !pinned; updatePin(); if (!pinned) actions.unpin(); });
        close.setToolTipText("Close details"); close.getAccessibleContext().setAccessibleName("Close details panel");
        close.addActionListener(e -> { cancelEdit(); actions.close(); });
        controls.setOpaque(false);
        controls.add(pin); controls.add(close);
        GridBagConstraints actionsConstraints = new GridBagConstraints();
        actionsConstraints.gridx = 1; actionsConstraints.gridy = 0;
        actionsConstraints.anchor = GridBagConstraints.NORTHEAST; actionsConstraints.insets = JBUI.insetsLeft(12);
        header.add(controls, actionsConstraints); add(header, BorderLayout.NORTH);

        content.setOpaque(true); content.setLayout(new ColumnLayout());
        bodyScroll.setBorder(JBUI.Borders.empty(0, 24));
        bodyScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        add(bodyScroll, BorderLayout.CENTER);
        identity.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        summary.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        tabs.setOpaque(false);
        styleTextToggle(formattedTab); styleTextToggle(rawTab); styleTextToggle(wrap);
        ButtonGroup presentation = new ButtonGroup(); presentation.add(formattedTab); presentation.add(rawTab);
        formattedTab.setToolTipText("Pretty-print structured content"); rawTab.setToolTipText("Show the stored value without formatting");
        wrap.setToolTipText("Wrap long lines");
        formattedTab.addActionListener(e -> { rawMode = false; if (!editing) renderValue(); updateTabs(); });
        rawTab.addActionListener(e -> { rawMode = true; if (!editing) renderValue(); updateTabs(); });
        wrap.addActionListener(e -> { valueArea.setLineWrap(wrap.isSelected()); valueArea.setWrapStyleWord(wrap.isSelected()); refreshTextToggle(wrap); });
        tabs.add(formattedTab); tabs.add(rawTab); tabs.add(wrap);
        valueArea.setLineWrap(true); valueArea.setWrapStyleWord(true); valueArea.setEditable(false);
        valueArea.getAccessibleContext().setAccessibleName("Cell value");
        valueArea.setBorder(JBUI.Borders.empty(12));
        valueScroll.setRowHeaderView(lineNumbers);
        valueScroll.setPreferredSize(new Dimension(100, JBUI.scale(164)));
        valueScroll.setMinimumSize(new Dimension(50, JBUI.scale(64)));
        JPanel valueLabelRow = new JPanel(new BorderLayout()); valueLabelRow.setOpaque(false);
        valueHeading.putClientProperty("lattice.section", Boolean.TRUE);
        valueLabelRow.add(valueHeading, BorderLayout.WEST); valueLabelRow.add(summary, BorderLayout.EAST);
        valueLabelRow.setBorder(JBUI.Borders.empty(16, 0, 8, 0));
        JPanel valuePresentation = new JPanel(new ColumnLayout()); valuePresentation.setOpaque(false);
        valuePresentation.add(valueLabelRow); valuePresentation.add(tabs);
        JPanel valueCard = new JPanel(new WidthAwareBorderLayout()); valueCard.setOpaque(false);
        valueCard.add(valuePresentation, BorderLayout.NORTH); valueCard.add(valueScroll, BorderLayout.CENTER);
        JPanel actionsRow = new JPanel(new ActionLayout());
        actionsRow.setOpaque(false); actionsRow.setBorder(JBUI.Borders.emptyTop(8));
        copy.setToolTipText("Copy the raw cell value");
        copy.addActionListener(e -> copyText());
        edit.setToolTipText("Edit selected cell value");
        edit.getAccessibleContext().setAccessibleName("Edit selected cell value");
        apply.setToolTipText("Stage this value; Commit saves it to the database");
        apply.getAccessibleContext().setAccessibleName("Stage this value; Commit saves it to the database");
        edit.addActionListener(e -> beginEdit());
        setNull.setToolTipText("Set SQL NULL"); setNull.addActionListener(e -> actions.setNull(cell));
        emptyString.setToolTipText("Set an empty string"); emptyString.addActionListener(e -> actions.apply(cell, ""));
        useDefault.setToolTipText("Use database default"); useDefault.addActionListener(e -> actions.useDefault(cell));
        revert.setToolTipText("Revert this pending value"); revert.addActionListener(e -> actions.revert(cell));
        valueStates.setOpaque(false); valueStates.setBorder(JBUI.Borders.emptyTop(4));
        valueStates.add(setNull); valueStates.add(emptyString); valueStates.add(useDefault); valueStates.add(revert);
        staging = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)); staging.setOpaque(false);
        staging.setAlignmentX(Component.LEFT_ALIGNMENT);
        apply.addActionListener(e -> applyDraft()); cancel.addActionListener(e -> cancelEdit());
        staging.add(apply); staging.add(Box.createHorizontalStrut(JBUI.scale(8))); staging.add(cancel);
        actionsRow.add(staging); actionsRow.add(edit);
        actionsRow.add(copy);
        JPanel actionGroup = new JPanel(new ColumnLayout()); actionGroup.setOpaque(false);
        actionGroup.add(actionsRow); actionGroup.add(valueStates);
        valueBlock = new JPanel(new WidthAwareBorderLayout()); valueBlock.setOpaque(false);
        valueBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        valueBlock.setBorder(JBUI.Borders.emptyBottom(16));
        valueBlock.add(actionGroup, BorderLayout.SOUTH);
        validation.setVisible(false);
        JPanel valueAndFeedback = new JPanel(new WidthAwareBorderLayout(0, JBUI.scale(8)));
        valueAndFeedback.setOpaque(false);
        valueAndFeedback.add(valueCard, BorderLayout.CENTER);
        valueAndFeedback.add(validation, BorderLayout.SOUTH);
        valueBlock.add(valueAndFeedback, BorderLayout.CENTER);
        metadata.setLayout(new ColumnLayout());
        metadata.setOpaque(false);
        metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
        lower.setOpaque(false); lower.setLayout(new ColumnLayout());
        lower.setAlignmentX(Component.LEFT_ALIGNMENT);
        lower.add(metadata);
        tableNote = new JPanel(new ColumnLayout()); tableNote.setOpaque(false);
        tableNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        tableNoteLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tableNoteText.setOpaque(false); tableNoteText.add(tableNoteLabel);
        editTable.setIcon(IconUtil.scale(Icons.TABLE, null, 1.5f));
        editTable.setAlignmentX(Component.LEFT_ALIGNMENT);
        editTable.addActionListener(e -> actions.editTable());
        JPanel editTableRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        editTableRow.setOpaque(false); editTableRow.add(editTable);
        editTableRow.setBorder(JBUI.Borders.emptyTop(8));
        tableNote.add(tableNoteText); tableNote.add(editTableRow);
        tableNote.setBorder(JBUI.Borders.empty(24, 0, 16, 0));
        lower.add(tableNote);
        footer.add(footerLabel, BorderLayout.CENTER);
        bottomDock.add(footer, BorderLayout.SOUTH);
        bottomDock.setBorder(JBUI.Borders.empty(0, 24));
        add(bottomDock, BorderLayout.SOUTH);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape");
        getActionMap().put("escape", new AbstractAction() { public void actionPerformed(ActionEvent e) { handleEscape(); } });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "apply");
        getActionMap().put("apply", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (editing) applyDraft(); } });
        valueArea.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && cell != null && cell.editable()) beginEdit(); } });
        DatabaseUi.onTextChange(valueArea, () -> { if (editing) { summary.setText(valueSummary(valueArea.getText())); updateValueTools(); updateEnabled(); } });
        renderEmpty();
        updatePin();
        lastLookAndFeel = UIManager.getLookAndFeel();
        refreshTheme();
    }

    @Override public void updateUI() {
        super.updateUI();
        if (valueArea != null) SwingUtilities.invokeLater(this::refreshTheme);
    }

    @Override public void doLayout() {
        header.setSize(getWidth(), header.getHeight());
        int contentWidth = Math.max(1, getWidth() - JBUI.scale(48));
        content.setSize(contentWidth, content.getHeight());
        if (!columnMode && valueBlock != null && getHeight() > 0) {
            int fixedHeight = header.getPreferredSize().height + bottomDock.getPreferredSize().height
                    + lower.getPreferredSize().height
                    + valueBlock.getPreferredSize().height
                    - valueScroll.getPreferredSize().height;
            boolean large = valueArea.getLineCount() > 1 || structuredValue;
            int lineHeight = valueArea.getFontMetrics(valueArea.getFont()).getHeight();
            int desired = large ? Math.min(JBUI.scale(320), Math.max(JBUI.scale(144), lineHeight * Math.min(12, valueArea.getLineCount()) + JBUI.scale(24)))
                    : Math.max(JBUI.scale(164), lineHeight * 2 + JBUI.scale(32));
            int editorHeight = Math.max(JBUI.scale(64), Math.min(desired, getHeight() - fixedHeight));
            if (valueScroll.getPreferredSize().height != editorHeight) {
                valueScroll.setPreferredSize(new Dimension(100, editorHeight));
                resizeContentBlocks();
            }
        }
        super.doLayout();
    }

    private static void styleTextToggle(AbstractButton button) {
        button.setOpaque(false); button.setFocusPainted(true);
        button.setMargin(JBUI.insets(2, 6));
    }
    boolean showCell(Cell cell) {
        ensureCurrentTheme();
        boolean same = this.cell != null && cell != null && Objects.equals(this.cell.tableName(), cell.tableName())
                && this.cell.modelRow() == cell.modelRow() && this.cell.modelColumn() == cell.modelColumn();
        // Selection may change while typing; keep the draft attached to its original cell.
        if (!same && editing) return false;
        if (!same) { editing = false; draft = null; }
        this.cell = cell; this.column = null; columnMode = false; rebuild();
        return true;
    }
    boolean showColumn(Column column) {
        ensureCurrentTheme();
        if (editing) return false;
        this.column = column; this.cell = null; columnMode = true; editing = false; draft = null; rebuild();
        return true;
    }
    private void ensureCurrentTheme() {
        if (lastLookAndFeel != UIManager.getLookAndFeel()) {
            lastLookAndFeel = UIManager.getLookAndFeel();
            SwingUtilities.updateComponentTreeUI(this);
            refreshTheme();
        }
    }
    void clearPin() { pinned = false; updatePin(); updateFooter(); }
    boolean isPinned() { return pinned; }
    boolean isColumnMode() { return columnMode; }
    boolean isEditing() { return editing; }
    boolean finishEditing() {
        if (!editing) return true;
        if (!hasDraftChange()) { cancelEdit(); return true; }
        updateFooter(); valueArea.requestFocusInWindow();
        return false;
    }
    boolean hasDraftChange() { return editing && !Objects.equals(draft, valueArea.getText()); }
    String draftText() { return hasDraftChange() ? valueArea.getText() : null; }
    void restoreDraft(Cell cell, String text) { showCell(cell); beginEdit(); valueArea.setText(text); }
    void cancelEditing() { cancelEdit(); }
    int inspectedRow() { return cell == null ? -1 : cell.modelRow(); }
    int inspectedColumn() { return cell != null ? cell.modelColumn() : column == null ? -1 : column.modelColumn(); }
    boolean handleEscape() { cancelEdit(); actions.close(); return true; }
    void setBusy(boolean busy) { this.busy = busy; updateEnabled(); }

    private void rebuild() {
        content.removeAll();
        if (!columnMode) content.add(valueBlock);
        content.add(lower);
        lower.setVisible(true);
        metadata.removeAll();
        if (columnMode && column != null) buildColumn();
        else if (cell != null) buildCell();
        else renderEmpty();
        refreshSectionBorder();
        refreshMetadataColors(metadata);
        updatePin();
        metadata.revalidate(); lower.revalidate(); content.revalidate();
        resizeContentBlocks();
        content.repaint(); updateEnabled();
    }
    private void resizeContentBlocks() {
        if (content != null) content.revalidate();
    }
    private void updateFooter() {
        footerLabel.setText(editing ? "Editing this cell · Stage change or Cancel · Esc cancels and closes"
                : cell != null && cell.validationError() != null && !cell.validationError().isBlank() ? "Invalid value · Fix before committing"
                : cell != null && cell.modified() ? "Pending change · Commit to save to database"
                : pinned ? columnMode ? "Pinned to this column · Esc to close"
                : "Pinned to this cell · Esc to close"
                : columnMode ? "Follows column selection · Esc to close" : "Follows cell selection · Esc to close");
        footer.revalidate(); footer.repaint();
    }
    private void buildCell() {
        valueScroll.setVisible(true); edit.setVisible(true);
        valueBlock.setVisible(true); tableNote.setVisible(true);
        ColumnMetadata c = cell.column();
        structuredValue = CellValuePresentation.isStructured(cell.value());
        eyebrow.setText(safe(cell.tableName()));
        eyebrow.setToolTipText(safe(cell.tableName()));
        title.setText(c == null ? "Cell value" : c.getName());
        title.setToolTipText(title.getText());
        identity.setText("Row " + (cell.modelRow() + 1) + " · Column " + (cell.modelColumn() + 1));
        identity.setToolTipText(identity.getText());
        summary.setText(valueSummary(editing ? valueArea.getText() : cell.value()));
        edit.setText("Edit value"); if (!editing) renderValue();
        if (c != null) {
            addSection("Column");
            addMetadata("SQL type", c.getFormattedType());
            addMetadata("Nullable", c.isNullable() ? "Yes" : "No");
            addMetadata("Default", c.getDefaultValue() == null ? "—" : c.getDefaultValue());
            if (c.isPrimaryKey()) addMetadata("Primary key", "Yes");
            if (c.isForeignKey()) addMetadata("Foreign key", "Yes");
        }
        boolean invalid = cell.validationError() != null && !cell.validationError().isBlank();
        validation.setText(invalid ? cell.validationError() : "");
        validation.setIcon(invalid ? AllIcons.General.Error : null);
        validation.putClientProperty("lattice.error", invalid);
        validation.setForeground(JBColor.namedColor("Label.errorForeground", new JBColor(0xB83232, 0xFF8282)));
        validation.setVisible(invalid);
        valueScroll.putClientProperty("JComponent.outline", invalid ? "error" : null);
        refreshValueBorder();
        if (cell.editabilityHint() != null && !cell.editabilityHint().isBlank()) addMetadata("", cell.editabilityHint());
        editTable.setEnabled(!cell.view());
    }
    private void buildColumn() {
        ColumnMetadata c = column.column();
        eyebrow.setText(safe(column.tableName()));
        eyebrow.setToolTipText(safe(column.tableName()));
        title.setText(c == null ? "Column details" : c.getName());
        title.setToolTipText(title.getText());
        identity.setText("Column " + (column.modelColumn() + 1) + "  ·  Structure");
        identity.setToolTipText(null);
        summary.setText(c == null ? "" : c.getFormattedType());
        valueBlock.setVisible(false); tableNoteText.setVisible(true); tableNote.setVisible(true); editTable.setVisible(true);
        staging.setVisible(false);
        if (c != null) {
            addSection("Definition");
            addMetadata("SQL type", c.getFormattedType());
            addMetadata("Position", Integer.toString(column.modelColumn() + 1));
            addMetadata("Default", c.getDefaultValue() == null ? "—" : c.getDefaultValue());
            if (c.isGenerated() || c.isAutoIncrement()) addMetadata("Generated", c.isAutoIncrement() ? "Auto increment" : "Yes");
            if (c.getColumnSize() > 0 && !c.getFormattedType().contains("(")) addMetadata("Size", Integer.toString(c.getColumnSize()));
            if (c.getDecimalDigits() > 0) addMetadata("Scale", Integer.toString(c.getDecimalDigits()));
            addSection("Constraints");
            addMetadata("Nullable", c.isNullable() ? "Yes" : "No");
            addMetadata("Primary key", c.isPrimaryKey() ? "Yes" : "No");
            addMetadata("Foreign key", c.isForeignKey() ? "Yes" : "No");
        }
        editTable.setEnabled(!column.view());
    }
    private void addSection(String name) {
        JLabel label = new JLabel(name);
        if ("Column".equals(name)) { label.setIcon(IconUtil.scale(Icons.COLUMN, null, 1.5f)); label.setIconTextGap(JBUI.scale(8)); }
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.putClientProperty("lattice.section", Boolean.TRUE);
        label.setFont(bodyFont().deriveFont(Font.BOLD));
        label.setBorder(JBUI.Borders.empty(12, 0, 8, 0));
        metadata.add(label);
    }
    private void renderEmpty() { eyebrow.setText(""); title.setText("Cell details"); identity.setText("Select a cell to inspect its value"); summary.setText(""); valueArea.setText(""); metadata.removeAll(); if (valueBlock != null) valueBlock.setVisible(true); if (tableNote != null) tableNote.setVisible(false); if (staging != null) staging.setVisible(false); }
    private void addMetadata(String name, String value) {
        metadata.add(new MetadataRow(name, safe(value)));
    }
    private void renderValue() {
        valueScroll.setVisible(!columnMode); valueArea.setEditable(editing);
        String text = editing ? draft : rawMode ? CellValuePresentation.raw(cell == null ? null : cell.value())
                : CellValuePresentation.formatted(cell == null ? null : cell.value());
        valueArea.setText(text == null ? "" : text);
        summary.setText(valueSummary(cell == null ? null : cell.value()));
        valueArea.setBackground(new JBColor(0xFFFFFF, 0x1E1F22));
        valueArea.setForeground(JBColor.foreground());
        valueArea.setCaretPosition(0); updateTabs();
    }
    private void updateTabs() { formattedTab.setSelected(!editing && !rawMode); rawTab.setSelected(editing || rawMode); wrap.setSelected(valueArea.getLineWrap()); }
    private void updateValueTools() {
        boolean structured = cell != null && structuredValue;
        String text = valueArea.getText();
        boolean multiline = text.contains("\n");
        formattedTab.setVisible(structured); rawTab.setVisible(structured);
        wrap.setVisible(structured || multiline || text.length() > 80);
        valueScroll.setRowHeaderView(multiline ? lineNumbers : null);
        updateTabs();
        tabs.setVisible(structured || wrap.isVisible());
    }
    private void beginEdit() { if (cell == null || !cell.editable() || busy || editing) return; draft = CellValuePresentation.editable(cell.value()); editing = true; valueArea.setEditable(true); valueArea.setText(draft); summary.setText(valueSummary(draft)); valueArea.requestFocusInWindow(); valueArea.setCaretPosition(valueArea.getDocument().getLength()); updateEnabled(); }
    private void applyDraft() { if (!editing || busy || !hasDraftChange()) return; stageCurrentEdit(); }
    private void stageCurrentEdit() {
        String text = valueArea.getText(); Cell target = cell;
        editing = false; draft = null; valueArea.setEditable(false); updateEnabled();
        if (target != null) actions.apply(target, text);
    }
    private void cancelEdit() { if (!editing) return; editing = false; draft = null; renderValue(); updateEnabled(); }
    private void copyText() { String text = editing ? valueArea.getText() : CellValuePresentation.raw(cell == null ? null : cell.value()); Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }
    private boolean isTextColumn() {
        return cell != null && cell.column() != null && switch (cell.column().getDataType()) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR,
                    java.sql.Types.NCHAR, java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR,
                    java.sql.Types.CLOB, java.sql.Types.NCLOB -> true;
            default -> false;
        };
    }
    private void updateValueStates() {
        boolean available = cell != null && !columnMode && !editing && cell.editable();
        setNull.setVisible(available && cell.column() != null && cell.column().isNullable());
        emptyString.setVisible(available && isTextColumn());
        useDefault.setVisible(available && cell.newRow() && cell.column() != null
                && (cell.column().getDefaultValue() != null || cell.column().isAutoIncrement()));
        revert.setVisible(available && cell.modified());
        for (JButton button : new JButton[]{setNull, emptyString, useDefault, revert}) button.setEnabled(!busy);
        valueStates.setVisible(setNull.isVisible() || emptyString.isVisible() || useDefault.isVisible() || revert.isVisible());
    }
    private void updateEnabled() {
        edit.setEnabled(cell != null && cell.editable() && !busy && !editing);
        edit.setVisible(cell != null && !columnMode && !editing);
        apply.setEnabled(hasDraftChange() && !busy); cancel.setEnabled(editing && !busy);
        updateValueStates();
        formattedTab.setEnabled(!editing); rawTab.setEnabled(!editing);
        staging.setVisible(editing && !columnMode);
        editTable.setEnabled(!busy && (cell != null && !cell.view() || column != null && !column.view()));
        refreshTextToggle(formattedTab); refreshTextToggle(rawTab); refreshTextToggle(wrap);
        updateValueTools();
        if (editing) summary.setText(hasDraftChange() ? valueSummary(valueArea.getText()) : valueSummary(cell.value()) + " · Unchanged");
        close.setToolTipText(editing ? "Cancel edit and close details" : "Close details");
        close.getAccessibleContext().setAccessibleDescription(close.getToolTipText());
        updateFooter();
        resizeContentBlocks(); content.revalidate(); content.repaint();
    }
    private void updatePin() {
        Color glyph = new JBColor(0x657185, 0xADB5C2);
        pin.setIcon(IconUtil.filterIcon(IconUtil.scale(pinned ? IconLoader.getIcon("/icons/inspector-pinned.svg", CellDetailsPanel.class) : AllIcons.General.Pin_tab, null, pinned ? 2f : 1.5f),
                () -> new java.awt.image.RGBImageFilter() {
                    @Override public int filterRGB(int x, int y, int rgb) { return (rgb & 0xFF000000) | (glyph.getRGB() & 0xFFFFFF); }
                }, null));
        close.setIcon(IconUtil.scale(AllIcons.Actions.CloseDarkGrey, null, 1.75f));
        pin.setActive(pinned);
        pin.setToolTipText(pinned ? "Unpin details" : "Pin details");
        pin.getAccessibleContext().setAccessibleName(pinned
                ? columnMode ? "Unpin column details" : "Unpin cell details"
                : columnMode ? "Pin column details" : "Pin cell details");
        updateFooter();
    }
    private void refreshTheme() {
        if (valueArea == null) return;
        lastLookAndFeel = UIManager.getLookAndFeel();
        Color panelBackground = new JBColor(0xF7F8FA, 0x25262A);
        setBackground(panelBackground);
        header.setBackground(panelBackground);
        footer.setBackground(panelBackground);
        bottomDock.setBackground(panelBackground);
        bodyScroll.setBackground(panelBackground);
        content.setBackground(panelBackground);
        valueArea.setBackground(new JBColor(0xFFFFFF, 0x1E1F22));
        valueArea.setForeground(JBColor.foreground());
        valueScroll.getViewport().setBackground(valueArea.getBackground());
        lineNumbers.setBackground(new JBColor(0xF4F6F9, 0x25262A));
        lineNumbers.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE)));
        Color separator = new JBColor(0xD4D9E1, 0x41454D);
        header.setBorder(new javax.swing.border.AbstractBorder() {
            @Override public Insets getBorderInsets(Component component) { return JBUI.insets(12, 24, 17, 16); }
            @Override public Insets getBorderInsets(Component component, Insets insets) {
                insets.set(JBUI.scale(12), JBUI.scale(24), JBUI.scale(17), JBUI.scale(16));
                return insets;
            }
            @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
                graphics.setColor(separator);
                graphics.drawLine(x + JBUI.scale(24), y + height - 1, x + width - JBUI.scale(24) - 1, y + height - 1);
            }
        });
        refreshSectionBorder();
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, separator), JBUI.Borders.empty(12, 0)));
        refreshValueBorder();
        bodyScroll.getViewport().setBackground(content.getBackground());
        footerLabel.setForeground(new JBColor(0x657185, 0xABB3BE));
        refreshMetadataColors(content);
        Font labelFont = bodyFont();
        Font buttonFont = UIManager.getFont("Button.font");
        if (labelFont != null) {
            title.setFont(labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() + JBUI.scale(6f)));
            eyebrow.setFont(labelFont); identity.setFont(labelFont); summary.setFont(labelFont);
            valueHeading.setFont(labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() + JBUI.scale(1f)));
            tableNoteLabel.setFont(labelFont); footerLabel.setFont(labelFont); validation.setFont(labelFont);
            valueArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(labelFont.getSize2D()));
        }
        if (buttonFont != null) for (AbstractButton button : new AbstractButton[]{formattedTab, rawTab, wrap, edit, copy, setNull, emptyString, useDefault, revert, apply, cancel, editTable}) {
            button.setOpaque(false); button.setForeground(JBColor.foreground());
            button.setFont(buttonFont); button.setIconTextGap(JBUI.scale(6));
            if (!(button instanceof InspectorButton)) button.setMargin(JBUI.insets(2, 6));
            sizeControl(button);
        }
        int actionHeight = JBUI.scale(28);
        for (JButton button : new JButton[]{edit, apply, cancel, copy}) actionHeight = Math.max(actionHeight, button.getPreferredSize().height);
        for (JButton button : new JButton[]{edit, apply, cancel, copy})
            button.setPreferredSize(new Dimension(button.getPreferredSize().width, actionHeight));
        title.setForeground(JBColor.foreground());
        summary.setForeground(new JBColor(0x667185, 0xAAB3C1));
        eyebrow.setForeground(new JBColor(0x667185, 0xAAB3C1));
        identity.setForeground(new JBColor(0x667185, 0xAAB3C1));
        tableNoteLabel.setForeground(new JBColor(0x667185, 0xAAB3C1));
        copy.setForeground(JBColor.foreground());
        cancel.setForeground(JBColor.foreground());
        refreshTextToggle(formattedTab); refreshTextToggle(rawTab); refreshTextToggle(wrap);
        edit.setIcon(IconUtil.filterIcon(IconUtil.scale(AllIcons.Actions.Edit, null, 1.5f),
                () -> new java.awt.image.RGBImageFilter() {
                    @Override public int filterRGB(int x, int y, int rgb) { return (rgb & 0xFF000000) | 0xFFFFFF; }
                }, null));
        updatePin();
        resizeContentBlocks();
        revalidate(); repaint();
    }
    private static void sizeControl(AbstractButton button) {
        button.setPreferredSize(null);
        Dimension nativeSize = button.getPreferredSize();
        button.setPreferredSize(new Dimension(nativeSize.width, Math.max(JBUI.scale(28), nativeSize.height)));
    }
    private void refreshValueBorder() {
        Color color = cell != null && cell.validationError() != null && !cell.validationError().isBlank()
                ? JBColor.namedColor("Label.errorForeground", new JBColor(0xB83232, 0xFF8282))
                : new JBColor(0xD4D9E1, 0x41454D);
        valueScroll.setBorder(BorderFactory.createLineBorder(color));
    }
    private void refreshSectionBorder() {
        Color separator = new JBColor(0xD4D9E1, 0x41454D);
        lower.setBorder(columnMode ? JBUI.Borders.empty()
                : BorderFactory.createMatteBorder(1, 0, 0, 0, separator));
    }
    private static void refreshTextToggle(AbstractButton button) {
        button.setContentAreaFilled(false); button.setBorderPainted(false); button.setOpaque(false);
        button.setForeground(button.isSelected() ? new JBColor(0x275DCD, 0x78A9FF) : new JBColor(0x657185, 0xABB3BE));
    }
    private static Font bodyFont() {
        return UIManager.getFont("Label.font");
    }
    private static void refreshMetadataColors(Component component) {
        Font font = bodyFont();
        if (component instanceof JTextArea text && font != null) {
            text.setFont(font); text.setForeground(Boolean.TRUE.equals(text.getClientProperty("lattice.secondary"))
                    ? new JBColor(0x657185, 0xABB3BE) : JBColor.foreground());
        }
        if (component instanceof JLabel label) {
            if (font != null) label.setFont(Boolean.TRUE.equals(label.getClientProperty("lattice.section")) ? font.deriveFont(Font.BOLD) : font);
            label.setForeground(Boolean.TRUE.equals(label.getClientProperty("lattice.error"))
                    ? JBColor.namedColor("Label.errorForeground", new JBColor(0xB83232, 0xFF8282))
                    : Boolean.TRUE.equals(label.getClientProperty("lattice.accent"))
                    ? new JBColor(0x2F65BE, 0xA6C7FF)
                    : Boolean.TRUE.equals(label.getClientProperty("lattice.secondary"))
                    ? new JBColor(0x657185, 0xABB3BE)
                    : JBColor.foreground());
            if (label.getText() != null && label.getText().startsWith("<html>"))
                BasicHTML.updateRenderer(label, label.getText());
        }
        if (component instanceof Container container)
            for (Component child : container.getComponents()) refreshMetadataColors(child);
    }
    private static String valueSummary(Object value) {
        if (value == RowDefaults.Value.USE_DEFAULT) return "Database default";
        if (value == null) return "SQL NULL";
        if (value instanceof String text && text.isEmpty()) return "Empty string · 0 characters";
        if (value instanceof byte[] bytes) return bytes.length + (bytes.length == 1 ? " byte" : " bytes");
        String s = CellValuePresentation.raw(value);
        int characters = s.codePointCount(0, s.length());
        return characters + (characters == 1 ? " character" : " characters");
    }
    private static String safe(String s) { return s == null ? "" : s; }
}
