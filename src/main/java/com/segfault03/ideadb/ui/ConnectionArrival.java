package com.segfault03.ideadb.ui;

import com.intellij.ide.RemoteDesktopService;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.accessibility.ScreenReader;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * The explorer's one authored moment: a newly added connection's row rises 8 px into place and fades in (240 ms,
 * ease-out-quart) over an accent tint that holds 200 ms, then fades over 400 ms. The IDE has no reduced-motion setting;
 * with a screen reader active or in a remote desktop session nothing moves: the tint shows at once and fades the same way.
 */
final class ConnectionArrival {
    static final String PROPERTY = "lattice.explorer.arrival";
    static final Color ACCENT = new JBColor(new Color(0x3574F0), new Color(0x548AF7));
    private static final long RISE_MS = 240, HOLD_MS = 200, FADE_MS = 400;
    private static final float TINT = 0.22f;

    /** One painted frame: vertical offset and content opacity for the row, and the tint's opacity. */
    record Frame(int dy, float alpha, float tint) {}

    private final Map<String, Long> started = new HashMap<>();
    private final java.util.function.LongSupplier clock;
    private final Timer timer;

    ConnectionArrival(JTree tree) { this(tree, System::nanoTime); }

    /** {@code clock} returns nanoseconds; previews pass a fixed one to paint a single moment. */
    ConnectionArrival(JTree tree, java.util.function.LongSupplier clock) {
        this.clock = clock;
        timer = new Timer(16, event -> {
            long now = clock.getAsLong();
            started.values().removeIf(start -> elapsed(start, now) > HOLD_MS + FADE_MS);
            tree.repaint();
            if (started.isEmpty()) ((Timer) event.getSource()).stop();
        });
        tree.putClientProperty(PROPERTY, this);
    }

    void start(String connectionId) {
        started.put(connectionId, clock.getAsLong());
        if (!timer.isRunning()) timer.start();
    }

    void stop() { timer.stop(); started.clear(); }

    /** The frame for {@code connectionId} now, or null when it is not arriving. */
    Frame frame(String connectionId) {
        Long start = started.get(connectionId);
        return start == null ? null : frame(elapsed(start, clock.getAsLong()), reduced());
    }

    static Frame frame(long ms, boolean reduced) {
        float tint = ms <= HOLD_MS ? TINT : TINT * Math.max(0f, 1f - (ms - HOLD_MS) / (float) FADE_MS);
        if (reduced) return new Frame(0, 1f, tint);
        float progress = Math.min(1f, ms / (float) RISE_MS);
        float eased = 1f - (float) Math.pow(1f - progress, 4);
        return new Frame(Math.round(8 * (1f - eased)), eased, tint);
    }

    private static boolean reduced() {
        return ScreenReader.isActive() || RemoteDesktopService.isRemoteSession();
    }

    private static long elapsed(long start, long now) { return (now - start) / 1_000_000; }
}
