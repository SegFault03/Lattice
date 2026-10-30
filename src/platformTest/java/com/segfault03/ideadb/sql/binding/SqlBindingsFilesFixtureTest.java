package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** A file's binding follows it when it is renamed or moved, directly or through its directory, and goes when it is deleted. */
public class SqlBindingsFilesFixtureTest extends BasePlatformTestCase {
    private SqlBindingsState bindings;

    @Override protected void setUp() throws Exception {
        super.setUp();
        bindings = SqlBindingsState.getInstance(getProject());
        bindings.loadState(new SqlBindingsState.State());
    }

    @Override protected void tearDown() throws Exception {
        try {
            bindings.loadState(new SqlBindingsState.State());
        } finally {
            super.tearDown();
        }
    }

    private VirtualFile file(String path) { return myFixture.addFileToProject(path, "select 1").getVirtualFile(); }

    private void run(ThrowingRunnable action) {
        try {
            WriteAction.runAndWait(action::run);
        } catch (RuntimeException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }

    private interface ThrowingRunnable { void run() throws IOException; }

    private String connectionOf(VirtualFile file) {
        var binding = bindings.fileBinding(file);
        return binding == null ? null : binding.connectionId;
    }

    public void testRenamingAFileKeepsItsBinding() {
        VirtualFile file = file("src/a.sql");
        bindings.bindFileToConnection(file, "conn-1");
        long before = bindings.getModificationCount();
        run(() -> file.rename(this, "b.sql"));
        assertEquals("conn-1", connectionOf(file));
        assertTrue(bindings.getModificationCount() > before);
        assertEquals(1, bindings.getState().files.size());
    }

    public void testRenamingOnlyTheCaseOfAFileKeepsItsBinding() {
        VirtualFile file = file("src/case.sql");
        bindings.bindFileToDialect(file, "generic");
        run(() -> file.rename(this, "CASE.sql"));
        assertEquals("generic", bindings.fileBinding(file).dialectId);
    }

    public void testMovingAFileKeepsItsBinding() {
        VirtualFile file = file("src/a.sql");
        VirtualFile target = file("other/keep.txt").getParent();
        bindings.bindFileToConnection(file, "conn-1");
        run(() -> file.move(this, target));
        assertEquals(target, file.getParent());
        assertEquals("conn-1", connectionOf(file));
        assertEquals(1, bindings.getState().files.size());
    }

    public void testRenamingADirectoryRewritesEveryBindingUnderIt() {
        VirtualFile first = file("dir/x.sql");
        VirtualFile nested = file("dir/sub/y.sql");
        VirtualFile sibling = file("dir2/z.sql");
        bindings.bindFileToConnection(first, "conn-1");
        bindings.bindFileToDialect(nested, "postgresql");
        bindings.bindFileToConnection(sibling, "conn-2");
        run(() -> first.getParent().rename(this, "renamed"));
        assertEquals("conn-1", connectionOf(first));
        assertEquals("postgresql", bindings.fileBinding(nested).dialectId);
        assertEquals("a directory whose name only starts the same is not under it", "conn-2", connectionOf(sibling));
        assertTrue(bindings.getState().files.keySet().stream().anyMatch(url -> url.endsWith("/renamed/sub/y.sql")));
        assertEquals(3, bindings.getState().files.size());
    }

    public void testMovingADirectoryRewritesEveryBindingUnderIt() {
        VirtualFile nested = file("dir/sub/y.sql");
        VirtualFile target = file("elsewhere/keep.txt").getParent();
        bindings.bindFileToConnection(nested, "conn-1");
        run(() -> nested.getParent().getParent().move(this, target));
        assertEquals("conn-1", connectionOf(nested));
        assertTrue(bindings.getState().files.keySet().stream().anyMatch(url -> url.endsWith("/elsewhere/dir/sub/y.sql")));
    }

    public void testDeletingAFileDropsItsBinding() {
        VirtualFile file = file("src/a.sql");
        VirtualFile kept = file("src/b.sql");
        bindings.bindFileToConnection(file, "conn-1");
        bindings.bindFileToConnection(kept, "conn-2");
        run(() -> file.delete(this));
        assertEquals(Set.of(kept.getUrl()), bindings.getState().files.keySet());
    }

    public void testDeletingADirectoryDropsEveryBindingUnderIt() {
        VirtualFile first = file("dir/x.sql");
        VirtualFile nested = file("dir/sub/y.sql");
        VirtualFile sibling = file("dir2/z.sql");
        bindings.bindFileToConnection(first, "conn-1");
        bindings.bindFileToConnection(nested, "conn-1");
        bindings.bindFileToConnection(sibling, "conn-2");
        run(() -> first.getParent().delete(this));
        assertEquals(Set.of(sibling.getUrl()), bindings.getState().files.keySet());
    }

    public void testBindingsOfFilesThatNoLongerExistArePrunedAtOpen() throws IOException {
        Path present = Files.createTempFile("lattice-binding", ".sql");
        Path gone = Files.createTempFile("lattice-binding", ".sql");
        try {
            bindings.getState().files.put("file://" + present.toString().replace('\\', '/'), new SqlBindingsState.Binding("conn-1", ""));
            bindings.getState().files.put("file://" + gone.toString().replace('\\', '/'), new SqlBindingsState.Binding("conn-2", ""));
            bindings.getState().files.put("temp:///src/not-on-disk.sql", new SqlBindingsState.Binding("conn-3", ""));
            Files.delete(gone);
            assertTrue(bindings.pruneMissingFiles());
            assertEquals(Set.of("file://" + present.toString().replace('\\', '/'), "temp:///src/not-on-disk.sql"), bindings.getState().files.keySet());
            assertFalse("nothing left to prune", bindings.pruneMissingFiles());
        } finally {
            Files.deleteIfExists(present);
            Files.deleteIfExists(gone);
        }
    }
}
