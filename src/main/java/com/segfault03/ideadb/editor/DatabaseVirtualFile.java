package com.segfault03.ideadb.editor;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Objects;

public abstract class DatabaseVirtualFile extends LightVirtualFile {
    private final String fileKey;

    public DatabaseVirtualFile(@NotNull String name, @NotNull String fileKey, @NotNull FileType fileType) {
        super(name, fileType, "");
        this.fileKey = fileKey;
    }

    public String getFileKey() {
        return fileKey;
    }

    public abstract JComponent createComponent(@NotNull Project project);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DatabaseVirtualFile that = (DatabaseVirtualFile) o;
        return Objects.equals(fileKey, that.fileKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileKey);
    }

    @Override
    public boolean isValid() {
        return true;
    }
}
