package com.segfault03.ideadb.release;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.ErrorReportSubmitter;
import com.intellij.openapi.diagnostic.IdeaLoggingEvent;
import com.intellij.openapi.diagnostic.SubmittedReportInfo;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * "Report to Lattice" in the IDE's error dialog: opens a prefilled GitHub issue in the browser. Nothing is sent by the
 * plugin itself; the user reviews the text, which can include values from their database, before submitting it.
 */
public final class GitHubErrorReporter extends ErrorReportSubmitter {
    private static final String NEW_ISSUE = "https://github.com/SegFault03/Lattice/issues/new";
    /** Browsers and GitHub reject very long URLs; the stack trace is cut to keep the link working. */
    static final int MAX_TRACE = 5000;

    @Override public @NotNull String getReportActionText() { return "Report to Lattice on GitHub"; }

    @Override public @NotNull String getPrivacyNoticeText() {
        return "Opens a new GitHub issue in your browser with the error and your IDE version. Review it before "
                + "submitting: error messages can contain table names or values from your database.";
    }

    @Override public boolean submit(IdeaLoggingEvent @NotNull [] events, @Nullable String additionalInfo,
                                    @NotNull Component parentComponent, @NotNull Consumer<? super SubmittedReportInfo> consumer) {
        BrowserUtil.browse(issueUrl(events.length == 0 ? null : events[0], additionalInfo, pluginVersion(), environment()));
        consumer.consume(new SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE));
        return true;
    }

    static @NotNull String issueUrl(@Nullable IdeaLoggingEvent event, @Nullable String additionalInfo, String version, String environment) {
        String message = event == null || event.getThrowableText() == null ? "" : event.getThrowableText().strip();
        String firstLine = message.lines().findFirst().orElse("Unexpected error");
        String trace = message.length() > MAX_TRACE ? message.substring(0, MAX_TRACE) + "\n…" : message;
        String body = "**What were you doing?**\n" + (additionalInfo == null || additionalInfo.isBlank() ? "\n" : additionalInfo.strip() + "\n")
                + "\n**Environment**\nLattice " + version + "\n" + environment + "\n"
                + "\n**Stack trace**\n```\n" + trace + "\n```\n";
        String title = firstLine.length() > 120 ? firstLine.substring(0, 119) + "…" : firstLine;
        return NEW_ISSUE + "?labels=" + encode("needs-triage") + "&title=" + encode(title) + "&body=" + encode(body);
    }

    private @NotNull String pluginVersion() {
        var descriptor = getPluginDescriptor();
        return descriptor == null ? "unknown" : descriptor.getVersion();
    }

    private static @NotNull String environment() {
        var info = ApplicationInfo.getInstance();
        return info.getFullApplicationName() + " (build " + info.getBuild().asString() + "), "
                + SystemInfo.getOsNameAndVersion() + ", Java " + SystemInfo.JAVA_RUNTIME_VERSION;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
