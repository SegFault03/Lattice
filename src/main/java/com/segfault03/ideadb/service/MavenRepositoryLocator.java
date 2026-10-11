package com.segfault03.ideadb.service;

import com.intellij.openapi.project.Project;

import java.lang.reflect.Method;
import java.nio.file.Path;

/** Resolves the effective Maven local repository configured for the current IDE project. */
public final class MavenRepositoryLocator {
    private MavenRepositoryLocator() {}

    public static Path localRepository(Project project) {
        if (project != null) {
            try {
                ClassLoader loader = MavenRepositoryLocator.class.getClassLoader();
                Class<?> managerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager", true, loader);
                Method getInstance = managerClass.getMethod("getInstance", Project.class);
                Object manager = getInstance.invoke(null, project);
                String fixtureRepository = System.getProperty("lattice.maven.repository");
                if (fixtureRepository != null && !fixtureRepository.isBlank()) {
                    // Isolated UI runs can point Maven's actual project settings at a fixture repo.
                    Object settings = managerClass.getMethod("getGeneralSettings").invoke(manager);
                    settings.getClass().getMethod("setLocalRepository", String.class)
                            .invoke(settings, fixtureRepository);
                }
                // Use the non-modal API on a worker. Other callers on the EDT retain Maven's UI-safe getter.
                String getter = javax.swing.SwingUtilities.isEventDispatchThread()
                        ? "getRepositoryPathUnderModalProgress" : "getRepositoryPath";
                Object repository = managerClass.getMethod(getter).invoke(manager);
                Path configured = repository instanceof Path path ? path : null;
                if (configured != null) return configured;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // An IDE may not have initialized Maven settings yet; use Maven's standard default.
            }
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath();
    }
}
