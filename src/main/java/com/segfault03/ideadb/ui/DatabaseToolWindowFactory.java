package com.segfault03.ideadb.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.util.ui.JBUI;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.SwingUtilities;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;

public class DatabaseToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setIcon(Icons.LATTICE);
        toolWindow.setDefaultState(toolWindow.getAnchor(), ToolWindowType.DOCKED,
                new Rectangle(0, 0, JBUI.scale(260), JBUI.scale(400)));
        DatabaseMainPanel panel = new DatabaseMainPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
        applyCompactWidth(project, toolWindow, panel);

        if (DatabaseSettingsState.getInstance().isShowWelcomeScreen()) {
            DatabaseEditorManager.getInstance(project).openWelcome();
        }
    }

    private static void applyCompactWidth(Project project, ToolWindow toolWindow, DatabaseMainPanel panel) {
        if (!(toolWindow instanceof ToolWindowEx resizableWindow)) return;
        PropertiesComponent preferences = PropertiesComponent.getInstance(project);
        String widthApplied = "lattice.explorer.compactWidthApplied";
        if (preferences.getBoolean(widthApplied, false)) return;
        // Docked widths are independent of floating bounds. Apply the new default once,
        // after the window has a real size, then preserve subsequent user resizing.
        ComponentAdapter listener = new ComponentAdapter() {
            private void apply() {
                SwingUtilities.invokeLater(() -> {
                    if (project.isDisposed() || Disposer.isDisposed(panel) || preferences.getBoolean(widthApplied, false)
                            || !toolWindow.isVisible() || toolWindow.getComponent().getWidth() <= 0) return;
                    if (toolWindow.getAnchor().isHorizontal()) return;
                    preferences.setValue(widthApplied, true);
                    resizableWindow.stretchWidth(JBUI.scale(260) - toolWindow.getComponent().getWidth());
                    toolWindow.getComponent().removeComponentListener(this);
                });
            }
            @Override public void componentShown(ComponentEvent event) { apply(); }
            @Override public void componentResized(ComponentEvent event) { apply(); }
        };
        toolWindow.getComponent().addComponentListener(listener);
        Disposer.register(panel, () -> toolWindow.getComponent().removeComponentListener(listener));
        if (toolWindow.isVisible()) listener.componentShown(null);
    }
}
