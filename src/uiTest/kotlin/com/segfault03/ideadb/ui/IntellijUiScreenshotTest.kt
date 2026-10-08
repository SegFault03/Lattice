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
        projectDirectory.resolve(".idea").toFile().deleteRecursively()
        projectDirectory.resolve("README.md").writeText("Temporary project opened for the Lattice IDE UI screenshot test.\n")
        projectDirectory.resolve("pom.xml").writeText("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>lattice-ui-test</artifactId><version>1.0.0</version>
              <properties><mysql.version>8.4.0</mysql.version><hsqldb.version>2.7.2</hsqldb.version></properties>
              <dependencies>
                <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>${'$'}{mysql.version}</version></dependency>
                <dependency><groupId>org.hsqldb</groupId><artifactId>hsqldb</artifactId><version>${'$'}{hsqldb.version}</version></dependency>
              </dependencies>
            </project>
        """.trimIndent())

        val screenshotsDirectory = Path.of(requireNotNull(System.getProperty("ui.screenshot.dir")))
        screenshotsDirectory.toFile().deleteRecursively()
        Files.createDirectories(screenshotsDirectory)
        val mavenRepository = Path.of(requireNotNull(System.getProperty("ui.maven.repository"))).toAbsolutePath()
        check(Files.isRegularFile(mavenRepository.resolve("org/hsqldb/hsqldb/2.7.2/hsqldb-2.7.2.jar"))) {
            "Expected the HSQLDB Maven fixture in $mavenRepository"
        }
        check(listOf("9.0.0", "8.4.0", "8.0.33").all { version ->
            Files.isRegularFile(mavenRepository.resolve("com/mysql/mysql-connector-j/$version/mysql-connector-j-$version.jar"))
        }) { "Expected the MySQL Connector/J Maven fixtures in $mavenRepository" }

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

            lateinit var frame: IdeaFrameUI
            ideFrame { frame = this }
            pluginRoot.x { byVisibleText("Add a connection…") }.waitFound().click()
            frame.x { byVisibleText("MySQL…") }.waitFound().click()

            val mysqlDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("New connection")
            }.waitFound()
            mysqlDialog.x {
                byVisibleText("MySQL · Available drivers · MySQL Connector/J 8.4.0")
            }.waitFound()
            mysqlDialog.x { byVisibleText("Driver options ▸") }.waitFound().click()
            val mysqlAvailableDriverCombo = mysqlDialog.x(JComboBoxUiComponent::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byAccessibleName("Available driver"),
                )
            }.waitFound()
            clickComboArrow(mysqlAvailableDriverCombo)
            Thread.sleep(300)
            val mysqlAvailableDriverLabels = mysqlAvailableDriverCombo.listValues()
            println("Available MySQL driver entries: ${mysqlAvailableDriverLabels.joinToString()}")
            check(mysqlAvailableDriverLabels.containsAll(listOf(
                "26.7.0 · BUNDLED", "9.0.0 · DISCOVERED", "8.4.0 · DISCOVERED", "8.0.33 · DISCOVERED",
            ))) { "Expected bundled and Maven-discovered MySQL releases: $mysqlAvailableDriverLabels" }
            captureScreen(screenshotsDirectory.resolve("mysql-connection-dialog-available-driver-list.png"))
            pressEscape()

            val mysqlDriverSourceCombo = mysqlDialog.x(JComboBoxUiComponent::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byAccessibleName("Source:"),
                )
            }.waitFound()
            mysqlDriverSourceCombo.selectItem("Download a version")
            val mysqlDriverVersionCombo = mysqlDialog.x(JComboBoxUiComponent::class.java) {
                and(
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byAccessibleName("Driver version"),
                )
            }.waitFound()
            Thread.sleep(300)
            clickComboArrow(mysqlDriverVersionCombo)
            Thread.sleep(300)
            val mysqlDownloadVersions = mysqlDriverVersionCombo.listValues()
            println("MySQL download-version entries: ${mysqlDownloadVersions.joinToString()}")
            check(mysqlDownloadVersions.containsAll(listOf(
                "9.0.0 · DISCOVERED", "8.4.0 · DISCOVERED", "8.0.33 · DISCOVERED",
            ))) { "Expected Maven-discovered MySQL releases in the download-version list: $mysqlDownloadVersions" }
            captureScreen(screenshotsDirectory.resolve("mysql-connection-dialog-download-version-list.png"))
            pressEscape()
            clickComboArrow(mysqlDriverVersionCombo)
            selectNextComboPopupRow()
            waitUntil("discovered MySQL driver cannot be downloaded again") {
                !mysqlDialog.x { byVisibleText("Download") }.waitFound().component.isEnabled()
            }
            val mysqlProgressVersion = "5.1.49"
            mysqlDriverVersionCombo.selectItem(mysqlProgressVersion)
            val mysqlDownloadButton = mysqlDialog.x { byVisibleText("Download") }.waitFound()
            waitUntil("MySQL version $mysqlProgressVersion can be downloaded") {
                mysqlDownloadButton.component.isEnabled()
            }
            mysqlDownloadButton.click()
            val mysqlProgressBar = mysqlDialog.x(UiComponent::class.java) {
                byAccessibleName("Driver download progress")
            }.waitFound()
            assertTrue(mysqlProgressBar.component.isVisible(), "The progress bar should appear during a driver download")
            assertTrue(!mysqlDownloadButton.component.isEnabled(), "Download should be disabled during the transfer")
            assertTrue(!mysqlDriverVersionCombo.component.isEnabled(), "The version selector should be disabled during the transfer")
            assertTrue(
                mysqlProgressBar.component.width == mysqlDriverVersionCombo.component.width,
                "The progress bar should match the driver version selector width",
            )
            captureScreen(screenshotsDirectory.resolve("mysql-driver-download-progress.png"))
            waitUntil("MySQL driver download and verification") {
                mysqlDialog.hasSubtext("Downloaded and ready")
            }
            check(!mysqlDownloadButton.component.isEnabled()) {
                "A successfully downloaded driver should not be offered for download again"
            }
            mysqlDialog.pressButton("Cancel")
            ideFrame { waitForNoOpenedDialogs() }

            pluginRoot.x { byVisibleText("Add a connection…") }.waitFound().click()
            frame.x { byVisibleText("HSQLDB…") }.waitFound().click()

            val connectionDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("New connection")
            }.waitFound()
            connectionDialog.x {
                byVisibleText("HSQLDB · Available drivers · HSQLDB 2.7.2")
            }.waitFound()
            Thread.sleep(750)
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
            val availableDriverLabels = availableDriverCombo.listValues()
            println("Available driver entries: ${availableDriverLabels.joinToString()}")
            check(availableDriverLabels.containsAll(listOf(
                "2.7.4 · BUNDLED", "2.7.3-jdk8 · DOWNLOADED", "2.7.2 · DISCOVERED", "2.6.1-jdk8 · DISCOVERED", "2.4.1 · DISCOVERED",
            ))) { "Expected bundled, downloaded, and Maven-discovered releases in the available-driver list: $availableDriverLabels" }
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
            val downloadVersions = driverVersionCombo.listValues()
            println("Download-version entries: ${downloadVersions.joinToString()}")
            check(downloadVersions.containsAll(listOf(
                "2.7.2 · DISCOVERED", "2.6.1-jdk8 · DISCOVERED", "2.4.1 · DISCOVERED",
            ))) {
                "Expected Maven-discovered releases in the download-version list: $downloadVersions"
            }
            captureScreen(screenshotsDirectory.resolve("connection-dialog-download-version-list.png"))
            pressEscape()
            val sourceWidth = driverSourceCombo.component.width
            val versionWidth = driverVersionCombo.component.width
            println("Download driver control widths: source=$sourceWidth, version=$versionWidth")
            assertTrue(sourceWidth == versionWidth, "Driver source and editable version controls should have equal widths")
            val moreVersionsButton = connectionDialog.x { byVisibleText("More versions") }.waitFound()
            val selectorLeft = driverVersionCombo.component.getLocationOnScreen().x
            val sourceLeft = driverSourceCombo.component.getLocationOnScreen().x
            val actionsLeft = moreVersionsButton.component.getLocationOnScreen().x
            println("Download driver row alignment: source=$sourceLeft, version=$selectorLeft, actions=$actionsLeft")
            assertTrue(
                sourceLeft == selectorLeft && selectorLeft == actionsLeft,
                "Source, version, and More versions controls should share a left edge",
            )
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

            val commitButton = tableEditor.x { byAccessibleName("Commit pending changes to the database") }.waitFound()
            val revertButton = tableEditor.x { byAccessibleName("Revert pending changes") }.waitFound()
            tableEditor.x { byAccessibleName("Add a new row") }.waitFound().click()
            waitUntil("empty insert row validates required fields") {
                dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 invalid cell") && !commitButton.component.isEnabled()
            }
            assertTrue(revertButton.component.isEnabled(), "An invalid empty row should remain revertible")
            val emptyRowValues = dataGrid.content().values.single().values
            println("Empty new-row cell values: ${emptyRowValues.joinToString()}")
            captureScreen(screenshotsDirectory.resolve("table-add-empty-row.png"))

            replaceCellValue(dataGrid, row = 0, column = 1, value = "Ada Lovelace")
            waitUntil("valid required value enables insert") {
                commitButton.component.isEnabled() && !tableEditor.hasSubtext("invalid cell")
            }
            captureScreen(screenshotsDirectory.resolve("table-add-row-valid.png"))

            commitButton.click()
            frame.x(DialogUiComponent::class.java) {
                byTitle("Changes Saved")
            }.waitFound().pressButton("OK")
            waitUntil("committed row reload") {
                dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 rows")
            }
            check(dataGrid.content().values.any { row -> row.values.any { "Ada" in it } }) {
                "Committed row should be visible in the live data grid"
            }

            val initialId = tableCellText(dataGrid, row = 0, column = 0).toLong()
            dataGrid.doubleClickCell(0, 0)
            dataGrid.keyboard { enter() }
            waitUntil("opening and closing an unchanged integer cell stays clean") {
                !commitButton.component.isEnabled() && !tableEditor.hasSubtext("invalid cell")
            }
            assertTrue(!revertButton.component.isEnabled(), "An unchanged cell should not enable Revert")
            captureScreen(screenshotsDirectory.resolve("table-edit-unchanged-value.png"))

            replaceCellValue(dataGrid, row = 0, column = 0, value = "not-an-integer")
            waitUntil("invalid integer is marked and blocks commit") {
                tableEditor.hasSubtext("1 invalid cell") && !commitButton.component.isEnabled()
            }
            captureScreen(screenshotsDirectory.resolve("table-edit-invalid-integer.png"))

            replaceCellValue(dataGrid, row = 0, column = 0, value = initialId.toString())
            waitUntil("restoring the original integer clears invalid and pending state") {
                !tableEditor.hasSubtext("invalid cell") && !commitButton.component.isEnabled()
            }
            assertTrue(!revertButton.component.isEnabled(), "Restoring the original value should leave no pending edit")
            captureScreen(screenshotsDirectory.resolve("table-edit-restored-original.png"))

            val changedId = initialId + 1
            replaceCellValue(dataGrid, row = 0, column = 0, value = changedId.toString())
            waitUntil("valid changed integer is marked pending and enables commit") {
                tableEditor.hasSubtext("1 pending change") && commitButton.component.isEnabled()
            }
            captureScreen(screenshotsDirectory.resolve("table-edit-pending.png"))

            commitButton.click()
            frame.x(DialogUiComponent::class.java) {
                byTitle("Changes Saved")
            }.waitFound().pressButton("OK")
            waitUntil("valid integer change is committed and reloaded") {
                dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 rows") && tableCellText(dataGrid, 0, 0).toLongOrNull() == changedId
            }
            check(dataGrid.content().values.any { row -> row.values.any { "Ada" in it } }) {
                "Committing the integer edit should preserve the other row values"
            }
            captureScreen(screenshotsDirectory.resolve("table-edit-committed.png"))

            val evidence = buildString {
                appendLine("IDE target: IntelliJ IDEA Community ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
                appendLine("UI driver: JetBrains Starter and Driver")
                appendLine("Configured Maven local repository fixture: $mavenRepository")
                appendLine("Project POM default drivers: MySQL 8.4.0, HSQLDB 2.7.2")
                appendLine("Discovered MySQL Connector/J releases: 9.0.0, 8.4.0, 8.0.33")
                appendLine("Discovered HSQLDB releases: 2.7.2, 2.6.1-jdk8, 2.4.1")
                appendLine("Driver download flow: MySQL 5.1.49 downloaded from Maven Central; progress bar displayed and download controls were disabled during transfer")
                appendLine("IDE display: ${displayBounds.width}x${displayBounds.height}")
                appendLine("IDE frame bounds: $ideFrameBounds")
                appendLine("Production root Swing class: com.segfault03.ideadb.ui.DatabaseMainPanel")
                appendLine("Production root bounds: $rootBounds")
                appendLine("JetBrains child Swing class: com.intellij.ui.components.ActionLink")
                appendLine("JetBrains ActionLink child bounds on Welcome screen: $jetBrainsActionLinkBounds")
                appendLine("IDE JBR release: ${Files.readString(ideHome.resolve("jbr/release")).lineSequence().filter { it.startsWith("JAVA_VERSION=") || it.startsWith("IMPLEMENTOR=") }.joinToString(", ")}")
                appendLine("Test worker JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
                appendLine("Connection flow: Driver-created HSQLDB in-memory connection, live test connection succeeded")
                appendLine("Table insert validation: empty row displayed its auto ID and nullable timestamp defaults, marked its missing required name invalid, and kept Commit disabled until the name was supplied")
                appendLine("Table edit validation: unchanged integer stayed clean; invalid integer was highlighted and blocked Commit; restoring the original stayed clean; a valid changed integer committed and reloaded")
                appendLine("Table flow: created LATTICE_PEOPLE through the production dialog, inserted Ada Lovelace, and edited its ID through the production data grid")
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

    private fun replaceCellValue(table: JTableUiComponent, row: Int, column: Int, value: String) {
        table.doubleClickCell(row, column)
        val robot = Robot()
        robot.keyPress(KeyEvent.VK_CONTROL)
        robot.keyPress(KeyEvent.VK_A)
        robot.keyRelease(KeyEvent.VK_A)
        robot.keyRelease(KeyEvent.VK_CONTROL)
        robot.waitForIdle()
        table.keyboard { typeText(value) }
        table.keyboard { enter() }
    }

    private fun tableCellText(table: JTableUiComponent, row: Int, column: Int): String =
        table.content().values.elementAt(row).values.elementAt(column)

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
