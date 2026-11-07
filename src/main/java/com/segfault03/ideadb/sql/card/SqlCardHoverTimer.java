package com.segfault03.ideadb.sql.card;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * When the hover toolbar shows and hides. A card shows once the mouse has rested on it for {@link #SHOW_DELAY_MS}, so
 * sweeping across a file shows nothing; it hides once the mouse has been off both the card and the toolbar for
 * {@link #HIDE_DELAY_MS}, which leaves time to travel from the card to the toolbar.
 */
final class SqlCardHoverTimer<T> {
    static final long SHOW_DELAY_MS = 150;
    static final long HIDE_DELAY_MS = 300;

    private @Nullable T shown;
    private @Nullable T candidate;
    private long candidateSince;
    private long leftSince = -1;

    /** Where the mouse is now: over {@code card} (null for none), or over the toolbar. */
    void over(@Nullable T card, boolean overToolbar, long nowMs) {
        if (overToolbar || (card != null && Objects.equals(card, shown))) {
            candidate = null;
            leftSince = -1;
            return;
        }
        if (card == null) {
            candidate = null;
        } else if (!Objects.equals(card, candidate)) {
            candidate = card;
            candidateSince = nowMs;
        }
        if (shown != null && leftSince < 0) leftSince = nowMs;
    }

    /** The card whose toolbar should show at {@code nowMs}, or empty when none should. */
    @NotNull Optional<T> due(long nowMs) {
        if (candidate != null && nowMs - candidateSince >= SHOW_DELAY_MS) {
            shown = candidate;
            candidate = null;
            leftSince = -1;
        } else if (shown != null && leftSince >= 0 && nowMs - leftSince >= HIDE_DELAY_MS) {
            shown = null;
            leftSince = -1;
        }
        return Optional.ofNullable(shown);
    }

    /** Whether a show or hide is still to come, so the caller knows to keep checking. */
    boolean pending() { return candidate != null || leftSince >= 0; }

    void cancel() {
        shown = null;
        candidate = null;
        leftSince = -1;
    }
}
