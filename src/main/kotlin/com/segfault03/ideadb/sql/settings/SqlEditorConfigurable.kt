package com.segfault03.ideadb.sql.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.ui.AnActionButtonUpdater
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.and
import com.intellij.ui.layout.selected
import com.intellij.util.ui.UIUtil
import com.segfault03.ideadb.sql.SqlEditorSupport
import com.segfault03.ideadb.sql.coexist.UltimateDatabasePlugin
import com.segfault03.ideadb.sql.inject.SqlKnownCalls
import java.awt.Component
import javax.swing.ListSelectionModel

/** Settings | Tools | Lattice SQL Editor. */
class SqlEditorConfigurable : BoundConfigurable("Lattice SQL Editor"), SearchableConfigurable {
    private val methods = CollectionListModel<String>()

    override fun getId(): String = "com.segfault03.lattice.sql.editor"

    override fun createPanel(): DialogPanel {
        val settings = SqlEditorSettings.getInstance()
        val methodList = JBList(methods).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = VISIBLE_METHODS
        }
        val decorator = ToolbarDecorator.createDecorator(methodList).disableUpDownActions()
            .setVisibleRowCount(VISIBLE_METHODS)
            .setAddAction { addMethod(methodList) }
            .setEditAction { editMethod(methodList) }
            .setRemoveAction { removeMethod(methodList) }
        // The list and its buttons are one component for the layout, which only disables what it holds.
        var listActive = { false }
        decorator.setAddActionUpdater(AnActionButtonUpdater { listActive() })
        decorator.setEditActionUpdater(AnActionButtonUpdater { listActive() && methodList.selectedIndex >= 0 })
        decorator.setRemoveActionUpdater(AnActionButtonUpdater { listActive() && methodList.selectedIndex >= 0 })
        val methodPanel = decorator.createPanel()

