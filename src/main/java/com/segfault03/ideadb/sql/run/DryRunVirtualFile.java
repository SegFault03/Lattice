package com.segfault03.ideadb.sql.run;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.segfault03.ideadb.editor.DatabaseFileTypes;
import com.segfault03.ideadb.editor.DatabaseVirtualFile;
import com.segfault03.ideadb.model.ConnectionConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.JComponent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The "Dry Run: connection" tab: one per connection, reused by every Explain and Run and Roll Back on it. */
public final class DryRunVirtualFile extends DatabaseVirtualFile {
    private static final Key<Map<String, DryRunVirtualFile>> FILES = Key.create("lattice.dryRunFiles");
    private static volatile Tabs tabsForTests;

    private final DryRunPanel panel;

    private DryRunVirtualFile(@NotNull Project project, @NotNull ConnectionConfig config) {
        super("Dry Run: " + config.getName(), key(config), DatabaseFileTypes.DRY_RUN);
        panel = new DryRunPanel(project, config);
    }

    private static String key(ConnectionConfig config) { return "dryrun:" + config.getId(); }

    @Override public JComponent createComponent(@NotNull Project project) { return panel; }

    /** Opens the connection's tab, or selects it when it is already open; call on the EDT. */
    public static @NotNull DryRunPanel open(@NotNull Project project, @NotNull ConnectionConfig config) {
        Map<String, DryRunVirtualFile> files = files(project);
        DryRunVirtualFile file = files.get(key(config));
        if (file == null || !tabs().isOpen(project, file)) {
            file = new DryRunVirtualFile(project, config);
            files.put(key(config), file);
        }
        tabs().open(project, file);
        return file.panel;
    }

    /** The panel of the connection's tab if it is open. */
    public static @Nullable DryRunPanel existingPanel(@NotNull Project project, @NotNull ConnectionConfig config) {
        DryRunVirtualFile file = files(project).get(key(config));
        return file != null && tabs().isOpen(project, file) ? file.panel : null;
    }

    private static Map<String, DryRunVirtualFile> files(Project project) {
        Map<String, DryRunVirtualFile> files = project.getUserData(FILES);
        if (files == null) files = ((UserDataHolderEx) project).putUserDataIfAbsent(FILES, new ConcurrentHashMap<>());
        return files;
    }

    private static Tabs tabs() {
        Tabs replacement = tabsForTests;
        return replacement != null ? replacement : EDITOR_TABS;
    }

    /** Where the tab goes; replaced in tests, which have no editor window to open a non-text file in. */
    interface Tabs {
        boolean isOpen(@NotNull Project project, @NotNull VirtualFile file);
        void open(@NotNull Project project, @NotNull VirtualFile file);
    }

    private static final Tabs EDITOR_TABS = new Tabs() {
        @Override public boolean isOpen(@NotNull Project project, @NotNull VirtualFile file) { return FileEditorManager.getInstance(project).isFileOpen(file); }
        @Override public void open(@NotNull Project project, @NotNull VirtualFile file) { new OpenFileDescriptor(project, file).navigate(true); }
    };

    @TestOnly
    static void setTabsForTests(@NotNull Tabs tabs, @NotNull Disposable parent) {
        tabsForTests = tabs;
        Disposer.register(parent, () -> tabsForTests = null);
    }
}
