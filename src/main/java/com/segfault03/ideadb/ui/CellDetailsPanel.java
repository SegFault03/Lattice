package com.segfault03.ideadb.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.segfault03.ideadb.model.ColumnMetadata;
import com.segfault03.ideadb.model.RowDefaults;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.Path2D;
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
    private final JLabel title = new JLabel();
    private final JLabel eyebrow = new JLabel();
    private final JLabel identity = new JLabel();
    private final JLabel summary = new JLabel();
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
    private final JPanel header = new JPanel(new BorderLayout(12, 0));
    private final JPanel heading = new JPanel();
    private final Body content = new Body();
    private final JScrollPane bodyScroll = new JScrollPane(content);
    private final JPanel lower = new JPanel();
    private JPanel valueBlock;
    private JPanel tableNote;
    private JPanel staging;
    private final JPanel tableNoteText = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    private final JLabel tableNoteLabel = new JLabel("Review or change table structure");
    private final JPanel metadata = new JPanel();
    private final JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    private final JPanel bottomDock = new JPanel(new BorderLayout());
    private final JLabel footerLabel = new JLabel();
    private final HeaderIconButton pin = new HeaderIconButton(new HeaderGlyph(true, false));
    private final HeaderIconButton close = new HeaderIconButton(new HeaderGlyph(false, false));
    private final JButton formattedTab = new JButton("Formatted");
    private final JButton rawTab = new JButton("Raw");
    private final JToggleButton wrap = new JToggleButton("Wrap");
    private final JButton edit = DatabaseUi.dialogAction("Edit value", AllIcons.Actions.Edit, "Edit selected cell value");
    private final JButton copy = new JButton("Copy", AllIcons.Actions.Copy);
    private final JButton more = new JButton("More");
    private final JButton apply = DatabaseUi.dialogAction("Stage change", null, "Stage this value; Commit saves it to the database");
    private final JButton cancel = new JButton("Cancel");
    private final JButton editTable = new JButton("Edit table");
    private Cell cell;
    private Column column;
    private boolean pinned, columnMode, editing, busy, rawMode;
    private String draft;
    private final WrappingLabel validation = new WrappingLabel("");
    private LookAndFeel lastLookAndFeel;

    private static final class Body extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return JBUI.scale(16); }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return visibleRect.height - JBUI.scale(32); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** A consistent hit target and restrained state surface for the inspector header. */
    private static final class HeaderIconButton extends JButton {
        private boolean active;
        HeaderIconButton(Icon icon) {
            super(icon);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setMargin(new Insets(0, 0, 0, 0));
        }
        void setActive(boolean active) { this.active = active; repaint(); }
        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(34), JBUI.scale(34)); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(active ? new JBColor(0xE5EEFE, 0x344760)
                        : getModel().isRollover() || getModel().isPressed()
                        ? new JBColor(0xE5EAF2, 0x3B3E46) : new JBColor(0xF0F2F6, 0x2E3035));
                canvas.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, JBUI.scale(9), JBUI.scale(9));
                canvas.setColor(active ? new JBColor(0xBBD0F6, 0x526E96) : new JBColor(0xDCE1E9, 0x41444C));
                canvas.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, JBUI.scale(9), JBUI.scale(9));
                if (isFocusOwner()) {
                    canvas.setColor(new JBColor(0x2F6DDB, 0x70A5FF));
                    canvas.setStroke(new BasicStroke(JBUI.scale(1.5f)));
                    canvas.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, JBUI.scale(9), JBUI.scale(9));
                }
            } finally { canvas.dispose(); }
            super.paintComponent(graphics);
        }
    }

    private record HeaderGlyph(boolean pin, boolean selected) implements Icon {
        @Override public int getIconWidth() { return JBUI.scale(18); }
        @Override public int getIconHeight() { return JBUI.scale(18); }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.translate(x, y);
                canvas.scale(getIconWidth() / 18.0, getIconHeight() / 18.0);
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                canvas.setColor(selected ? new JBColor(0x2F6DDB, 0x83B0FF) : new JBColor(0x5F6A7C, 0xBAC3D1));
                if (pin) {
                    if (!selected) canvas.rotate(Math.PI / 6, 9, 9);
                    Path2D head = new Path2D.Double();
                    head.moveTo(6, 2.5); head.lineTo(12, 2.5); head.lineTo(12, 7);
                    head.lineTo(14.5, 10); head.lineTo(3.5, 10); head.lineTo(6, 7); head.closePath();
                    if (selected) canvas.fill(head); else canvas.draw(head);
                    canvas.drawLine(9, 10, 9, 16);
                } else {
                    canvas.drawLine(4, 4, 14, 14);
                    canvas.drawLine(14, 4, 4, 14);
                }
            } finally { canvas.dispose(); }
        }
    }

    CellDetailsPanel(Actions actions) {
        this.actions = actions;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(JBUI.scale(520), 400));
        setMinimumSize(new Dimension(JBUI.scale(340), 220));
        setBorder(JBUI.Borders.empty(0, 24));
        heading.setOpaque(false);
        heading.setLayout(new GridBagLayout());
        eyebrow.setIcon(Icons.TABLE); eyebrow.setIconTextGap(JBUI.scale(7));
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0; labelConstraints.weightx = 1;
        labelConstraints.fill = GridBagConstraints.HORIZONTAL;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.gridy = 0; labelConstraints.insets = JBUI.insetsBottom(5);
        heading.add(eyebrow, labelConstraints);
        labelConstraints.gridy = 1; labelConstraints.insets = JBUI.insetsBottom(3);
        heading.add(title, labelConstraints);
        labelConstraints.gridy = 2; labelConstraints.insets = JBUI.emptyInsets();
        heading.add(identity, labelConstraints);
        header.add(heading, BorderLayout.CENTER);
        pin.setToolTipText("Pin details"); pin.getAccessibleContext().setAccessibleName("Pin cell details");
        pin.addActionListener(e -> { pinned = !pinned; updatePin(); if (!pinned) actions.unpin(); });
        close.setToolTipText("Close details"); close.getAccessibleContext().setAccessibleName("Close details panel");
        close.addActionListener(e -> { cancelEdit(); actions.close(); });
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
        controls.setOpaque(false);
        controls.setBorder(JBUI.Borders.emptyTop(16));
        controls.add(pin); controls.add(Box.createHorizontalStrut(JBUI.scale(6))); controls.add(close);
        header.add(controls, BorderLayout.EAST); add(header, BorderLayout.NORTH);

        content.setOpaque(true); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        bodyScroll.setBorder(BorderFactory.createEmptyBorder());
        bodyScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        add(bodyScroll, BorderLayout.CENTER);
        identity.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        summary.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)); tabs.setOpaque(false);
        styleTextToggle(formattedTab); styleTextToggle(rawTab); styleTextToggle(wrap);
        formattedTab.addActionListener(e -> { rawMode = false; if (!editing) renderValue(); updateTabs(); });
        rawTab.addActionListener(e -> { rawMode = true; if (!editing) renderValue(); updateTabs(); });
        wrap.addActionListener(e -> { valueArea.setLineWrap(wrap.isSelected()); valueArea.setWrapStyleWord(wrap.isSelected()); });
        tabs.add(formattedTab); tabs.add(Box.createHorizontalStrut(JBUI.scale(16)));
        tabs.add(rawTab); tabs.add(Box.createHorizontalStrut(JBUI.scale(16))); tabs.add(wrap);
        valueArea.setLineWrap(true); valueArea.setWrapStyleWord(true); valueArea.setEditable(false);
        valueArea.getAccessibleContext().setAccessibleName("Cell value");
        valueArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(18)));
        valueArea.setBorder(JBUI.Borders.empty(16));
        valueScroll.setRowHeaderView(lineNumbers);
        valueScroll.setPreferredSize(new Dimension(100, JBUI.scale(384)));
        valueScroll.setMinimumSize(new Dimension(50, JBUI.scale(160)));
        JPanel tabLine = new JPanel(new BorderLayout()); tabLine.setOpaque(false);
        tabLine.setBorder(JBUI.Borders.empty(16, 0, 8, 0));
        tabLine.add(tabs, BorderLayout.WEST); tabLine.add(summary, BorderLayout.EAST);
        JPanel valueCard = new JPanel(new BorderLayout()); valueCard.setOpaque(false);
        valueCard.add(tabLine, BorderLayout.NORTH); valueCard.add(valueScroll, BorderLayout.CENTER);
        JPanel actionsRow = new JPanel(new WrapLayout(FlowLayout.LEADING, 0, JBUI.scale(4)));
        actionsRow.setOpaque(false); actionsRow.setBorder(JBUI.Borders.emptyTop(8));
        quiet(copy); copy.setToolTipText("Copy the raw cell value");
        copy.addActionListener(e -> copyText());
        quiet(more);
        edit.setMargin(JBUI.insets(6, 16));
        apply.setMargin(JBUI.insets(6, 16));
        edit.addActionListener(e -> beginEdit());
        more.addActionListener(e -> showMore(more));
        more.setIcon(AllIcons.Actions.MoreHorizontal); more.setIconTextGap(JBUI.scale(5));
        staging = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)); staging.setOpaque(false);
        staging.setAlignmentX(Component.LEFT_ALIGNMENT);
        quiet(cancel);
        apply.addActionListener(e -> applyDraft()); cancel.addActionListener(e -> cancelEdit());
        staging.add(apply); staging.add(Box.createHorizontalStrut(JBUI.scale(8))); staging.add(cancel);
        actionsRow.add(staging); actionsRow.add(edit);
        actionsRow.add(Box.createHorizontalStrut(JBUI.scale(16))); actionsRow.add(copy);
        actionsRow.add(Box.createHorizontalStrut(JBUI.scale(16))); actionsRow.add(more);
        valueBlock = new JPanel(new BorderLayout(0, 4)); valueBlock.setOpaque(false);
        valueBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        valueBlock.setBorder(JBUI.Borders.emptyBottom(12));
        valueBlock.add(valueCard, BorderLayout.CENTER);
        valueBlock.add(actionsRow, BorderLayout.SOUTH);
        validation.setVisible(false);
        JPanel valueAndFeedback = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        valueAndFeedback.setOpaque(false);
        valueAndFeedback.add(valueCard, BorderLayout.CENTER);
        valueAndFeedback.add(validation, BorderLayout.SOUTH);
        valueBlock.add(valueAndFeedback, BorderLayout.CENTER);
        metadata.setLayout(new BoxLayout(metadata, BoxLayout.Y_AXIS));
        metadata.setOpaque(false);
        metadata.setAlignmentX(Component.LEFT_ALIGNMENT);
        lower.setOpaque(false); lower.setLayout(new BoxLayout(lower, BoxLayout.Y_AXIS));
        lower.setAlignmentX(Component.LEFT_ALIGNMENT);
        lower.add(metadata);
        tableNote = new JPanel(); tableNote.setOpaque(false); tableNote.setLayout(new BoxLayout(tableNote, BoxLayout.Y_AXIS));
        tableNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        tableNoteLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tableNoteText.setOpaque(false); tableNoteText.add(tableNoteLabel);
        editTable.setIcon(Icons.TABLE);
        editTable.setMargin(new Insets(3, 8, 3, 8)); editTable.setAlignmentX(Component.LEFT_ALIGNMENT);
        editTable.addActionListener(e -> actions.editTable());
        JPanel editTableRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        editTableRow.setOpaque(false); editTableRow.add(editTable);
        tableNote.add(tableNoteText); tableNote.add(editTableRow);
        tableNote.setBorder(JBUI.Borders.empty(16, 0, 16, 0));
        footer.add(footerLabel);
        bottomDock.add(tableNote, BorderLayout.NORTH);
        bottomDock.add(footer, BorderLayout.SOUTH);
        add(bottomDock, BorderLayout.SOUTH);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape");
        getActionMap().put("escape", new AbstractAction() { public void actionPerformed(ActionEvent e) { handleEscape(); } });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "apply");
        getActionMap().put("apply", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (editing) applyDraft(); } });
        valueArea.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && cell != null && cell.editable()) beginEdit(); } });
        DatabaseUi.onTextChange(valueArea, () -> { if (editing) { summary.setText(valueSummary(valueArea.getText())); updateEnabled(); } });
        renderEmpty();
        updatePin();
        lastLookAndFeel = UIManager.getLookAndFeel();
        refreshTheme();
    }

    private static void quiet(AbstractButton button) {
        button.setOpaque(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusable(true);
        button.setMargin(new Insets(2, 5, 2, 5));
    }

    @Override public void updateUI() {
        super.updateUI();
        if (valueArea != null) SwingUtilities.invokeLater(this::refreshTheme);
    }

    @Override public void doLayout() {
        if (!columnMode && valueBlock != null && getHeight() > 0) {
            int fixedHeight = header.getPreferredSize().height + bottomDock.getPreferredSize().height
                    + lower.getPreferredSize().height + valueBlock.getPreferredSize().height
                    - valueScroll.getPreferredSize().height;
            int editorHeight = Math.max(JBUI.scale(180), Math.min(JBUI.scale(384), getHeight() - fixedHeight));
            if (valueScroll.getPreferredSize().height != editorHeight) {
                valueScroll.setPreferredSize(new Dimension(100, editorHeight));
                resizeContentBlocks();
            }
        }
        super.doLayout();
    }

    private static final class TextToggleUI extends BasicButtonUI {
        @Override public void paint(Graphics graphics, JComponent component) {
            super.paint(graphics, component);
            AbstractButton button = (AbstractButton) component;
            if (button.isSelected() && !(button instanceof JToggleButton)) {
                graphics.setColor(button.getForeground());
                graphics.fillRect(0, component.getHeight() - JBUI.scale(2), component.getWidth(), JBUI.scale(2));
            }
        }
        @Override protected void paintText(Graphics graphics, JComponent component, Rectangle bounds, String text) {
            AbstractButton button = (AbstractButton) component;
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setColor(button.getForeground());
                BasicGraphicsUtils.drawStringUnderlineCharAt(button, copy, text,
                        button.getDisplayedMnemonicIndex(), bounds.x,
                        bounds.y + copy.getFontMetrics().getAscent());
            } finally { copy.dispose(); }
        }
    }
    private static void styleTextToggle(AbstractButton button) {
        button.setUI(new TextToggleUI()); button.setBorderPainted(false); button.setContentAreaFilled(false); button.setOpaque(false);
        button.setFocusPainted(false); button.setMargin(new Insets(2, 5, 2, 5));
        button.getModel().addChangeListener(e -> refreshTextToggle(button));
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
        metadata.removeAll();
        if (columnMode && column != null) buildColumn();
        else if (cell != null) buildCell();
        else renderEmpty();
        refreshSectionBorder();
        updatePin();
        metadata.revalidate(); lower.revalidate(); content.revalidate();
        resizeContentBlocks();
        content.repaint(); updateEnabled();
    }
    private void resizeContentBlocks() {
        valueBlock.setMaximumSize(new Dimension(Integer.MAX_VALUE, valueBlock.getPreferredSize().height));
        metadata.setMaximumSize(new Dimension(Integer.MAX_VALUE, metadata.getPreferredSize().height));
        tableNote.setMaximumSize(new Dimension(Integer.MAX_VALUE, tableNote.getPreferredSize().height));
        lower.setMaximumSize(new Dimension(Integer.MAX_VALUE, lower.getPreferredSize().height));
    }
    private void updateFooter() {
        footerLabel.setText(editing ? "Editing this cell · Stage change or Cancel · Esc cancels and closes"
                : cell != null && cell.modified() ? "Pending change · Commit to save to database"
                : pinned ? columnMode ? "Pinned to this column · Esc to close"
                : "Pinned to this cell · Esc to close"
                : columnMode ? "Follows column selection · Esc to close" : "Follows cell selection · Esc to close");
        footer.revalidate(); footer.repaint();
    }
    private void buildCell() {
        valueScroll.setVisible(true); edit.setVisible(true); more.setVisible(true);
        valueBlock.setVisible(true); tableNote.setVisible(true);
        ColumnMetadata c = cell.column();
        eyebrow.setText(safe(cell.tableName()));
        title.setText(c == null ? "Cell value" : c.getName());
        title.setToolTipText(title.getText());
        identity.setText(safe(cell.rowLabel()));
        identity.setToolTipText(safe(cell.rowLabel()));
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
        validation.setForeground(JBColor.namedColor("Label.errorForeground", new JBColor(0xB83232, 0xFF8282)));
        validation.setVisible(invalid);
        valueScroll.putClientProperty("JComponent.outline", invalid ? "error" : null);
        if (cell.editabilityHint() != null && !cell.editabilityHint().isBlank()) addMetadata("", cell.editabilityHint());
        editTable.setEnabled(!cell.view()); updateMoreMenu();
    }
    private void buildColumn() {
        ColumnMetadata c = column.column();
        eyebrow.setText(safe(column.tableName()));
        title.setText(c == null ? "Column details" : c.getName());
        title.setToolTipText(title.getText());
        identity.setText("Column " + (column.modelColumn() + 1) + "  ·  Structure");
        identity.setToolTipText(null);
        summary.setText(c == null ? "" : c.getFormattedType());
        valueBlock.setVisible(false); tableNoteText.setVisible(true); tableNote.setVisible(true); editTable.setVisible(true);
        staging.setVisible(false);
        if (c != null) {
            addTypeCard(c.getFormattedType());
            addSection("Definition");
            addMetadata("Position", Integer.toString(column.modelColumn() + 1));
            addMetadata("Default", c.getDefaultValue() == null ? "—" : c.getDefaultValue());
            if (c.isGenerated() || c.isAutoIncrement()) addMetadata("Generated", c.isAutoIncrement() ? "Auto increment" : "Yes");
            if (c.getColumnSize() > 0) addMetadata("Size", Integer.toString(c.getColumnSize()));
            if (c.getDecimalDigits() > 0) addMetadata("Scale", Integer.toString(c.getDecimalDigits()));
            addSection("Constraints");
            addMetadata("Nullable", c.isNullable() ? "Yes" : "No");
            addMetadata("Primary key", c.isPrimaryKey() ? "Yes" : "No");
            addMetadata("Foreign key", c.isForeignKey() ? "Yes" : "No");
        }
        editTable.setEnabled(!column.view());
    }
    private void addTypeCard(String sqlType) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBackground(new JBColor(0xEDF3FC, 0x2A3444));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new JBColor(0xD8E5F8, 0x40506A)),
                JBUI.Borders.empty(16)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(91)));
        JLabel caption = new JLabel("SQL TYPE");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, JBUI.scale(13f)));
        caption.putClientProperty("lattice.secondary", Boolean.TRUE);
        caption.setForeground(new JBColor(0x657185, 0xABB3BE));
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel type = new JLabel(safe(sqlType));
        type.setFont(type.getFont().deriveFont(Font.BOLD, JBUI.scale(22f)));
        type.putClientProperty("lattice.accent", Boolean.TRUE);
        type.setForeground(new JBColor(0x2F65BE, 0xA6C7FF));
        type.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(caption);
        card.add(Box.createVerticalStrut(JBUI.scale(5)));
        card.add(type);
        metadata.add(Box.createVerticalStrut(JBUI.scale(16)));
        metadata.add(card);
    }
    private void addSection(String name) {
        JLabel label = new JLabel(name);
        if ("Column".equals(name)) { label.setIcon(Icons.COLUMN); label.setIconTextGap(JBUI.scale(8)); }
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, JBUI.scale(17f)));
        label.setBorder(JBUI.Borders.empty(16, 0, 8, 0));
        metadata.add(label);
    }
    private void renderEmpty() { eyebrow.setText(""); title.setText("Cell details"); identity.setText("Select a cell to inspect its value"); summary.setText(""); valueArea.setText(""); metadata.removeAll(); if (valueBlock != null) valueBlock.setVisible(true); if (tableNote != null) tableNote.setVisible(false); if (staging != null) staging.setVisible(false); }
    private void addMetadata(String name, String value) {
        JPanel row = new JPanel(new BorderLayout(8, 0)); row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!name.isEmpty()) {
            JLabel key = new JLabel(name);
            key.setFont(key.getFont().deriveFont(JBUI.scale(16f)));
            key.setForeground(new JBColor(0x657185, 0xABB3BE));
            key.putClientProperty("lattice.secondary", Boolean.TRUE);
            key.setPreferredSize(new Dimension(JBUI.scale(132), key.getPreferredSize().height));
            row.add(key, BorderLayout.WEST);
        }
        JLabel val = new JLabel("<html>" + escape(value) + "</html>");
        val.setFont(val.getFont().deriveFont(JBUI.scale(16f)));
        val.setToolTipText(value); row.add(val, BorderLayout.CENTER);
        row.setBorder(JBUI.Borders.empty(6, 0)); metadata.add(row);
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
    private void updateTabs() { formattedTab.setSelected(!rawMode); rawTab.setSelected(rawMode); wrap.setSelected(valueArea.getLineWrap()); }
    private void beginEdit() { if (cell == null || !cell.editable() || busy || editing) return; draft = CellValuePresentation.editable(cell.value()); editing = true; valueArea.setEditable(true); valueArea.setText(draft); summary.setText(valueSummary(draft)); valueArea.requestFocusInWindow(); valueArea.setCaretPosition(valueArea.getDocument().getLength()); updateEnabled(); }
    private void applyDraft() { if (!editing || busy || !hasDraftChange()) return; stageCurrentEdit(); }
    private void stageCurrentEdit() {
        String text = valueArea.getText(); Cell target = cell;
        editing = false; draft = null; valueArea.setEditable(false); updateEnabled();
        if (target != null) actions.apply(target, text);
    }
    private void cancelEdit() { if (!editing) return; editing = false; draft = null; renderValue(); updateEnabled(); }
    private void copyText() { String text = editing ? valueArea.getText() : CellValuePresentation.raw(cell == null ? null : cell.value()); Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }
    private void showMore(Component at) {
        JPopupMenu menu = DatabaseUi.popupMenu(); JMenuItem nullItem = new JMenuItem("Set SQL NULL"); nullItem.setEnabled(cell != null && cell.editable() && !busy && cell.column() != null && cell.column().isNullable()); nullItem.addActionListener(e -> actions.setNull(cell)); menu.add(nullItem);
        if (cell != null && cell.newRow() && cell.column() != null && (cell.column().getDefaultValue() != null || cell.column().isAutoIncrement())) { JMenuItem def = new JMenuItem("Use database default"); def.setEnabled(cell.editable() && !busy); def.addActionListener(e -> actions.useDefault(cell)); menu.add(def); }
        if (cell != null && cell.modified()) { JMenuItem rev = new JMenuItem("Revert value"); rev.setEnabled(cell.editable() && !busy); rev.addActionListener(e -> actions.revert(cell)); menu.add(rev); }
        menu.show(at, 0, at.getHeight());
    }
    private void updateMoreMenu() { more.setVisible(cell != null); }
    private void updateEnabled() {
        edit.setEnabled(cell != null && cell.editable() && !busy && !editing);
        edit.setVisible(cell != null && !columnMode && !editing);
        apply.setEnabled(hasDraftChange() && !busy); cancel.setEnabled(editing && !busy);
        more.setEnabled(cell != null && cell.editable() && !busy && !editing);
        more.setVisible(cell != null && !columnMode && !editing);
        formattedTab.setEnabled(!editing); rawTab.setEnabled(!editing);
        staging.setVisible(editing && !columnMode);
        editTable.setEnabled(!busy && (cell != null && !cell.view() || column != null && !column.view()));
        refreshTextToggle(formattedTab); refreshTextToggle(rawTab); refreshTextToggle(wrap);
        updateFooter();
        resizeContentBlocks(); content.revalidate(); content.repaint();
    }
    private void updatePin() {
        pin.setIcon(new HeaderGlyph(true, pinned));
        pin.setSelected(pinned);
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
        content.setBackground(panelBackground);
        valueArea.setBackground(new JBColor(0xFFFFFF, 0x1E1F22));
        valueArea.setForeground(JBColor.foreground());
        valueScroll.getViewport().setBackground(valueArea.getBackground());
        lineNumbers.setBackground(new JBColor(0xF4F6F9, 0x25262A));
        lineNumbers.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE)));
        Color separator = JBColor.namedColor("Component.borderColor", new JBColor(0xDCE0E5, 0x46484D));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separator), JBUI.Borders.empty(20, 0, 16, 0)));
        refreshSectionBorder();
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, separator), JBUI.Borders.empty(8, 0, 8, 0)));
        valueScroll.setBorder(BorderFactory.createLineBorder(separator));
        bodyScroll.getViewport().setBackground(content.getBackground());
        footerLabel.setForeground(new JBColor(0x657185, 0xABB3BE));
        refreshMetadataColors(content);
        Font labelFont = UIManager.getFont("Label.font");
        Font buttonFont = UIManager.getFont("Button.font");
        if (labelFont != null) {
            title.setFont(labelFont.deriveFont(Font.BOLD, JBUI.scale(24f)));
            eyebrow.setFont(labelFont.deriveFont(Font.BOLD, JBUI.scale(13f)));
            identity.setFont(labelFont.deriveFont(JBUI.scale(14f)));
            summary.setFont(labelFont.deriveFont(JBUI.scale(15f)));
            tableNoteLabel.setFont(labelFont.deriveFont(JBUI.scale(15f)));
            footerLabel.setFont(labelFont.deriveFont(JBUI.scale(13f)));
        }
        if (buttonFont != null) for (AbstractButton button : new AbstractButton[]{formattedTab, rawTab, wrap, edit, copy, more, apply, cancel, editTable})
            button.setFont(buttonFont.deriveFont(JBUI.scale(16f)));
        sizePrimaryAction(edit, 148);
        sizePrimaryAction(apply, 96);
        title.setForeground(JBColor.foreground());
        summary.setForeground(new JBColor(0x667185, 0xAAB3C1));
        eyebrow.setForeground(new JBColor(0x2F6DDB, 0x70A5FF));
        identity.setForeground(new JBColor(0x667185, 0xAAB3C1));
        tableNoteLabel.setForeground(new JBColor(0x667185, 0xAAB3C1));
        copy.setForeground(JBColor.foreground());
        more.setForeground(JBColor.foreground());
        cancel.setForeground(JBColor.foreground());
        for (AbstractButton button : new AbstractButton[]{formattedTab, rawTab, wrap}) {
            button.setUI(new TextToggleUI()); button.setBorderPainted(false); button.setContentAreaFilled(false); button.setOpaque(false);
        }
        refreshTextToggle(formattedTab); refreshTextToggle(rawTab); refreshTextToggle(wrap);
        quiet(more);
        quiet(cancel);
        DatabaseUi.setActionEmphasis(edit, new JBColor(0x2F69CF, 0x3574D4));
        DatabaseUi.setActionEmphasis(apply, new JBColor(0x2F69CF, 0x3574D4));
        for (HeaderIconButton button : new HeaderIconButton[]{pin, close}) {
            button.setBorderPainted(false); button.setContentAreaFilled(false);
            button.setOpaque(false); button.setFocusPainted(false);
            button.setMargin(new Insets(0, 0, 0, 0));
        }
        resizeContentBlocks();
        revalidate(); repaint();
    }
    private static void sizePrimaryAction(JButton button, int minimumWidth) {
        FontMetrics metrics = button.getFontMetrics(button.getFont());
        int iconWidth = button.getIcon() == null ? 0 : button.getIcon().getIconWidth() + button.getIconTextGap();
        button.setPreferredSize(new Dimension(Math.max(JBUI.scale(minimumWidth),
                metrics.stringWidth(button.getText()) + iconWidth + JBUI.scale(48)),
                Math.max(JBUI.scale(36), metrics.getHeight() + JBUI.scale(16))));
    }
    private void refreshSectionBorder() {
        Color separator = JBColor.namedColor("Component.borderColor", new JBColor(0xDCE0E5, 0x46484D));
        lower.setBorder(columnMode ? JBUI.Borders.empty()
                : BorderFactory.createMatteBorder(1, 0, 0, 0, separator));
    }
    private static void refreshTextToggle(AbstractButton button) {
        Color accent = new JBColor(0x2F6DDB, 0x70A5FF);
        button.setForeground(!button.isEnabled()
                ? JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE))
                : button.isSelected() ? accent : JBColor.foreground());
        if (button instanceof JToggleButton) button.setBorder(JBUI.Borders.empty(2, 5));
        else button.setBorder(button.isSelected()
                ? BorderFactory.createMatteBorder(0, 0, JBUI.scale(2), 0, accent)
                : JBUI.Borders.empty(0, 0, 2, 0));
    }
    private static void refreshMetadataColors(Component component) {
        if (component instanceof JLabel label) {
            label.setForeground(Boolean.TRUE.equals(label.getClientProperty("lattice.accent"))
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
    private static String escape(String s) { return safe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