        // The panel shows the saved state as soon as it exists, whether or not the caller resets it.
        val dialog = panel {
            lateinit var master: Cell<JBCheckBox>
            lateinit var completion: Cell<JBCheckBox>
            lateinit var injection: Cell<JBCheckBox>
            lateinit var knownCalls: Cell<JBCheckBox>

            row {
                master = checkBox("Enable Lattice SQL editor support")
                    .bindSelected({ settings.isEnabled() }, { if (it != settings.isEnabled()) settings.setEnabled(it) })
                if (UltimateDatabasePlugin.isActive()) {
                    master.comment("The IDE's Database plugin is enabled and provides SQL support, so this is off unless you turn it on.")
                }
            }

            group("SQL files") {
                row {
                    checkBox("Handle .sql files")
                        .comment("Replaces the IDE's highlighting-only SQL file type")
                        .bindSelected({ settings.isHandleSqlFiles() }, { settings.setHandleSqlFiles(it) })
                }
                row {
                    checkBox("Syntax highlighting for the file's dialect")
                        .bindSelected({ settings.isHighlighting() }, { settings.setHighlighting(it) })
                }
                row {
                    completion = checkBox("Code completion")
                        .comment("Keywords, functions, tables and columns of the file's connection")
                        .bindSelected({ settings.isCompletion() }, { settings.setCompletion(it) })
                }
                indent {
                    row("Keyword case when inserted:") {
                        comboBox(SqlEditorSettings.KeywordCase.values().toList())
                            .bindItem({ settings.keywordCase() }, { settings.keywordCase(it ?: SqlEditorSettings.KeywordCase.AS_TYPED) })
                    }.enabledIf(completion.component.selected)
                }
                row {
                    checkBox("Hover documentation")
                        .comment("Tables, columns, keys and comments")
                        .bindSelected({ settings.isHover() }, { settings.setHover(it) })
                }
                row {
                    checkBox("Validation")
                        .comment("Syntax errors, unknown tables and columns")
                        .bindSelected({ settings.isValidation() }, { settings.setValidation(it) })
                }
                indent {
                    row {
                        link("Configure inspections…") { openInspections(it.source as? Component) }
                    }
                }
            }.enabledIf(master.component.selected)

            group("SQL in Java and Kotlin strings") {
                row {
                    injection = checkBox("Recognize SQL in strings")
                        .bindSelected({ settings.isInjection() }, { settings.setInjection(it) })
                }
                indent {
                    row {
                        checkBox("Strings marked @Language(\"SQL\") or // language=SQL")
                            .bindSelected({ settings.isInjectExplicit() }, { settings.setInjectExplicit(it) })
                    }
                    row {
                        knownCalls = checkBox("Strings passed to these methods")
                            .bindSelected({ settings.isInjectKnownCalls() }, { settings.setInjectKnownCalls(it) })
                    }
                    row {
                        cell(methodPanel)
                            .align(AlignX.FILL)
                            .comment("Methods whose first argument is SQL. Names such as query and update count only when the string starts like a statement.")
                            .onIsModified { methodNames() != normalized(settings.knownCalls()) }
                            .onApply { applyMethods(settings) }
                            .onReset { loadMethods(settings, methodList) }
                    }
                    row {
                        checkBox("Strings that look like SQL statements (strict)")
                            .bindSelected({ settings.isInjectContent() }, { settings.setInjectContent(it) })
                    }
                }.enabledIf(injection.component.selected)
            }.enabledIf(master.component.selected)

            val active = master.component.selected.and(injection.component.selected).and(knownCalls.component.selected)
            listActive = { active() }
            active.addListener { UIUtil.setEnabled(methodPanel, it, true) }
        }
        dialog.reset()
        return dialog
    }

    override fun apply() {
        super.apply()
        SqlEditorSupport.injectionSettingsChanged()
        SqlEditorSupport.settingsChanged()
    }

    private fun methodNames(): List<String> = methods.items.toList()

    private fun applyMethods(settings: SqlEditorSettings) {
        settings.knownCalls(if (methods.isEmpty) SqlKnownCalls.DEFAULT else methodNames().joinToString("\n"))
        // An empty list means the default one, so show it.
        if (methods.isEmpty) methods.replaceAll(normalized(settings.knownCalls()))
    }

    private fun loadMethods(settings: SqlEditorSettings, list: JBList<String>) {
        methods.replaceAll(normalized(settings.knownCalls()))
        list.clearSelection()
        list.ensureIndexIsVisible(0)
    }

    private fun addMethod(list: JBList<String>) {
        val name = askMethod(list, "Add Method", "", "") ?: return
        methods.add(name)
        list.selectedIndex = methods.size - 1
        list.ensureIndexIsVisible(methods.size - 1)
    }

    private fun editMethod(list: JBList<String>) {
        val index = list.selectedIndex
        if (index < 0) return
        val name = askMethod(list, "Edit Method", methods.getElementAt(index), methods.getElementAt(index)) ?: return
        methods.setElementAt(name, index)
    }

    private fun removeMethod(list: JBList<String>) {
        val index = list.selectedIndex
        if (index < 0) return
        methods.remove(index)
        if (!methods.isEmpty) list.selectedIndex = minOf(index, methods.size - 1)
    }

    /** Asks for a method name; blank and repeated names are refused. [own] is the name being edited, which may stay. */
    private fun askMethod(parent: Component, title: String, initial: String, own: String): String? {
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String?): String? {
                val name = inputString.orEmpty().trim()
                return when {
                    name.isEmpty() -> "Enter a method name"
                    !METHOD_NAME.matches(name) -> "Use a method name such as prepareStatement"
                    methods.items.any { it != own && key(it) == key(name) } -> "$name is already in the list"
                    else -> null
                }
            }

            override fun checkInput(inputString: String?): Boolean = getErrorText(inputString) == null
            override fun canClose(inputString: String?): Boolean = checkInput(inputString)
        }
        return Messages.showInputDialog(parent, "Method name:", title, null, initial, validator)?.trim()
    }

    private fun openInspections(source: Component?) {
        val context = source?.let { DataManager.getInstance().getDataContext(it) }
        val settings = context?.let { Settings.KEY.getData(it) }
        val errors = settings?.find(INSPECTIONS_ID)
        if (settings != null && errors != null) {
            // Already in the settings dialog: switch its page instead of opening a second dialog.
            settings.select(errors).doWhenDone {
                ConfigurableWrapper.cast(ErrorsConfigurable::class.java, errors)?.selectInspectionGroup(arrayOf(INSPECTION_GROUP))
            }
            return
        }
        val project = context?.let { CommonDataKeys.PROJECT.getData(it) } ?: ProjectManager.getInstance().defaultProject
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ErrorsConfigurable::class.java) { it.selectInspectionGroup(arrayOf(INSPECTION_GROUP)) }
    }

    companion object {
        private const val VISIBLE_METHODS = 6
        private const val INSPECTIONS_ID = "Errors"
        private const val INSPECTION_GROUP = "Lattice SQL"
        private val METHOD_NAME = Regex("[\\p{L}_$][\\p{L}\\p{N}_$.]*")

        /** The name that decides whether two entries are the same method, as [SqlKnownCalls] reads them. */
        private fun key(name: String) = name.trim().substringAfterLast('.').lowercase()

        /** The saved list as the page shows it: one trimmed name per entry, no blanks. */
        internal fun normalized(text: String): List<String> = text.split(Regex("[\\r\\n,]+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
