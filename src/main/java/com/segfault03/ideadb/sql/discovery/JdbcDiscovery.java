package com.segfault03.ideadb.sql.discovery;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.EditorNotifications;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.model.DatabaseType;
import com.segfault03.ideadb.sql.binding.SqlBindingsState;
import com.segfault03.ideadb.sql.binding.SqlEditorRefresh;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The databases this project's configuration files point at, read in the background and kept as a snapshot that editor
 * features can read at any time. Passwords and user names are never read (see {@link ProjectConfigScanner}).
 */
@Service(Service.Level.PROJECT)
public final class JdbcDiscovery implements Disposable {
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final Set<String> GENERATED_DIRECTORIES = Set.of("build", "target", "out", "node_modules", "bin", ".gradle", ".git", ".idea");

    private final Project project;
    private volatile List<DiscoveredConnection> found = List.of();
    private volatile boolean scanned;
    /** What the saved connections make of one snapshot; dropped when the snapshot or the saved connections change. */
    private volatile Matches matches;

    private record Matches(List<DiscoveredConnection> snapshot, DatabaseSettingsState settings, long modification,
                           @Nullable String matchedId, List<DiscoveredConnection> unmatched, @Nullable DatabaseType impliedType) {}

    public JdbcDiscovery(@NotNull Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    if (ProjectConfigScanner.isConfigFileName(nameOf(event)) && inProject(event)) { refresh(); return; }
                }
            }
        });
    }

    public static @NotNull JdbcDiscovery getInstance(@NotNull Project project) { return project.getService(JdbcDiscovery.class); }

    /** What was found by the last scan; empty until the first one finishes. */
    public @NotNull List<DiscoveredConnection> snapshot() { return enabled() ? found : List.of(); }

    /** Whether this project reads its configuration files (a per-project setting). */
    public boolean enabled() { return SqlBindingsState.getInstance(project).useProjectFiles(); }

    /** Whether a scan has finished since the project opened. */
    public boolean hasScanned() { return scanned; }

    /** Looks for configuration files again, off the UI thread. Concurrent requests share one scan. */
    public void refresh() {
        if (project.isDisposed() || !enabled()) return;
        ReadAction.nonBlocking(() -> scan(project)).inSmartMode(project).expireWith(this).coalesceBy(this)
                .finishOnUiThread(ModalityState.nonModal(), this::publish).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
    }

    /** Scans now, on the calling thread, for a caller that needs the result before it continues. Needs indexes: call in smart mode. */
    public void refreshNow() {
        if (!project.isDisposed()) publish(ReadAction.compute(() -> scan(project)));
    }

    private void publish(List<DiscoveredConnection> result) {
        boolean changed = !result.equals(found);
        found = List.copyOf(result);
        scanned = true;
        if (!changed || project.isDisposed()) return;
        EditorNotifications.getInstance(project).updateAllNotifications();
        SqlEditorRefresh.refreshAll(project);
    }

    // ---- what the saved connections make of it -----------------------------------------------------------------------

    /** The one saved connection the project's files point at, or null when none does or several different ones do. */
    public @Nullable ConnectionConfig matchedConnection() {
        String id = current().matchedId();
        return id == null ? null : DatabaseSettingsState.getInstance().getConnection(id);
    }

    /** The discovered databases that no saved connection points at: the ones worth offering to add. */
    public @NotNull List<DiscoveredConnection> unmatched() { return current().unmatched(); }

    /** The database type every discovered URL agrees on: the dialect to use when no connection is chosen. */
    public @Nullable DatabaseType impliedType() { return current().impliedType(); }

    /** These are asked on every completion, hover and keystroke handler, so they are worked out once per snapshot and settings change. */
    private Matches current() {
        List<DiscoveredConnection> snapshot = snapshot();
        DatabaseSettingsState settings = DatabaseSettingsState.getInstance();
        long modification = settings.modificationCount(); // before reading the connections: a later change only causes a recompute
        Matches known = matches;
        if (known != null && known.snapshot() == snapshot && known.settings() == settings && known.modification() == modification) return known;
        List<ConnectionConfig> saved = settings.getConnections();
        Set<String> hits = new LinkedHashSet<>();
        List<DiscoveredConnection> open = new ArrayList<>();
        DatabaseType type = null;
        boolean agree = true;
        for (DiscoveredConnection discovered : snapshot) {
            boolean matched = false;
            for (ConnectionConfig connection : saved) {
                if (!discovered.url().matches(connection)) continue;
                hits.add(connection.getId());
                matched = true;
            }
            if (!matched) open.add(discovered);
            if (type != null && type != discovered.url().type()) agree = false;
            type = discovered.url().type();
        }
        Matches computed = new Matches(snapshot, settings, modification, hits.size() == 1 ? hits.iterator().next() : null, List.copyOf(open), agree ? type : null);
        matches = computed;
        return computed;
    }

    // ---- scanning ---------------------------------------------------------------------------------------------------

    private static List<DiscoveredConnection> scan(Project project) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        List<DiscoveredConnection> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        FilenameIndex.processAllFileNames(name -> {
            if (ProjectConfigScanner.isConfigFileName(name)) names.add(name);
            return true;
        }, scope, null);
        for (String name : names) {
            for (VirtualFile file : FilenameIndex.getVirtualFilesByName(name, scope)) {
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                if (!file.isValid() || file.isDirectory() || file.getLength() > MAX_FILE_BYTES || inGeneratedDirectory(project, file)) continue;
                String text;
                try { text = new String(file.contentsToByteArray(), file.getCharset() != null ? file.getCharset() : StandardCharsets.UTF_8); }
                catch (java.io.IOException unreadable) { continue; }
                String path = relative(project, file);
                for (ParsedJdbcUrl url : ProjectConfigScanner.urlsIn(name, text)) result.add(new DiscoveredConnection(url, name, path));
            }
        }
        result.sort(Comparator.comparing(DiscoveredConnection::path));
        return result;
    }

    private static boolean inGeneratedDirectory(Project project, VirtualFile file) {
        String[] segments = relative(project, file).split("/");
        for (int i = 0; i < segments.length - 1; i++) if (GENERATED_DIRECTORIES.contains(segments[i])) return true;
        return false;
    }

    /** The file's path from its content root, which is what tells a source file from a build output copy of it. */
    private static String relative(Project project, VirtualFile file) {
        VirtualFile root = ProjectFileIndex.getInstance(project).getContentRootForFile(file);
        String relative = root == null ? null : VfsUtilCore.getRelativePath(file, root);
        return relative != null ? relative : file.getName();
    }

    /** Whether the event touches a path under one of this project's content roots; a deleted file has no VirtualFile, so paths are compared. */
    private boolean inProject(VFileEvent event) {
        List<String> paths = new ArrayList<>(List.of(event.getPath()));
        if (event instanceof VFileMoveEvent move) paths.add(move.getOldPath());
        else if (event instanceof VFilePropertyChangeEvent change && change.isRename()) paths.add(change.getOldPath());
        String[] roots = ReadAction.compute(() -> Arrays.stream(ProjectRootManager.getInstance(project).getContentRoots()).map(VirtualFile::getPath).toArray(String[]::new));
        for (String path : paths) {
            for (String root : roots) if (path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/")) return true;
        }
        return false;
    }

    private static String nameOf(VFileEvent event) {
        String path = event.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override public void dispose() {}
}
