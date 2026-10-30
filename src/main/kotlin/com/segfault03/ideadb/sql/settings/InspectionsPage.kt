package com.segfault03.ideadb.sql.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.ProjectManager
import com.intellij.profile.codeInspection.ui.ErrorsConfigurable
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionConfigTreeNode
import com.intellij.profile.codeInspection.ui.inspectionsTree.InspectionsConfigTreeTable
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Opens Settings | Editor | Inspections with the Lattice SQL group selected. */
object InspectionsPage {
    const val GROUP = "Lattice SQL"
    private const val INSPECTIONS_ID = "Errors"

    private const val POLL_MILLIS = 200
    private const val GIVE_UP_AFTER_MILLIS = 60_000
    private const val HOLD_TICKS = 3

    /**
     * The inspections page fills its tree some time after it has been shown (in the background), and a selection made before
     * that is lost. So the group is selected again every [POLL_MILLIS] until the tree under [root] has shown it for [HOLD_TICKS] ticks in a row,
     * or [GIVE_UP_AFTER_MILLIS] have passed. [root] is looked up on each try, as the window may not be active at the first one.
     */
    @JvmStatic
    @JvmOverloads
    fun selectGroup(page: ErrorsConfigurable, root: () -> Component? = { KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow }) {
        selectGroup(page, root, ::groupSelectedIn)
    }

    /** As above, with the judgement of whether the group is selected given by the caller. */
    @JvmStatic
    fun selectGroup(page: ErrorsConfigurable, root: () -> Component?, isSelected: (Component?) -> Boolean) {
        var waited = 0
        var holding = 0
        val timer = Timer(POLL_MILLIS, null)
        timer.addActionListener {
            waited += POLL_MILLIS
            val target = root()
            // The page also resets its own selection while it finishes loading, so the choice has to survive a few ticks.
            holding = if (isSelected(target)) holding + 1 else 0
            if (holding >= HOLD_TICKS || waited > GIVE_UP_AFTER_MILLIS) {
                timer.stop()
                return@addActionListener
            }
            if (holding > 0) return@addActionListener
            try {
                page.selectInspectionGroup(arrayOf(GROUP))
            } catch (notReady: RuntimeException) {
                LOG.debug("The inspections page is not ready to select $GROUP yet", notReady)
            }
        }
        timer.initialDelay = 0
        timer.start()
    }

    /** Whether the inspections tree under [root] has the Lattice SQL group selected. */
    @JvmStatic
    fun groupSelectedIn(root: Component?): Boolean {
        val container = root as? javax.swing.JComponent ?: (root as? javax.swing.RootPaneContainer)?.rootPane ?: return false
        val tree = UIUtil.findComponentsOfType(container, InspectionsConfigTreeTable::class.java).firstOrNull()?.tree ?: return false
        val node = tree.selectionPath?.lastPathComponent as? InspectionConfigTreeNode.Group ?: return false
        return node.toString() == GROUP
    }

    /** Switches the settings dialog that holds [source] to the inspections page, or opens one when there is none. */
    @JvmStatic
    fun open(source: Component?) {
        val context = source?.let { DataManager.getInstance().getDataContext(it) }
        val settings = context?.let { Settings.KEY.getData(it) }
        val errors = settings?.find(INSPECTIONS_ID)
        if (settings != null && errors != null) {
            // Already in the settings dialog: switch its page instead of opening a second dialog.
            val window = SwingUtilities.getWindowAncestor(source)
            settings.select(errors).doWhenDone {
                ConfigurableWrapper.cast(ErrorsConfigurable::class.java, errors)?.let { selectGroup(it) { window } }
            }
            return
        }
        val project = context?.let { CommonDataKeys.PROJECT.getData(it) } ?: ProjectManager.getInstance().defaultProject
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ErrorsConfigurable::class.java) { selectGroup(it) }
    }

    private val LOG = Logger.getInstance(InspectionsPage::class.java)
}
