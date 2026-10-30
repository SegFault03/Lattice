package com.segfault03.ideadb.sql.binding;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

public final class SqlConnectionWidgetFactory implements StatusBarWidgetFactory {
    static final String ID = "com.segfault03.lattice.sql.connection";

    @Override public @NotNull String getId() { return ID; }
    @Override public @NotNull @Nls String getDisplayName() { return "Lattice SQL Connection"; }
    @Override public boolean isAvailable(@NotNull Project project) { return true; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) { return new SqlConnectionWidget(project); }
    @Override public boolean canBeEnabledOn(@NotNull StatusBar statusBar) { return true; }
}
