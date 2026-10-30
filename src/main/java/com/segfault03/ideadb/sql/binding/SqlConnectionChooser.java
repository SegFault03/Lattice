package com.segfault03.ideadb.sql.binding;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.ListPopupStepEx;
import com.intellij.openapi.ui.popup.ListSeparator;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.StatusText;
import com.segfault03.ideadb.model.ConnectionConfig;
import com.segfault03.ideadb.sql.dialect.SqlDialects;
import com.segfault03.ideadb.sql.discovery.DiscoveredConnection;
import com.segfault03.ideadb.sql.discovery.ProjectConnectionOffer;
import com.segfault03.ideadb.state.DatabaseSettingsState;
import com.segfault03.ideadb.ui.Icons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Popups for picking the connection that supplies the dialect, for one file or for the whole project. Saved
 * connections come first, each with its database icon and type; the other choices follow a separator. The row that is
 * in effect is the one preselected.
 */
public final class SqlConnectionChooser {
    private SqlConnectionChooser() {}

    enum Kind { CONNECTION, GENERIC, FOLLOW_DEFAULT, ADD_FOUND, SET_PROJECT_DEFAULT }

    /** One row of the popup; {@code secondary} is the greyed text after the label. */
    record Choice(@NotNull Kind kind, @NotNull String label, @Nullable String secondary, @Nullable Icon icon,
                  @Nullable ConnectionConfig connection, @Nullable DiscoveredConnection found) {
        @Override public String toString() { return label; }
    }

    /** What a popup shows: its title, its rows in order, and the index of the row in effect. */
    record Model(@NotNull String title, @NotNull List<Choice> choices, int defaultIndex) {}

    // ---- showing ---------------------------------------------------------------------------------------------------

    public static void chooseForFile(@NotNull Project project, @NotNull VirtualFile file) {
        chooseForFile(project, file, editorOf(project, file));
    }

    /** Shows the chooser at the caret of {@code editor}, or centered in the window when there is none. */
    public static void chooseForFile(@NotNull Project project, @NotNull VirtualFile file, @Nullable Editor editor) {
        show(project, filePopup(project, file, editor), editor);
    }

    public static void chooseForProject(@NotNull Project project) { chooseForProject(project, null); }

    public static void chooseForProject(@NotNull Project project, @Nullable Editor editor) {
        show(project, projectPopup(project), editor);
    }

    /** The popup for a file, not yet shown: the status bar shows it above its entry. */
    public static @NotNull ListPopup filePopup(@NotNull Project project, @NotNull VirtualFile file, @Nullable Editor editor) {
        return popup(fileModel(project, file), choice -> applyToFile(project, file, editor, choice));
    }

    public static @NotNull ListPopup projectPopup(@NotNull Project project) {
        return popup(projectModel(project), choice -> applyToProject(project, choice));
    }

    private static void show(Project project, ListPopup popup, @Nullable Editor editor) {
        if (editor != null && editor.getContentComponent().isShowing()) popup.showInBestPositionFor(editor);
        else popup.showCenteredInCurrentWindow(project);
    }

    static @Nullable Editor editorOf(Project project, VirtualFile file) {
        for (FileEditor editor : FileEditorManager.getInstance(project).getEditors(file)) {
            if (editor instanceof TextEditor text) return text.getEditor();
        }
        return null;
    }

    // ---- what is chosen --------------------------------------------------------------------------------------------

    private static void applyToFile(Project project, VirtualFile file, @Nullable Editor editor, Choice choice) {
        switch (choice.kind()) {
            case ADD_FOUND -> ProjectConnectionOffer.add(project, choice.found(), file);
            case SET_PROJECT_DEFAULT -> chooseForProject(project, editor);
            case CONNECTION -> {
                SqlBindingsState.getInstance(project).bindFileToConnection(file, choice.connection().getId());
                SqlEditorRefresh.refresh(project, file);
            }
            case GENERIC -> {
                SqlBindingsState.getInstance(project).bindFileToDialect(file, SqlDialects.ANSI.id());
                SqlEditorRefresh.refresh(project, file);
            }
            case FOLLOW_DEFAULT -> {
                SqlBindingsState.getInstance(project).clearFile(file);
                SqlEditorRefresh.refresh(project, file);
            }
        }
    }

