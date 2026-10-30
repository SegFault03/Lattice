package com.segfault03.ideadb.sql.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.EditorNotifications
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.segfault03.ideadb.model.ConnectionConfig
import com.segfault03.ideadb.sql.binding.SqlBindingsState
import com.segfault03.ideadb.sql.binding.SqlEditorRefresh
import com.segfault03.ideadb.sql.discovery.JdbcDiscovery
import com.segfault03.ideadb.state.DatabaseSettingsState
import com.segfault03.ideadb.ui.Icons

/** Settings | Tools | Lattice SQL Editor | Project Defaults: what applies to the SQL of this project only. */
class SqlProjectConfigurable(private val project: Project) : BoundConfigurable("Project Defaults"), SearchableConfigurable {
    private val connections = CollectionComboBoxModel<Any>()
    private lateinit var useProjectFiles: Cell<JBCheckBox>
    private lateinit var offerAgain: Cell<JBCheckBox>
    private lateinit var offerRow: Row

    override fun getId(): String = "com.segfault03.lattice.sql.editor.project"

    override fun createPanel(): DialogPanel {
        val bindings = SqlBindingsState.getInstance(project)
        // The panel shows the saved state as soon as it exists, whether or not the caller resets it.
        val dialog = panel {
            row("Default connection:") {
                comboBox(connections, SimpleListCellRenderer.create<Any?> { label, value, _ ->
                    if (value is ConnectionConfig) {
                        label.text = value.name
                        label.icon = Icons.dataSource(value.type, 16)
                    } else {
                        label.text = AUTOMATIC
                    }
                }).comment("Used for SQL files and strings that have no connection of their own")
                    .onIsModified { selectedId() != bindings.defaultConnectionId() }
                    .onReset { loadConnections(bindings) }
            }
            row {
                useProjectFiles = checkBox("Find the database in the project's configuration files")
                    .comment("application.yml, persistence.xml and similar. Only the server and database are read, never passwords.")
                    .onIsModified { useProjectFiles.component.isSelected != bindings.useProjectFiles() }
                    .onReset { useProjectFiles.component.isSelected = bindings.useProjectFiles() }
            }
            offerRow = row {
                offerAgain = checkBox("Offer the databases I turned down again")
                    .comment("Applied with OK or Apply")
                    .onIsModified { offerAgain.component.isSelected }
                    .onReset { loadOffer(bindings) }
            }
        }
        dialog.reset()
        return dialog
    }

    override fun apply() {
        val bindings = SqlBindingsState.getInstance(project)
        val filesChanged = useProjectFiles.component.isSelected != bindings.useProjectFiles()
        bindings.setDefaultConnectionId(selectedId())
        bindings.useProjectFiles(useProjectFiles.component.isSelected)
        if (offerAgain.component.isSelected) bindings.clearDismissedDiscoveries()
        if (filesChanged) JdbcDiscovery.getInstance(project).refresh()
        SqlEditorRefresh.refreshAll(project)
        EditorNotifications.getInstance(project).updateAllNotifications()
        loadOffer(bindings)
    }

    private fun selectedId(): String = (connections.selected as? ConnectionConfig)?.id ?: ""

    private fun loadConnections(bindings: SqlBindingsState) {
        val saved = DatabaseSettingsState.getInstance().connections
        connections.removeAll()
        connections.add(AUTOMATIC)
        connections.add(saved)
        connections.selectedItem = saved.firstOrNull { it.id == bindings.defaultConnectionId() } ?: AUTOMATIC
    }

    /** The checkbox is a pending action, not a setting: it starts off, and is only there while some offer is turned down. */
    private fun loadOffer(bindings: SqlBindingsState) {
        val count = bindings.dismissedDiscoveryCount()
        offerAgain.component.isSelected = false
        offerAgain.component.text = if (count == 1) "Offer the 1 database I turned down again" else "Offer the $count databases I turned down again"
        offerRow.visible(count > 0)
    }

    companion object {
        /** The first entry of the connection list: no default, so the project's files and the only connection decide. */
        private const val AUTOMATIC = "Automatic (project files, or the only connection)"
    }
}
