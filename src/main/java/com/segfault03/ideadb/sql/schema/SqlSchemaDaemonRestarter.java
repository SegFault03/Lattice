package com.segfault03.ideadb.sql.schema;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

/**
 * Highlights and inspections only look at the cached schema, so when a background read finishes the open editors
 * have to be analyzed again. Registered in plugin.xml, so it goes away with the plugin.
 */
public final class SqlSchemaDaemonRestarter implements SqlSchemaListener {
    @Override public void schemaChanged(@NotNull String connectionId) {
        ApplicationManager.getApplication().getService(Restarts.class).request();
    }

    /** Per-table reloads announce many results in a row; one restart covers them all. */
    @Service(Service.Level.APP)
    static final class Restarts implements Disposable {
        private static final int COALESCE_MILLIS = 300;
        private final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);

        void request() {
            if (alarm.isEmpty()) alarm.addRequest(Restarts::restartAll, COALESCE_MILLIS);
        }

        private static void restartAll() {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (!project.isDisposed()) DaemonCodeAnalyzer.getInstance(project).restart();
            }
        }

        @Override public void dispose() {}
    }
}
