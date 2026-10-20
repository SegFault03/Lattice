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
    private final JPanel header = new JPanel(new BorderLayout(8, 0));
    private final Body content = new Body();
    private final JScrollPane bodyScroll = new JScrollPane(content);
    private final JPanel intro = new JPanel(new BorderLayout(8, 0));
    private final JPanel lower = new JPanel();
    private JPanel valueBlock;
    private JPanel tableNote;
    private JPanel staging;
    private final JPanel tableNoteText = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
    private final JLabel tableNoteLabel = new JLabel("Review or change table structure");
    private final JPanel metadata = new JPanel();
    private final JPanel footer = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
    private final JPanel bottomDock = new JPanel(new BorderLayout());
    private final JLabel footerLabel = new JLabel();
    private final JButton pin = new JButton(AllIcons.General.Pin);
    private final JButton close = new JButton("×");
    private final JButton formattedTab = new JButton("Formatted");
    private final JButton rawTab = new JButton("Raw");
    private final JToggleButton wrap = new JToggleButton("Wrap");
    private final JButton edit = DatabaseUi.dialogAction("Edit value", AllIcons.Actions.Edit, "Edit selected cell value");
    private final JButton copy = new JButton("Copy", AllIcons.Actions.Copy);
    private final JButton more = new JButton("More");
    private final JButton apply = DatabaseUi.dialogAction("Apply", null, "Apply");
    private final JButton cancel = new JButton("Cancel");
    private final JButton editTable = new JButton("Edit table");
    private Cell cell;
    private Column column;
    private boolean pinned, columnMode, editing, busy, rawMode;
    private String draft;
    private LookAndFeel lastLookAndFeel;

    private static final class Body extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return JBUI.scale(16); }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return visibleRect.height - JBUI.scale(32); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    CellDetailsPanel(Actions actions) {
        this.actions = actions;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(JBUI.scale(520), 400));
        setMinimumSize(new Dimension(JBUI.scale(340), 220));
        setBorder(JBUI.Borders.empty(0, 23));
        title.setText("Value Workspace");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5));
        header.add(title, BorderLayout.CENTER);
        pin.setToolTipText("Pin details"); pin.getAccessibleContext().setAccessibleName("Pin cell details");
        pin.setBorderPainted(false); pin.setContentAreaFilled(false);
        pin.addActionListener(e -> { pinned = !pinned; updatePin(); if (!pinned) actions.unpin(); });
        close.setToolTipText("Close details"); close.setBorderPainted(false); close.setContentAreaFilled(false);
        close.addActionListener(e -> actions.close());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.TRAILING, 2, 0));
        controls.setOpaque(false); controls.add(pin); controls.add(close);
        header.add(controls, BorderLayout.EAST); add(header, BorderLayout.NORTH);

        content.setOpaque(true); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        bodyScroll.setBorder(BorderFactory.createEmptyBorder());
        bodyScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        add(bodyScroll, BorderLayout.CENTER);
        intro.setOpaque(false); intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        eyebrow.setIcon(Icons.TABLE); eyebrow.setIconTextGap(JBUI.scale(8));
        identity.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        summary.setForeground(JBColor.namedColor("Label.infoForeground", JBColor.GRAY));
        intro.add(eyebrow, BorderLayout.WEST); intro.add(identity, BorderLayout.EAST);
        intro.setBorder(JBUI.Borders.empty(10, 0, 11, 0));
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEADING, 18, 0)); tabs.setOpaque(false);
        styleTextToggle(formattedTab); styleTextToggle(rawTab); styleTextToggle(wrap);
        formattedTab.addActionListener(e -> { rawMode = false; if (!editing) renderValue(); updateTabs(); });
        rawTab.addActionListener(e -> { rawMode = true; if (!editing) renderValue(); updateTabs(); });
        wrap.addActionListener(e -> { valueArea.setLineWrap(wrap.isSelected()); valueArea.setWrapStyleWord(wrap.isSelected()); });
        tabs.add(formattedTab); tabs.add(rawTab); tabs.add(wrap);
        valueArea.setLineWrap(true); valueArea.setWrapStyleWord(true); valueArea.setEditable(false);
        valueArea.getAccessibleContext().setAccessibleName("Cell value");
        valueArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(18)));
        valueArea.setBorder(JBUI.Borders.empty(15));
        valueScroll.setRowHeaderView(lineNumbers);
        valueScroll.setPreferredSize(new Dimension(100, JBUI.scale(385)));
        valueScroll.setMinimumSize(new Dimension(50, JBUI.scale(160)));
        JPanel tabLine = new JPanel(new BorderLayout()); tabLine.setOpaque(false);
        tabLine.setBorder(JBUI.Borders.empty(7, 0, 6, 0));
        tabLine.add(tabs, BorderLayout.WEST); tabLine.add(summary, BorderLayout.EAST);
        JPanel valueCard = new JPanel(new BorderLayout(0, 6)); valueCard.setOpaque(false);
        valueCard.add(tabLine, BorderLayout.NORTH); valueCard.add(valueScroll, BorderLayout.CENTER);
        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 18, 0));
        actionsRow.setOpaque(false); actionsRow.setBorder(JBUI.Borders.emptyTop(8));
        quiet(copy); copy.setToolTipText("Copy the raw cell value");
        copy.addActionListener(e -> copyText());
        quiet(more);
        edit.setMargin(new Insets(6, 14, 6, 14));
        edit.addActionListener(e -> beginEdit());
        more.addActionListener(e -> showMore(more));
        more.setIcon(AllIcons.Actions.MoreHorizontal); more.setIconTextGap(JBUI.scale(5));
        actionsRow.add(edit); actionsRow.add(copy); actionsRow.add(more);
        staging = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0)); staging.setOpaque(false);
        staging.setAlignmentX(Component.LEFT_ALIGNMENT);
        apply.addActionListener(e -> applyDraft()); cancel.addActionListener(e -> cancelEdit());
        staging.add(apply); staging.add(cancel);
        actionsRow.add(staging, 0);
        valueBlock = new JPanel(new BorderLayout(0, 4)); valueBlock.setOpaque(false);
        valueBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        valueBlock.add(valueCard, BorderLayout.CENTER);
        valueBlock.add(actionsRow, BorderLayout.SOUTH);
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
        tableNote.setBorder(JBUI.Borders.emptyTop(18));
        footer.add(footerLabel);
        bottomDock.add(tableNote, BorderLayout.NORTH);
        bottomDock.add(footer, BorderLayout.SOUTH);
        add(bottomDock, BorderLayout.SOUTH);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape");
        getActionMap().put("escape", new AbstractAction() { public void actionPerformed(ActionEvent e) { handleEscape(); } });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "apply");
        getActionMap().put("apply", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (editing) applyDraft(); } });
        valueArea.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && cell != null && cell.editable()) beginEdit(); } });
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
    void showCell(Cell cell) {
        ensureCurrentTheme();
        boolean same = this.cell != null && cell != null && Objects.equals(this.cell.tableName(), cell.tableName())
                && this.cell.modelRow() == cell.modelRow() && this.cell.modelColumn() == cell.modelColumn();
        if (!same && editing) stageCurrentEdit();
        if (!same) { editing = false; draft = null; }
        this.cell = cell; this.column = null; columnMode = false; rebuild();
    }
    void showColumn(Column column) {
        ensureCurrentTheme();
        if (editing) stageCurrentEdit();
        this.column = column; this.cell = null; columnMode = true; editing = false; draft = null; rebuild();
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
    boolean finishEditing() { if (editing && busy) return false; if (editing) stageCurrentEdit(); return true; }
    void cancelEditing() { cancelEdit(); }
    int inspectedRow() { return cell == null ? -1 : cell.modelRow(); }
    int inspectedColumn() { return cell != null ? cell.modelColumn() : column == null ? -1 : column.modelColumn(); }
    boolean handleEscape() { if (editing) { cancelEdit(); return true; } actions.close(); return true; }
    void setBusy(boolean busy) { this.busy = busy; updateEnabled(); }

    private void rebuild() {
        content.removeAll();
        content.add(intro);
        if (!columnMode) content.add(valueBlock);
        content.add(lower);
        metadata.removeAll();
        if (columnMode && column != null) buildColumn();
        else if (cell != null) buildCell();
        else renderEmpty();
        updateFooter();
        metadata.revalidate(); lower.revalidate(); content.revalidate();
        resizeContentBlocks();
        content.repaint(); updateEnabled();
    }
    private void resizeContentBlocks() {
        intro.setMaximumSize(new Dimension(Integer.MAX_VALUE, intro.getPreferredSize().height));
        valueBlock.setMaximumSize(new Dimension(Integer.MAX_VALUE, valueBlock.getPreferredSize().height));
        metadata.setMaximumSize(new Dimension(Integer.MAX_VALUE, metadata.getPreferredSize().height));
        tableNote.setMaximumSize(new Dimension(Integer.MAX_VALUE, tableNote.getPreferredSize().height));
        lower.setMaximumSize(new Dimension(Integer.MAX_VALUE, lower.getPreferredSize().height));
    }
    private void updateFooter() {
        footerLabel.setText(pinned ? columnMode ? "Pinned to this column · Esc to close"
                : "Pinned to this cell · Esc to close"
                : columnMode ? "Follows column selection · Esc to close" : "Follows cell selection · Esc to close");
        footer.revalidate(); footer.repaint();
    }
    private void buildCell() {
        valueScroll.setVisible(true); edit.setVisible(true); more.setVisible(true);
        valueBlock.setVisible(true); tableNote.setVisible(true);
        ColumnMetadata c = cell.column(); eyebrow.setText(safe(cell.tableName()) + "  /  " + (c == null ? "" : c.getName()));
        title.setText("Value Workspace"); identity.setText(safe(cell.rowLabel()));
        summary.setText(valueSummary(cell.value()));
        edit.setText(editing ? "Editing" : "Edit value"); if (!editing) renderValue();
        if (c != null) {
            addSection("Column");
            addMetadata("Name", c.getName());
            addMetadata("SQL type", c.getFormattedType());
            addMetadata("Nullable", c.isNullable() ? "Yes" : "No");
            addMetadata("Default", c.getDefaultValue() == null ? "—" : c.getDefaultValue());
            if (c.isPrimaryKey()) addMetadata("Primary key", "Yes");
            if (c.isForeignKey()) addMetadata("Foreign key", "Yes");
        }
        if (cell.validationError() != null && !cell.validationError().isBlank()) addMetadata("Validation", cell.validationError());
        if (cell.editabilityHint() != null && !cell.editabilityHint().isBlank()) addMetadata("", cell.editabilityHint());
        editTable.setEnabled(!cell.view()); updateMoreMenu();
    }
    private void buildColumn() {
        ColumnMetadata c = column.column(); eyebrow.setText(safe(column.tableName()) + "  /  " + (c == null ? "" : c.getName()));
        title.setText("Column Workspace"); identity.setText("Column structure"); summary.setText(c == null ? "" : c.getFormattedType());
        valueBlock.setVisible(false); tableNoteText.setVisible(true); tableNote.setVisible(true); editTable.setVisible(true);
        staging.setVisible(false);
        if (c != null) {
            addTypeCard(c.getFormattedType());
            addSection("Definition");
            addMetadata("Name", c.getName());
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
                JBUI.Borders.empty(13, 15)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(91)));
        JLabel caption = new JLabel("SQL TYPE");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, JBUI.scale(13f)));
        caption.putClientProperty("lattice.secondary", Boolean.TRUE);
        caption.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE)));
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel type = new JLabel(safe(sqlType));
        type.setFont(type.getFont().deriveFont(Font.BOLD, JBUI.scale(22f)));
        type.putClientProperty("lattice.accent", Boolean.TRUE);
        type.setForeground(new JBColor(0x2F65BE, 0xA6C7FF));
        type.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(caption);
        card.add(Box.createVerticalStrut(JBUI.scale(5)));
        card.add(type);
        metadata.add(Box.createVerticalStrut(JBUI.scale(15)));
        metadata.add(card);
    }
    private void addSection(String name) {
        JLabel label = new JLabel(name);
        if ("Column".equals(name)) { label.setIcon(Icons.COLUMN); label.setIconTextGap(JBUI.scale(8)); }
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, JBUI.scale(17f)));
        label.setBorder(JBUI.Borders.empty(13, 0, 9, 0));
        metadata.add(label);
    }
    private void renderEmpty() { eyebrow.setText(""); title.setText("Value Workspace"); identity.setText("Select a cell to inspect its value"); summary.setText(""); valueArea.setText(""); metadata.removeAll(); if (valueBlock != null) valueBlock.setVisible(true); if (tableNote != null) tableNote.setVisible(false); if (staging != null) staging.setVisible(false); }
    private void addMetadata(String name, String value) {
        JPanel row = new JPanel(new BorderLayout(8, 0)); row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!name.isEmpty()) {
            JLabel key = new JLabel(name);
            key.setFont(key.getFont().deriveFont(JBUI.scale(16f)));
            key.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE)));
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
        valueArea.setBackground(new JBColor(0xFFFFFF, 0x1E1F22));
        valueArea.setForeground(JBColor.foreground());
        valueArea.setCaretPosition(0); updateTabs();
    }
    private void updateTabs() { formattedTab.setSelected(!rawMode); rawTab.setSelected(rawMode); wrap.setSelected(valueArea.getLineWrap()); }
    private void beginEdit() { if (cell == null || !cell.editable() || busy || editing) return; draft = CellValuePresentation.editable(cell.value()); editing = true; valueArea.setEditable(true); valueArea.setText(draft); valueArea.requestFocusInWindow(); valueArea.setCaretPosition(valueArea.getDocument().getLength()); updateEnabled(); }
    private void applyDraft() { if (!editing || busy) return; stageCurrentEdit(); }
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
        apply.setEnabled(editing && !busy); cancel.setEnabled(editing && !busy);
        more.setEnabled(cell != null && cell.editable() && !busy && !editing);
        more.setVisible(cell != null && !columnMode && !editing);
        formattedTab.setEnabled(!editing); rawTab.setEnabled(!editing);
        staging.setVisible(editing && !columnMode);
        editTable.setEnabled(!busy && (cell != null && !cell.view() || column != null && !column.view()));
        refreshTextToggle(formattedTab); refreshTextToggle(rawTab); refreshTextToggle(wrap);
        resizeContentBlocks(); content.revalidate(); content.repaint();
    }
    private void updatePin() {
        pin.setIcon(pinned ? AllIcons.General.PinSelected : AllIcons.General.Pin);
        pin.setToolTipText(pinned ? "Unpin details" : "Pin details");
        pin.getAccessibleContext().setAccessibleName(pinned ? "Unpin cell details" : "Pin cell details");
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
                BorderFactory.createMatteBorder(0, 0, 1, 0, separator), JBUI.Borders.empty(12, 0)));
        intro.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separator), JBUI.Borders.empty(10, 0, 11, 0)));
        lower.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, separator), JBUI.Borders.emptyTop(5)));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, separator));
        valueScroll.setBorder(BorderFactory.createLineBorder(separator));
        bodyScroll.getViewport().setBackground(content.getBackground());
        footerLabel.setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE)));
        refreshMetadataColors(content);
        Font labelFont = UIManager.getFont("Label.font");
        Font buttonFont = UIManager.getFont("Button.font");
        if (labelFont != null) {
            title.setFont(labelFont.deriveFont(Font.BOLD, JBUI.scale(25f)));
            eyebrow.setFont(labelFont.deriveFont(JBUI.scale(16f)));
            identity.setFont(labelFont.deriveFont(JBUI.scale(15f)));
            summary.setFont(labelFont.deriveFont(JBUI.scale(15f)));
            tableNoteLabel.setFont(labelFont.deriveFont(JBUI.scale(15f)));
        }
        if (buttonFont != null) for (AbstractButton button : new AbstractButton[]{formattedTab, rawTab, wrap, edit, copy, more, apply, cancel, editTable})
            button.setFont(buttonFont.deriveFont(JBUI.scale(16f)));
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
        DatabaseUi.setActionEmphasis(edit, new JBColor(0x2F69CF, 0x3574D4));
        DatabaseUi.setActionEmphasis(apply, new JBColor(0x2F69CF, 0x3574D4));
        pin.setBorderPainted(false); pin.setContentAreaFilled(false); close.setBorderPainted(false); close.setContentAreaFilled(false);
        resizeContentBlocks();
        revalidate(); repaint();
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
                    ? JBColor.namedColor("Label.infoForeground", new JBColor(0x657185, 0xABB3BE))
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
        if (value instanceof byte[] bytes) return bytes.length + (bytes.length == 1 ? " byte" : " bytes");
        String s = CellValuePresentation.raw(value);
        int characters = s.codePointCount(0, s.length());
        return characters + (characters == 1 ? " character" : " characters");
    }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String escape(String s) { return safe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