    private static void applyToProject(Project project, Choice choice) {
        SqlBindingsState.getInstance(project).setDefaultConnectionId(choice.kind() == Kind.CONNECTION ? choice.connection().getId() : "");
        SqlEditorRefresh.refreshAll(project);
    }

    // ---- the rows --------------------------------------------------------------------------------------------------

    static @NotNull Model fileModel(@NotNull Project project, @NotNull VirtualFile file) {
        List<Choice> choices = connectionChoices();
        Choice generic = genericChoice();
        choices.add(generic);
        SqlBindingsState bindings = SqlBindingsState.getInstance(project);
        SqlBindingsState.Binding binding = bindings.fileBinding(file);
        if (binding != null) {
            choices.add(new Choice(Kind.FOLLOW_DEFAULT, "Follow project default (" + SqlDialectResolver.describeProjectDefault(project) + ")", null, null, null, null));
        }
        for (DiscoveredConnection found : ProjectConnectionOffer.open(project)) {
            choices.add(new Choice(Kind.ADD_FOUND, "Add " + found.describe() + "…", null, AllIcons.General.Add, null, found));
        }
        choices.add(new Choice(Kind.SET_PROJECT_DEFAULT, "Set project default…", null, null, null, null));
        ConnectionConfig current = SqlDialectResolver.connection(project, file);
        return new Model("SQL connection for " + file.getName(), choices, indexOf(choices, current, generic));
    }

    static @NotNull Model projectModel(@NotNull Project project) {
        List<Choice> choices = connectionChoices();
        Choice generic = genericChoice();
        choices.add(generic);
        String id = SqlBindingsState.getInstance(project).defaultConnectionId();
        ConnectionConfig chosen = id.isEmpty() ? null : DatabaseSettingsState.getInstance().getConnection(id);
        return new Model("Default SQL connection for this project", choices, indexOf(choices, chosen, generic));
    }

    private static Choice genericChoice() { return new Choice(Kind.GENERIC, "Generic SQL", null, null, null, null); }

    private static int indexOf(List<Choice> choices, @Nullable ConnectionConfig current, Choice fallback) {
        if (current != null) {
            for (int i = 0; i < choices.size(); i++) {
                ConnectionConfig connection = choices.get(i).connection();
                if (connection != null && connection.getId().equals(current.getId())) return i;
            }
        }
        return choices.indexOf(fallback);
    }

    private static List<Choice> connectionChoices() {
        List<Choice> choices = new ArrayList<>();
        for (ConnectionConfig config : DatabaseSettingsState.getInstance().getConnections()) {
            choices.add(new Choice(Kind.CONNECTION, config.getName(), config.getType().getDisplayName(), Icons.dataSource(config.getType(), 16), config, null));
        }
        return choices;
    }

    // ---- the popup -------------------------------------------------------------------------------------------------

    private static ListPopup popup(Model model, Consumer<Choice> onChosen) {
        return JBPopupFactory.getInstance().createListPopup(new Step(model, onChosen));
    }

    /** The list step: the first row that is not a connection starts a new section. */
    private static final class Step extends BaseListPopupStep<Choice> implements ListPopupStepEx<Choice> {
        private final Consumer<Choice> onChosen;

        Step(Model model, Consumer<Choice> onChosen) {
            super(model.title(), model.choices());
            this.onChosen = onChosen;
            setDefaultOptionIndex(model.defaultIndex());
        }

        @Override public @Nullable PopupStep<?> onChosen(Choice choice, boolean finalChoice) { return doFinalStep(() -> onChosen.accept(choice)); }
        @Override public @NotNull String getTextFor(Choice choice) { return choice.label(); }
        @Override public @Nullable Icon getIconFor(Choice choice) { return choice.icon(); }
        @Override public @Nullable String getSecondaryTextFor(Choice choice) { return choice.secondary(); }
        @Override public @Nullable String getTooltipTextFor(Choice choice) { return null; }
        @Override public void setEmptyText(@NotNull StatusText emptyText) {}

        @Override public @Nullable ListSeparator getSeparatorAbove(Choice choice) {
            List<Choice> values = getValues();
            int index = values.indexOf(choice);
            return index > 0 && choice.kind() != Kind.CONNECTION && values.get(index - 1).kind() == Kind.CONNECTION ? new ListSeparator() : null;
        }
    }
}
