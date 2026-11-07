package com.segfault03.ideadb.sql

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SqlEditorStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        SqlEditorSupport.initialize(project)
    }
}
