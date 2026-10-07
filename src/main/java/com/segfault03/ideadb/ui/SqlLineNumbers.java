package com.segfault03.ideadb.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.geom.Rectangle2D;

/** A scrolling line-number gutter for the console's plain-text SQL editor. */
final class SqlLineNumbers extends JComponent implements DocumentListener {
    private final JTextArea editor;

    SqlLineNumbers(JTextArea editor) {
        this.editor = editor;
        editor.getDocument().addDocumentListener(this);
        editor.addPropertyChangeListener("font", event -> { revalidate(); repaint(); });
        setOpaque(true);
        setBackground(JBColor.namedColor("EditorGutter.background", new JBColor(0xF5F7FA, 0x2E3138)));
        setForeground(JBColor.namedColor("Label.infoForeground", new JBColor(0x777D86, 0xA0A5AE)));
    }

    @Override public Dimension getPreferredSize() {
        int digits = Math.max(2, String.valueOf(editor.getLineCount()).length());
        int width = getFontMetrics(editor.getFont()).charWidth('0') * digits + JBUI.scale(20);
        return new Dimension(width, editor.getPreferredSize().height);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(getBackground());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        graphics.setFont(editor.getFont());
        graphics.setColor(getForeground());
        FontMetrics metrics = graphics.getFontMetrics();
        Rectangle clip = graphics.getClipBounds();
        int firstOffset = editor.viewToModel2D(new Point(0, clip.y));
        int firstLine = editor.getDocument().getDefaultRootElement().getElementIndex(Math.max(0, firstOffset));
        for (int line = firstLine; line < editor.getLineCount(); line++) {
            try {
                Rectangle2D bounds = editor.modelToView2D(editor.getLineStartOffset(line));
                if (bounds == null || bounds.getY() > clip.y + clip.height) break;
                String number = String.valueOf(line + 1);
                graphics.drawString(number, getWidth() - JBUI.scale(10) - metrics.stringWidth(number),
                        (int)bounds.getY() + metrics.getAscent());
            } catch (javax.swing.text.BadLocationException ignored) {
                break;
            }
        }
    }

    private void changed() { revalidate(); repaint(); }
    @Override public void insertUpdate(DocumentEvent event) { changed(); }
    @Override public void removeUpdate(DocumentEvent event) { changed(); }
    @Override public void changedUpdate(DocumentEvent event) { changed(); }
}
