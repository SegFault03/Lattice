package com.segfault03.ideadb.ui

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTableUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class IntellijUiScreenshotTest {
    @Test
    fun captureLatticeToolWindowFromRealIde() {
        val projectDirectory = Path.of(System.getProperty("user.dir"), "build", "ui-test-project")
        Files.createDirectories(projectDirectory)
        projectDirectory.resolve("README.md").writeText("Temporary project opened for the Lattice IDE UI screenshot test.\n")

        val screenshotsDirectory = Path.of(requireNotNull(System.getProperty("ui.screenshot.dir")))
        Files.createDirectories(screenshotsDirectory)

        val ideHome = Path.of(requireNotNull(System.getProperty("ui.ide.home")))
        val ideInfo = IdeProductProvider.IC.copy(
            buildNumber = requireNotNull(System.getProperty("ui.ide.build")),
            version = requireNotNull(System.getProperty("ui.ide.version")),
            getInstaller = { ExistingIdeInstaller(ideHome) },
        )

        val testContext = Starter.newContext(
            CurrentTestMethod.hyphenateWithClass(),
            TestCase(ideInfo, LocalProjectInfo(projectDirectory)),
        )
        // Starter keeps the test IDE's config between runs. Reset only this test plugin's
        // saved connections so every run begins at the same real empty-state screen.
        Files.deleteIfExists(testContext.paths.configDir.resolve("options/LatticeSettings.xml"))
        testContext.apply {
            PluginConfigurator(this).installPluginFromPath(
                Path.of(requireNotNull(System.getProperty("path.to.build.plugin"))),
            )
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            val retainedHsqlFixture = testContext.paths.systemDir
                .resolve("lattice/jdbc/hsqldb/hsqldb-2.7.3-jdk8.jar")
            Files.createDirectories(retainedHsqlFixture.parent)
            Files.copy(
                Path.of(System.getProperty("user.dir"), "lib", "hsqldb-2.7.4.jar"),
                retainedHsqlFixture,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            ideFrame {
                waitForNoOpenedDialogs()
            }

            ideFrame {
                invokeAction("com.segfault03.lattice.open")
                maximize()
                toFront()
            }

            // The first IDE run raises environment notices over the real tool window.
            // Dismiss the nonmodal "Don't show again" notices through Driver so they
            // don't cover the component screenshot.
            repeat(3) {
                var dismissNotice: UiComponent? = null
                ideFrame {
                    dismissNotice = xx { byVisibleText("Don't show again") }.list().firstOrNull()
                }
                val notice = dismissNotice ?: return@repeat
                notice.click()
                Thread.sleep(250)
            }

            lateinit var pluginRoot: UiComponent
            ideFrame {
                pluginRoot = x { byJavaClass("com.segfault03.ideadb.ui.DatabaseMainPanel") }.waitFound()
            }
            val jetBrainsActionLink = pluginRoot.x {
                byJavaClass("com.intellij.ui.components.ActionLink")
            }.waitFound()
            val jetBrainsActionLinkBounds = Rectangle(jetBrainsActionLink.component.getBounds())

            Thread.sleep(1000)

            val displayBounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
            var ideFrameBounds = Rectangle()
            ideFrame { ideFrameBounds = component.getBounds() }
            val rootLocation = pluginRoot.component.getLocationOnScreen()
            val rootBounds = Rectangle(rootLocation.x, rootLocation.y, pluginRoot.component.width, pluginRoot.component.height)
            assertTrue(rootBounds.width > 0 && rootBounds.height > 0, "Lattice tool-window root must have a visible size")
            assertTrue(displayBounds.contains(rootBounds), "Lattice tool-window root must be inside the virtual display")
            assertTrue(jetBrainsActionLink.component.width > 0, "Expected the production ActionLink inside DatabaseMainPanel")

            val robot = Robot()
            savePng(screenshotsDirectory.resolve("full-ide.png"), robot.createScreenCapture(displayBounds))
            savePng(screenshotsDirectory.resolve("plugin-ui.png"), robot.createScreenCapture(rootBounds))

            lateinit var frame: IdeaFrameUI
            ideFrame { frame = this }
            pluginRoot.x { byVisibleText("Add a connection…") }.waitFound().click()
            frame.x { byVisibleText("HSQLDB…") }.waitFound().click()

            val connectionDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("New connection")
            }.waitFound()
            connectionDialog.x {
                byVisibleText("HSQLDB · Available drivers · HSQLDB 2.7.4")
            }.waitFound()
            captureScreen(screenshotsDirectory.resolve("connection-dialog-default-driver.png"))
            val modeCombo = connectionDialog.x(JComboBoxUiComponent::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byAccessibleName("Mode:"),
                )
            }.waitFound()
            modeCombo.selectItem("In-Memory (mem)")
            connectionDialog.x(JTextFieldUI::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"),
                    byAccessibleName("Name:"),
                )
            }.waitFound().text = "Lattice UI demo"
            connectionDialog.x(JTextFieldUI::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"),
                    byAccessibleName("Database name:"),
                )
            }.waitFound().text = "lattice_ui_demo"
            connectionDialog.x(JTextFieldUI::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"),
                    byAccessibleName("User:"),
                )
            }.waitFound().text = "SA"
            captureScreen(screenshotsDirectory.resolve("connection-dialog.png"))

            connectionDialog.x { byVisibleText("Driver options ▸") }.waitFound().click()
            val availableDriverCombo = connectionDialog.x(JComboBoxUiComponent::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byAccessibleName("Available driver"),
                )
            }.waitFound()
            clickComboArrow(availableDriverCombo)
            Thread.sleep(300)
            captureScreen(screenshotsDirectory.resolve("connection-dialog-available-driver-list.png"))
            pressEscape()

            val driverSourceCombo = connectionDialog.x(JComboBoxUiComponent::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byAccessibleName("Source:"),
                )
            }.waitFound()
            driverSourceCombo.selectItem("Download a version")
            val driverVersionCombo = connectionDialog.x(JComboBoxUiComponent::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byAccessibleName("Driver version"),
                )
            }.waitFound()
            Thread.sleep(500)
            clickComboArrow(driverVersionCombo)
            Thread.sleep(300)
            captureScreen(screenshotsDirectory.resolve("connection-dialog-download-version-list.png"))
            pressEscape()
            val sourceWidth = driverSourceCombo.component.width
            val versionWidth = driverVersionCombo.component.width
            println("Download driver control widths: source=$sourceWidth, version=$versionWidth")
            assertTrue(sourceWidth == versionWidth, "Driver source and editable version controls should have equal widths")
            clickComboArrow(driverVersionCombo)
            Thread.sleep(200)
            selectNextComboPopupRow()
            pressEscape()
            connectionDialog.x {
                byVisibleText("HSQLDB · Download a version · HSQLDB 2.7.3-jdk8")
            }.waitFound()
            val downloadButton = connectionDialog.x { byVisibleText("Download") }.waitFound()
            waitUntil("Download disabled for an already downloaded driver") {
                !downloadButton.component.isEnabled()
            }
            connectionDialog.x { byVisibleText("Downloaded and ready") }.waitFound()
            captureScreen(screenshotsDirectory.resolve("connection-dialog-download-driver-full.png"))
            captureComponent(screenshotsDirectory.resolve("connection-dialog-download-driver.png"), connectionDialog)
            driverSourceCombo.selectItem("Available drivers")

            connectionDialog.x { byVisibleText("Test connection") }.waitFound().click()
            waitUntil("successful HSQLDB connection test") {
                connectionDialog.hasSubtext("Connected · HSQL Database Engine")
            }
            Thread.sleep(500)
            captureScreen(screenshotsDirectory.resolve("connection-tested.png"))
            connectionDialog.pressButton("Add connection")
            ideFrame {
                waitForNoOpenedDialogs()
            }

            lateinit var databaseTree: JTreeUiComponent
            ideFrame {
                databaseTree = x(JTreeUiComponent::class.java) {
                    byJavaClass("com.intellij.ui.treeStructure.Tree")
                }.waitFound()
            }
            databaseTree.fixture.expandRow(1)
            val connectionPath = arrayOf("Data Sources", "Lattice UI demo [HSQLDB] (connected)")
            val publicSchemaPath = connectionPath + "PUBLIC"
            waitUntil("connected HSQLDB schema in the explorer") {
                databaseTree.pathExists(*publicSchemaPath)
            }
            captureScreen(screenshotsDirectory.resolve("connected-explorer.png"))
            captureComponent(screenshotsDirectory.resolve("connected-plugin-ui.png"), pluginRoot)

            val publicSchemaPathText = publicSchemaPath.joinToString(databaseTree.fixture.separator())
            databaseTree.fixture.expandPath(publicSchemaPathText)
            val tablesPath = publicSchemaPath + "Tables (0)"
            waitUntil("empty Tables folder") { databaseTree.pathExists(*tablesPath) }
            databaseTree.fixture.rightClickPath(publicSchemaPathText)
            frame.x { byVisibleText("Create table…") }.waitFound().click()

            val createTableDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("Create table in PUBLIC")
            }.waitFound()
            createTableDialog.x(JTextFieldUI::class.java) {
                byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField")
            }.waitFound().text = "LATTICE_PEOPLE"
            Thread.sleep(500)
            captureScreen(screenshotsDirectory.resolve("create-table-dialog.png"))
            createTableDialog.pressButton("Create table")
            frame.x(DialogUiComponent::class.java) {
                byTitle("Table created")
            }.waitFound().pressButton("OK")
            ideFrame {
                waitForNoOpenedDialogs()
            }

            val peoplePath = publicSchemaPath + "Tables (1)" + "LATTICE_PEOPLE"
            waitUntil("created LATTICE_PEOPLE table in the explorer") {
                databaseTree.pathExists(*peoplePath)
            }
            databaseTree.fixture.doubleClickPath(peoplePath.joinToString(databaseTree.fixture.separator()))

            lateinit var tableEditor: UiComponent
            tableEditor = frame.x { byJavaClass("com.segfault03.ideadb.ui.TableDataEditorPanel") }.waitFound()
            val dataGrid = tableEditor.x(JTableUiComponent::class.java) {
                byJavaClass("com.segfault03.ideadb.ui.DatabaseTable")
            }.waitFound()
            waitUntil("empty table data loaded") { tableEditor.hasSubtext("0 rows") }
            check(dataGrid.rowCount() == 0) { "New LATTICE_PEOPLE table should start empty" }
            captureScreen(screenshotsDirectory.resolve("table-editor-empty.png"))
            captureComponent(screenshotsDirectory.resolve("table-editor-empty-component.png"), tableEditor)

            tableEditor.x { byAccessibleName("Add a new row") }.waitFound().click()
            waitUntil("new editable row") { dataGrid.rowCount() == 1 }
            dataGrid.doubleClickCell(0, 1)
            dataGrid.keyboard { typeText("Ada Lovelace") }
            dataGrid.keyboard { enter() }
            waitUntil("edited cell text") {
                dataGrid.content().values.any { row -> row.values.any { "Ada" in it } }
            }
            tableEditor.x { byAccessibleName("Commit pending changes to the database") }.waitFound()
            captureScreen(screenshotsDirectory.resolve("table-edit-pending.png"))
            captureComponent(screenshotsDirectory.resolve("table-edit-pending-component.png"), tableEditor)

            tableEditor.x { byAccessibleName("Commit pending changes to the database") }.waitFound().click()
            frame.x(DialogUiComponent::class.java) {
                byTitle("Changes Saved")
            }.waitFound().pressButton("OK")
            waitUntil("committed row reload") {
                dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 rows")
            }
            check(dataGrid.content().values.any { row -> row.values.any { "Ada" in it } }) {
                "Committed row should be visible in the live data grid"
            }
            captureScreen(screenshotsDirectory.resolve("table-edit-committed.png"))
            captureComponent(screenshotsDirectory.resolve("table-edit-committed-component.png"), tableEditor)

            val evidence = buildString {
                appendLine("IDE target: IntelliJ IDEA Community ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
                appendLine("UI driver: JetBrains Starter and Driver")
                appendLine("IDE display: ${displayBounds.width}x${displayBounds.height}")
                appendLine("IDE frame bounds: $ideFrameBounds")
                appendLine("Production root Swing class: com.segfault03.ideadb.ui.DatabaseMainPanel")
                appendLine("Production root bounds: $rootBounds")
                appendLine("JetBrains child Swing class: com.intellij.ui.components.ActionLink")
                appendLine("JetBrains ActionLink child bounds on Welcome screen: $jetBrainsActionLinkBounds")
                appendLine("IDE JBR release: ${Files.readString(ideHome.resolve("jbr/release")).lineSequence().filter { it.startsWith("JAVA_VERSION=") || it.startsWith("IMPLEMENTOR=") }.joinToString(", ")}")
                appendLine("Test worker JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
                appendLine("Connection flow: Driver-created HSQLDB in-memory connection, live test connection succeeded")
                appendLine("Table flow: created LATTICE_PEOPLE through the production dialog, inserted Ada Lovelace through the production data grid, committed, and reloaded")
                appendLine("Table editor production Swing class: com.segfault03.ideadb.ui.TableDataEditorPanel")
                appendLine("Database grid production Swing class: com.segfault03.ideadb.ui.DatabaseTable")
            }
            Files.writeString(screenshotsDirectory.resolve("runtime-evidence.txt"), evidence)
            println(evidence)
            println("Saved real IDE screenshots to $screenshotsDirectory")
        }
    }

    private fun savePng(path: Path, image: BufferedImage) {
        check(ImageIO.write(image, "png", path.toFile())) { "No PNG ImageIO writer available" }
    }

    private fun captureScreen(path: Path) {
        val bounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
        savePng(path, Robot().createScreenCapture(bounds))
    }

    private fun captureComponent(path: Path, component: UiComponent) {
        val screen = Rectangle(Toolkit.getDefaultToolkit().screenSize)
        val location = component.component.getLocationOnScreen()
        val bounds = Rectangle(location.x, location.y, component.component.width, component.component.height)
        assertTrue(bounds.width > 0 && bounds.height > 0, "Captured component must have a visible size")
        assertTrue(screen.contains(bounds), "Captured component must remain inside the virtual display")
        savePng(path, Robot().createScreenCapture(bounds))
    }

    private fun clickComboArrow(combo: JComboBoxUiComponent) {
        val location = combo.component.getLocationOnScreen()
        val robot = Robot()
        robot.mouseMove(location.x + combo.component.width - 6, location.y + combo.component.height / 2)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        robot.waitForIdle()
    }

    private fun selectNextComboPopupRow() {
        val robot = Robot()
        robot.keyPress(KeyEvent.VK_DOWN)
        robot.keyRelease(KeyEvent.VK_DOWN)
        robot.keyPress(KeyEvent.VK_ENTER)
        robot.keyRelease(KeyEvent.VK_ENTER)
        robot.waitForIdle()
    }

    private fun pressEscape() {
        val robot = Robot()
        robot.keyPress(KeyEvent.VK_ESCAPE)
        robot.keyRelease(KeyEvent.VK_ESCAPE)
        robot.waitForIdle()
    }

    private fun waitUntil(description: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        assertTrue(condition(), "Timed out waiting for $description")
    }
}
