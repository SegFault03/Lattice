package com.segfault03.ideadb.sql.card;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Optional;

/**
 * Shows the card toolbar of the card under the mouse, in a popup above the card that never takes focus. Typing,
 * scrolling or the editor losing focus hide it at once; otherwise {@link SqlCardHoverTimer} decides.
 */
final class SqlCardHoverController implements Disposable {
    static final String TOOLBAR_GROUP = "com.segfault03.lattice.sql.card.toolbar";
    static final String PLACE = "LatticeSqlCard";
    private static final Key<SqlCardHoverController> KEY = Key.create("lattice.sql.card.hover");
    private static final int TICK_MS = 50;

    private final Editor editor;
    private final SqlCardHoverTimer<RangeHighlighter> timer = new SqlCardHoverTimer<>();
    private final Timer tick = new Timer(TICK_MS, event -> tick());
    private final FocusAdapter focus = new FocusAdapter() {
        @Override public void focusLost(FocusEvent event) { if (!overToolbar()) hideNow(); }
    };
    private @Nullable RangeHighlighter shown;
    private @Nullable JBPopup popup;
    private @Nullable JPanel panel;

    private SqlCardHoverController(@NotNull Editor editor) {
        this.editor = editor;
        editor.addEditorMouseMotionListener(new EditorMouseMotionListener() {
            @Override public void mouseMoved(@NotNull EditorMouseEvent event) {
                if (shown != null && !shown.isValid()) hideNow();
                moved(cardAt(editor, event.getMouseEvent().getPoint()));
            }
        }, this);
        editor.addEditorMouseListener(new EditorMouseListener() {
            @Override public void mouseExited(@NotNull EditorMouseEvent event) { moved(null); }
        }, this);
        editor.getScrollingModel().addVisibleAreaListener(event -> hideNow(), this);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) { hideNow(); }
        }, this);
        editor.getContentComponent().addFocusListener(focus);
    }

    /** Installs the controller on an editor that shows cards, for the editor's lifetime. */
    static void install(@NotNull Editor editor) {
        if (editor.getProject() == null || !SqlCardPassFactory.showsCards(editor, FileDocumentManager.getInstance().getFile(editor.getDocument()))) return;
        SqlCardHoverController controller = new SqlCardHoverController(editor);
        editor.putUserData(KEY, controller);
        com.intellij.openapi.editor.ex.util.EditorUtil.disposeWithEditor(editor, controller);
    }

    static @Nullable SqlCardHoverController of(@NotNull Editor editor) { return editor.getUserData(KEY); }

    /** The card whose painted rectangle holds {@code point}, in the editor content component's coordinates. */
    static @Nullable RangeHighlighter cardAt(@NotNull Editor editor, @NotNull Point point) {
        for (RangeHighlighter highlighter : SqlCardPass.highlighters(editor)) {
            if (SqlCardRenderer.bounds(editor, highlighter).contains(point)) return highlighter;
        }
        return null;
    }

    private void moved(@Nullable RangeHighlighter card) {
        timer.over(card, false, System.currentTimeMillis());
        if (timer.pending()) tick.start();
    }

    private void tick() {
        if (overToolbar()) timer.over(null, true, System.currentTimeMillis());
        Optional<RangeHighlighter> due = timer.due(System.currentTimeMillis());
        show(due.orElse(null));
        if (!timer.pending()) tick.stop();
    }

    /** The mouse is on the toolbar, or one of its menus is open. */
    private boolean overToolbar() {
        JPanel current = panel;
        return current != null && current.isShowing()
                && (current.getMousePosition(true) != null || !JBPopupFactory.getInstance().getChildPopups(current).isEmpty());
    }

    /** A card read before the last edit is not shown: its SQL and its actions' states may no longer hold. */
    private void show(@Nullable RangeHighlighter highlighter) {
        if (highlighter != null && !SqlCardPass.isFresh(editor, highlighter)) highlighter = null;
        if (highlighter == shown) return;
        hidePopup();
        if (highlighter == null) return;
        shown = highlighter;
        highlighter.putUserData(SqlCardPass.HOVERED, Boolean.TRUE);
        repaint(highlighter);
        if (!GraphicsEnvironment.isHeadless()) openPopup(highlighter);
    }

    private void openPopup(RangeHighlighter highlighter) {
        SqlCard card = highlighter.getUserData(SqlCardPass.CARD);
        Project project = editor.getProject();
        if (card == null || project == null) return;
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(PLACE,
                (ActionGroup) ActionManager.getInstance().getAction(TOOLBAR_GROUP), true);
        JPanel content = new CardToolbarPanel(project, card);
        toolbar.setTargetComponent(content);
        content.add(toolbar.getComponent(), BorderLayout.CENTER);
        content.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent event) { moved(null); }
        });
        panel = content;
        JBPopup opened = JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
                .setFocusable(false).setRequestFocus(false).setCancelOnClickOutside(true).setCancelKeyEnabled(true)
                .setMovable(false).setResizable(false).setShowShadow(true).createPopup();
        opened.addListener(new JBPopupListener() {
            @Override public void onClosed(@NotNull LightweightWindowEvent event) { if (event.asPopup() == popup) popupClosed(); }
        });
        popup = opened;
        // The toolbar fills itself in the background; place the popup once its buttons are there, and again if they change.
        toolbar.getComponent().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { relocate(opened, content, highlighter); }
        });
        toolbar.updateActionsAsync();
        opened.show(new RelativePoint(editor.getContentComponent(), placement(content, highlighter)));
    }

    private Point placement(JPanel content, RangeHighlighter highlighter) {
        return SqlCardToolbarPlacement.place(SqlCardRenderer.bounds(editor, highlighter), content.getPreferredSize(),
                editor.getScrollingModel().getVisibleArea());
    }

    private void relocate(JBPopup opened, JPanel content, RangeHighlighter highlighter) {
        if (opened != popup || opened.isDisposed() || !highlighter.isValid()) return;
        opened.setSize(content.getPreferredSize());
        opened.setLocation(new RelativePoint(editor.getContentComponent(), placement(content, highlighter)).getScreenPoint());
    }

    /** The platform closed the popup (a click outside, Escape): forget it, so the next hover starts afresh. */
    private void popupClosed() {
        popup = null;
        panel = null;
        timer.cancel();
        tick.stop();
        clearShown();
    }

    private void hidePopup() {
        JBPopup closing = popup;
        popup = null;
        panel = null;
        if (closing != null) closing.cancel();
        clearShown();
    }

    private void clearShown() {
        RangeHighlighter previous = shown;
        shown = null;
        if (previous != null) {
            previous.putUserData(SqlCardPass.HOVERED, null);
            repaint(previous);
        }
    }

    private void hideNow() {
        timer.cancel();
        tick.stop();
        hidePopup();
    }

    private void repaint(RangeHighlighter highlighter) {
        // The controller is disposed after its editor, which can no longer be measured by then.
        if (!highlighter.isValid() || editor.isDisposed()) return;
        Rectangle bounds = SqlCardRenderer.bounds(editor, highlighter);
        bounds.grow(2, 2);
        editor.getContentComponent().repaint(bounds);
    }

    @Override public void dispose() {
        hideNow();
        editor.getContentComponent().removeFocusListener(focus);
        editor.putUserData(KEY, null);
    }

    @TestOnly void popupClosedForTests() { popupClosed(); }

    @TestOnly void showForTests(@NotNull RangeHighlighter highlighter) { show(highlighter); }

    @TestOnly @Nullable SqlCard shownCard() { return shown == null ? null : shown.getUserData(SqlCardPass.CARD); }

    /** The toolbar's data: the card, and the editor and file it belongs to, as the run actions expect. */
    private final class CardToolbarPanel extends JPanel implements UiDataProvider {
        private final Project project;
        private final SqlCard card;

        CardToolbarPanel(Project project, SqlCard card) {
            super(new BorderLayout());
            this.project = project;
            this.card = card;
        }

        @Override public void uiDataSnapshot(@NotNull DataSink sink) {
            sink.set(SqlCardDataKeys.SQL_CARD, card);
            sink.set(CommonDataKeys.PROJECT, project);
            sink.set(CommonDataKeys.EDITOR, editor);
            sink.set(CommonDataKeys.VIRTUAL_FILE, FileDocumentManager.getInstance().getFile(editor.getDocument()));
            sink.set(CommonDataKeys.PSI_FILE, PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()));
        }
    }
}
