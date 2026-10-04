package com.vibe.ideadb;

import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import com.vibe.ideadb.editor.*;
import com.vibe.ideadb.model.ConnectionConfig;
import com.vibe.ideadb.model.DatabaseType;
import com.vibe.ideadb.model.TableMetadata;

import java.util.List;

public class EditorIntegrationTest {
    public static void main(String[] args) {
        try {
            System.out.println("Starting Database Editor Integration Tests...");

            testFileTypes();
            testVirtualFileEqualityAndKeys();
            testFileEditorProviderAcceptance();

            System.out.println("\nALL EDITOR INTEGRATION TESTS PASSED! (100% OK)");
        } catch (Throwable t) {
            System.err.println("EDITOR TEST FAILED:");
            t.printStackTrace();
            throw new AssertionError("Integration suite failed",t);
        }
    }

    private static void testFileTypes() {
        System.out.print("[TEST] File Types & Icons... ");
        if (!"dbtable".equals(DatabaseFileTypes.TABLE.getDefaultExtension())) {
            throw new AssertionError("TABLE extension mismatch");
        }
        if (!"dbsql".equals(DatabaseFileTypes.CONSOLE.getDefaultExtension())) {
            throw new AssertionError("CONSOLE extension mismatch");
        }
        if (!"dbwelcome".equals(DatabaseFileTypes.WELCOME.getDefaultExtension())) {
            throw new AssertionError("WELCOME extension mismatch");
        }
        System.out.println("PASSED!");
    }

    private static void testVirtualFileEqualityAndKeys() {
        System.out.print("[TEST] Virtual Files Equality & Keys... ");
        ConnectionConfig cfg = new ConnectionConfig(DatabaseType.MYSQL, "Prod MySQL");
        TableMetadata tm = new TableMetadata("shop_db", null, "customers", "TABLE");

        TableDataVirtualFile f1 = new TableDataVirtualFile(cfg, "shop_db", tm);
        TableDataVirtualFile f2 = new TableDataVirtualFile(cfg, "shop_db", tm);
        TableDataVirtualFile f3 = new TableDataVirtualFile(cfg, "shop_db", new TableMetadata("shop_db", null, "orders", "TABLE"));

        if (!f1.getFileKey().equals(f2.getFileKey())) {
            throw new AssertionError("Keys should match for identical table");
        }
        if (!f1.equals(f2)) {
            throw new AssertionError("f1 should equal f2");
        }
        if (f1.equals(f3)) {
            throw new AssertionError("f1 should not equal f3 (different table)");
        }
        if (f1.hashCode() != f2.hashCode()) {
            throw new AssertionError("Hash codes must match");
        }

        SqlConsoleVirtualFile c1 = new SqlConsoleVirtualFile(cfg, "shop_db", List.of("shop_db"));
        SqlConsoleVirtualFile c2 = new SqlConsoleVirtualFile(cfg, "shop_db", List.of("shop_db"));
        if (!c1.equals(c2)) {
            throw new AssertionError("Console files should be equal");
        }

        WelcomeVirtualFile w1 = new WelcomeVirtualFile();
        WelcomeVirtualFile w2 = new WelcomeVirtualFile();
        if (!w1.equals(w2)) {
            throw new AssertionError("Welcome files should be equal");
        }
        System.out.println("PASSED!");
    }

    private static void testFileEditorProviderAcceptance() {
        System.out.print("[TEST] File Editor Provider Acceptance & Policy... ");
        DatabaseFileEditorProvider provider = new DatabaseFileEditorProvider();
        Project project = (Project)java.lang.reflect.Proxy.newProxyInstance(Project.class.getClassLoader(), new Class[]{Project.class},
                (proxy, method, args) -> { throw new AssertionError("Provider acceptance should not inspect project services"); });

        ConnectionConfig cfg = new ConnectionConfig(DatabaseType.HSQLDB, "HSQLDB Local");
        TableMetadata tm = new TableMetadata(null, "PUBLIC", "employees", "TABLE");

        TableDataVirtualFile tableFile = new TableDataVirtualFile(cfg, "PUBLIC", tm);
        SqlConsoleVirtualFile consoleFile = new SqlConsoleVirtualFile(cfg, "PUBLIC", List.of("PUBLIC"));
        WelcomeVirtualFile welcomeFile = new WelcomeVirtualFile();
        LightVirtualFile normalFile = new LightVirtualFile("Sample.java");

        if (!provider.accept(project, tableFile)) throw new AssertionError("Provider should accept TableDataVirtualFile");
        if (!provider.accept(project, consoleFile)) throw new AssertionError("Provider should accept SqlConsoleVirtualFile");
        if (!provider.accept(project, welcomeFile)) throw new AssertionError("Provider should accept WelcomeVirtualFile");
        if (provider.accept(project, normalFile)) throw new AssertionError("Provider must NOT accept regular LightVirtualFile");

        if (provider.getPolicy() != FileEditorPolicy.HIDE_DEFAULT_EDITOR) {
            throw new AssertionError("Policy should be HIDE_DEFAULT_EDITOR");
        }
        if (!DatabaseFileEditorProvider.EDITOR_TYPE_ID.equals(provider.getEditorTypeId())) {
            throw new AssertionError("EditorTypeId mismatch");
        }
        System.out.println("PASSED!");
    }
}
