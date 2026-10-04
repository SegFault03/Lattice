package com.segfault03.ideadb.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.beans.PropertyChangeListener;

public class DatabaseFileEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final DatabaseVirtualFile file;
    private final JComponent component;
    private final PropertyChangeListener pendingListener;
    private final java.beans.PropertyChangeSupport changes = new java.beans.PropertyChangeSupport(this);

    public DatabaseFileEditor(@NotNull Project project, @NotNull DatabaseVirtualFile file) {
        this.project = project;
        this.file = file;
        this.component = file.createComponent(project);
        pendingListener=event -> changes.firePropertyChange("modified",event.getOldValue(),event.getNewValue());
        component.addPropertyChangeListener("pendingChanges",pendingListener);
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return component;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) @NotNull String getName() {
        return file.getName();
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return component instanceof com.segfault03.ideadb.ui.TableDataEditorPanel table && table.hasPendingChanges();
    }

    @Override
    public boolean isValid() {
        return file.isValid();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        changes.removePropertyChangeListener(listener);
    }

    @Override
    public @Nullable VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        component.removePropertyChangeListener("pendingChanges",pendingListener);
        if (component instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception e) {
                com.intellij.openapi.diagnostic.Logger.getInstance(DatabaseFileEditor.class).warn(e);
            }
        }
    }
}
