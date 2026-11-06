package com.segfault03.ideadb.sql.status;

import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Lattice is doing for each file right now, for the editor's status icon: finding the SQL in it, or checking that
 * SQL. Work is reported against the host file, so SQL injected into a Java string counts for the Java file. Files that
 * have had Lattice SQL checked are remembered, so the icon also shows in a file whose cards are turned off.
 */
public final class LatticeActivity {
    public enum Stage {
        READING("Finding SQL"), CHECKING("Checking SQL");
        public final String text;
        Stage(String text) { this.text = text; }
    }

    /** Ends a stage; close it exactly once, in a finally block. */
    public interface Running extends AutoCloseable { @Override void close(); }

    private static final Running NOTHING = () -> {};
    private static final Map<String, int[]> RUNNING = new ConcurrentHashMap<>();
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private LatticeActivity() {}

    public static @NotNull Running begin(@Nullable VirtualFile file, @NotNull Stage stage) {
        if (file == null) return NOTHING;
        String url = SqlBindingsState.hostOf(file).getUrl();
        if (stage == Stage.CHECKING) SEEN.add(url);
        RUNNING.compute(url, (key, counts) -> {
            int[] next = counts == null ? new int[Stage.values().length] : counts;
            next[stage.ordinal()]++;
            return next;
        });
        changed();
        return () -> {
            RUNNING.computeIfPresent(url, (key, counts) -> {
                counts[stage.ordinal()] = Math.max(0, counts[stage.ordinal()] - 1);
                for (int count : counts) if (count > 0) return counts;
                return null;
            });
            changed();
        };
    }

    /** The stage running for the file, checking first; null when Lattice is not working on it. */
    public static @Nullable Stage current(@NotNull VirtualFile file) {
        int[] counts = RUNNING.get(SqlBindingsState.hostOf(file).getUrl());
        if (counts == null) return null;
        if (counts[Stage.CHECKING.ordinal()] > 0) return Stage.CHECKING;
        if (counts[Stage.READING.ordinal()] > 0) return Stage.READING;
        return null;
    }

    /** Whether Lattice has checked SQL in the file during this session. */
    public static boolean hasSql(@NotNull VirtualFile file) { return SEEN.contains(SqlBindingsState.hostOf(file).getUrl()); }

    /** Asks the toolbars to update, the editor's status icon among them. */
    public static void changed() {
        if (ApplicationManager.getApplication() != null) ActivityTracker.getInstance().inc();
    }
}
