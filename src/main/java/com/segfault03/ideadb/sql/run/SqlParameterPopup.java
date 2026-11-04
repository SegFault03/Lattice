package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lifts a card into a focused, shadowed card of its own: the form is laid over the card with its SQL on the card's SQL,
 * so the parameters turn into fields where they stand. Code around the card does not move. Enter runs; Esc or a click
 * elsewhere puts the card back.
 */
final class SqlParameterPopup {
    private SqlParameterPopup() {}

    /** Shows the form over {@code anchor} (content component coordinates); {@code then} gets the values once submitted. */
    static void show(@NotNull Editor editor, @NotNull Rectangle anchor, @NotNull SqlParameterForm form,
                     @NotNull Consumer<Map<String, SqlParameterValue>> then) {
        Rectangle visible = editor.getScrollingModel().getVisibleArea();
        int left = Math.max(anchor.x, visible.x);
        form.fitWidth(visible.x + visible.width - left - 2 * SqlParameterForm.OUTER - JBUI.scale(24));
        Dimension preferred = form.getPreferredSize();
        int width = Math.max(preferred.width, anchor.width + 2 * SqlParameterForm.OUTER);
        form.setPreferredSize(new Dimension(width, preferred.height));

        JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(form, form.firstField())
                .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true).setCancelKeyEnabled(true)
                .setMovable(false).setResizable(false).setShowShadow(true).setShowBorder(true).createPopup();
        form.onCancel(popup::cancel);
        form.onSubmit(() -> {
            Map<String, SqlParameterValue> values = form.values();
            popup.closeOk(null);
            // After the popup has given the focus back to the editor.
            ApplicationManager.getApplication().invokeLater(() -> then.accept(values));
        });
        form.addChangeListener(() -> {
            if (popup.isDisposed() || !popup.isVisible()) return;
            form.setPreferredSize(null);
            Dimension size = form.getPreferredSize();
            form.setPreferredSize(new Dimension(Math.max(size.width, width), size.height));
            popup.pack(true, true);
        });
        popup.show(new RelativePoint(editor.getContentComponent(), location(anchor, form.sqlOrigin(), preferred.height, visible)));
    }

    /**
     * The SQL of the form on the card's SQL: the form starts a padding to the left of the card's text and its header above
     * the card. Kept inside the visible area, so a card at the top of the view lifts downwards instead.
     */
    static @NotNull Point location(@NotNull Rectangle anchor, @NotNull Point sqlOrigin, int height, @NotNull Rectangle visible) {
        int x = anchor.x + com.segfault03.ideadb.sql.card.SqlCardGeometry.PADDING - sqlOrigin.x;
        int y = anchor.y - sqlOrigin.y;
        x = Math.max(visible.x, x);
        if (y < visible.y) y = visible.y;
        if (y + height > visible.y + visible.height) y = Math.max(visible.y, visible.y + visible.height - height);
        return new Point(x, y);
    }
}
