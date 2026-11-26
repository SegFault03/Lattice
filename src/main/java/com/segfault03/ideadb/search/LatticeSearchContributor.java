package com.segfault03.ideadb.search;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.psi.codeStyle.MinusculeMatcher;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.speedSearch.SpeedSearchUtil;
import com.intellij.util.Processor;
import com.segfault03.ideadb.editor.DatabaseEditorManager;
import com.segfault03.ideadb.model.TableMetadata;
import com.segfault03.ideadb.service.DdlService;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.state.MetadataCacheState;
import com.segfault03.ideadb.state.RecentItemsState;
import com.segfault03.ideadb.ui.DatabaseMainPanel;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.event.InputEvent;
import java.util.List;

/** The Lattice tab in Search Everywhere: Double Shift, then Tab to Lattice. Enter opens; Alt+Enter opens a SQL console. */
public final class LatticeSearchContributor implements SearchEverywhereContributor<LatticeSearch.Item> {
    private final Project project;
    private volatile String pattern = "";

    LatticeSearchContributor(Project project) { this.project = project; }

    public static final class Factory implements SearchEverywhereContributorFactory<LatticeSearch.Item> {
        @Override public @NotNull SearchEverywhereContributor<LatticeSearch.Item> createContributor(@NotNull AnActionEvent event) {
            return new LatticeSearchContributor(event.getRequiredData(CommonDataKeys.PROJECT));
        }
        @Override public boolean isAvailable(Project project) { return project != null; }
    }

    @Override public @NotNull String getSearchProviderId() { return "LatticeSearchEverywhereContributor"; }
    @Override public @NotNull String getGroupName() { return "Lattice"; }
    @Override public @NotNull String getFullGroupName() { return "Lattice: recent items, tables and connections"; }
    @Override public int getSortWeight() { return 1000; }
    @Override public boolean showInFindResults() { return false; }
    @Override public boolean isShownInSeparateTab() { return true; }
    @Override public boolean isEmptyPatternSupported() { return true; }
    @Override public boolean isDumbAware() { return true; }
    @Override public String getAdvertisement() { return "Enter opens · Alt+Enter opens a SQL console · searches loaded schemas only"; }

    @Override public void fetchElements(@NotNull String pattern, @NotNull ProgressIndicator indicator,
                                        @NotNull Processor<? super LatticeSearch.Item> consumer) {
        if (project.isDisposed()) return;
        this.pattern = pattern;
        List<LatticeSearch.Item> items = LatticeSearch.search(pattern, DatabaseSettingsState.getInstance().getConnections(),
                RecentItemsState.getInstance(project).items(), MetadataCacheState.getInstance()::tables);
        for (LatticeSearch.Item item : items) {
            indicator.checkCanceled();
            if (!consumer.process(item)) return;
        }
    }

    @Override public boolean processSelectedItem(@NotNull LatticeSearch.Item item, int modifiers, @NotNull String searchText) {
        // Search Everywhere passes either extended or legacy modifier flags depending on the platform version.
        @SuppressWarnings("deprecation") boolean console = (modifiers & (InputEvent.ALT_DOWN_MASK | InputEvent.ALT_MASK)) != 0;
        DatabaseEditorManager editors = DatabaseEditorManager.getInstance(project);
        switch (item.kind()) {
            case RECENT -> {
                if (item.table() == null || console) editors.openConsole(item.connection(), item.database(), null);
                else editors.openTableData(item.connection(), item.database(), copy(item.table()));
            }
            case TABLE -> {
                if (console) editors.openConsole(item.connection(), item.database(), "SELECT * FROM "
                        + DdlService.formatTable(item.connection(), item.database(), item.table().getName()) + " LIMIT 100;\n");
                else editors.openTableData(item.connection(), item.database(), copy(item.table()));
            }
            case CONNECTION -> {
                if (console) editors.openConsole(item.connection(), null, null);
                else DatabaseMainPanel.revealConnection(project, item.connection().getId());
            }
            case CONSOLE -> editors.openConsole(item.connection(), null, null);
            case NEW_CONNECTION -> SwingUtilities.invokeLater(() -> {
                if (!project.isDisposed()) DatabaseMainPanel.addConnection(project, item.type());
            });
        }
        return true;
    }

    /** The editor fills in columns; each open gets its own metadata object. */
    private static TableMetadata copy(TableMetadata table) {
        return new TableMetadata(table.getCatalog(), table.getSchema(), table.getName(), table.getType(), table.getComment());
    }

    @Override public @NotNull ListCellRenderer<? super LatticeSearch.Item> getElementsRenderer() { return renderer(() -> pattern); }

    /** Icon, title with the query's matches in bold, then the muted context. Public for the headless previews. */
    public static ListCellRenderer<LatticeSearch.Item> renderer(java.util.function.Supplier<String> pattern) {
        return new ColoredListCellRenderer<LatticeSearch.Item>() {
            @Override protected void customizeCellRenderer(@NotNull JList<? extends LatticeSearch.Item> list, LatticeSearch.Item item,
                                                           int index, boolean selected, boolean focused) {
                setIcon(icon(item));
                MinusculeMatcher matcher = LatticeSearch.matcher(pattern.get());
                SimpleTextAttributes plain = SimpleTextAttributes.REGULAR_ATTRIBUTES;
                if (matcher == null || item.kind() == LatticeSearch.Kind.CONSOLE || item.kind() == LatticeSearch.Kind.NEW_CONNECTION)
                    append(item.title(), plain);
                else SpeedSearchUtil.appendColoredFragmentForMatcher(item.title(), this, plain, matcher, getBackground(), selected);
                append("  " + item.context(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                setToolTipText(item.title() + " · " + item.context());
            }
        };
    }

    private static Icon icon(LatticeSearch.Item item) {
        return switch (item.kind()) {
            case RECENT -> item.table() == null ? Icons.CONSOLE : item.isView() ? Icons.VIEW : Icons.TABLE;
            case TABLE -> item.isView() ? Icons.VIEW : Icons.TABLE;
            case CONNECTION -> Icons.dataSource(item.type(), 16);
            case CONSOLE -> Icons.CONSOLE;
            case NEW_CONNECTION -> AllIcons.General.Add;
        };
    }

    @Override public Object getDataForItem(@NotNull LatticeSearch.Item element, @NotNull String dataId) { return null; }
}
