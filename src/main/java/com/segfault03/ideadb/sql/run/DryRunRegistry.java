package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The connections of this project with a dry run in flight. Unlike the tab, it outlives a closed tab, so a second run
 * cannot start (and compete for row locks) while the first one is still going.
 */
@Service(Service.Level.PROJECT)
public final class DryRunRegistry {
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public static DryRunRegistry getInstance(@NotNull Project project) { return project.getService(DryRunRegistry.class); }

    /** False when the connection already has a run. */
    public boolean tryBegin(@NotNull String connectionId) { return busy.add(connectionId); }

    public void end(@NotNull String connectionId) { busy.remove(connectionId); }

    public boolean isBusy(@NotNull String connectionId) { return busy.contains(connectionId); }
}
