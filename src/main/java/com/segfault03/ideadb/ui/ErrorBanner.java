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
 * A surface's one error banner (explorer, table editor): it names the problem and the recovery in place of a modal
 * dialog. A new message replaces the previous one; the full driver message is one click away under Details.
 */
final class ErrorBanner extends JPanel {
    private final Project project;
    private String subject;

    ErrorBanner(Project project, Insets padding) {
        super(new BorderLayout());
        this.project = project;
        setOpaque(false);
        setBorder(JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right));
        setVisible(false);
    }

    /**
     * Shows {@code summary} followed by the first line of {@code detail}. {@code subject} identifies what the error is
     * about (a connection id, for example) so a later success can clear it; {@code actions} are recovery links.
     */
    void error(String subject, String summary, String detail, Map<String, Runnable> actions) {
        show(EditorNotificationPanel.Status.Error, subject, summary, detail, actions);
    }

    /** Like {@link #error}, for a request Lattice refused before contacting the database. */
    void warning(String subject, String summary, String detail, Map<String, Runnable> actions) {
        show(EditorNotificationPanel.Status.Warning, subject, summary, detail, actions);
    }

    private void show(EditorNotificationPanel.Status status, String subject, String summary, String detail, Map<String, Runnable> actions) {
        this.subject = subject;
        String reason = firstLine(detail);
        InlineBanner banner = new InlineBanner(reason.isEmpty() ? summary : summary + ": " + reason, status);
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
