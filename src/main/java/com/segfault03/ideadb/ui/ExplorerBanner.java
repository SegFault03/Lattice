package com.segfault03.ideadb.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.InlineBanner;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The explorer's one error banner, between the toolbar and the tree: it names the problem and the recovery in place of
 * a modal dialog. A new error replaces the previous one; the full driver message is one click away under Details.
 */
final class ExplorerBanner extends JPanel {
    private final Project project;
    private String subject;

    ExplorerBanner(Project project) {
        super(new BorderLayout());
        this.project = project;
        setOpaque(false);
        setBorder(JBUI.Borders.empty(0, 8, 8, 8));
        setVisible(false);
    }

    /**
     * Shows {@code summary} followed by the first line of {@code detail}. {@code subject} identifies what the error is
     * about (a connection id, for example) so a later success can clear it; {@code actions} are recovery links.
     */
    void error(String subject, String summary, String detail, Map<String, Runnable> actions) {
        this.subject = subject;
        String reason = firstLine(detail);
        InlineBanner banner = new InlineBanner(reason.isEmpty() ? summary : summary + ": " + reason, EditorNotificationPanel.Status.Error);
        Map<String, Runnable> links = new LinkedHashMap<>(actions);
        if (detail != null && !detail.isBlank() && !detail.strip().equals(reason))
            links.put("Details", () -> Messages.showErrorDialog(project, detail, summary));
        links.forEach((label, action) -> banner.addAction(label, () -> { clear(); action.run(); }));
        banner.showCloseButton(true);
        banner.setCloseAction(this::clear);
        removeAll();
        add(banner, BorderLayout.CENTER);
        setVisible(true);
        revalidate();
        repaint();
    }

    /** Removes the banner if it is about {@code subject}. */
    void clear(String subject) {
        if (subject != null && subject.equals(this.subject)) clear();
    }

    void clear() {
        subject = null;
        removeAll();
        setVisible(false);
        revalidate();
        repaint();
    }

    static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.strip().split("\\R")) if (!line.isBlank()) return line.strip();
        return "";
    }
}
