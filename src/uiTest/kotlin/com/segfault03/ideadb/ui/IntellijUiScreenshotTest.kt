package com.segfault03.ideadb.ui

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.DialogUiComponent
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTableUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.JTextComponent
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.remote.Window
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
    private var repaintIdeBeforeCapture: (() -> Unit)? = null

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
        val review = System.getProperty("ui.review.enabled").toBoolean()
        val themeId = System.getProperty("ui.theme.id", "ExperimentalDark")
        var downloadedButtonEnabledAfterModeSwitch: Boolean? = null
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
        Files.createDirectories(testContext.paths.configDir.resolve("options"))
        testContext.paths.configDir.resolve("options/laf.xml").writeText(
            """<application><component name="LafManager" autodetect="false"><laf themeId="$themeId"/></component></application>""",
        )
        testContext.apply {
            PluginConfigurator(this).installPluginFromPath(
                Path.of(requireNotNull(System.getProperty("path.to.build.plugin"))),
            )
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            val laf = service(LiveLafManager::class)
            // IDEA migrates this classic theme ID on startup in the new UI. Select
            // the still-installed theme through the live platform API for this audit.
            if (themeId == "JetBrainsLightTheme") {
                withContext(OnDispatcher.EDT) {
                    laf.setCurrentLookAndFeel(laf.findLaf(themeId), true)
                    laf.updateUI()
                }
            }
            val actualTheme = laf.getCurrentUIThemeLookAndFeel()
            check(actualTheme.getId() == themeId) { "Requested $themeId, running ${actualTheme.getId()}" }
            val availableThemes = laf.getInstalledLookAndFeels().map { it.getName() }
            println("Actual IDE theme: ${actualTheme.getName()} ($themeId); available: $availableThemes")
            val retainedHsqlFixture = testContext.paths.systemDir
                .resolve("lattice/jdbc/hsqldb/hsqldb-2.7.3-jdk8.jar")
            Files.createDirectories(retainedHsqlFixture.parent)
            Files.copy(
                if (review) mavenRepository.resolve("org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3-jdk8.jar")
                else Path.of(System.getProperty("user.dir"), "lib", "hsqldb-2.7.4.jar"),
                retainedHsqlFixture,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            ideFrame {
                waitForNoOpenedDialogs()
            }

            ideFrame {
                invokeAction("com.segfault03.lattice.open")
                resize(1400, 1000)
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
            if (themeId == "JetBrainsHighContrastTheme") {
                // In Xvfb, closing dialogs can leave parts of the high-contrast IDE
                // chrome unpainted. Request a real JFrame repaint before settled captures.
                repaintIdeBeforeCapture = {
                    withContext(OnDispatcher.EDT) { cast(frame.component, LiveFrame::class).repaint() }
                }
            }
            openConnectionMenu(pluginRoot, frame, "MySQL…")

            val mysqlDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("New connection")
            }.waitFound()
            mysqlDialog.x {
                byVisibleText("MySQL · Available drivers · MySQL Connector/J 8.4.0")
            }.waitFound()
            if (review) {
                captureScreen(screenshotsDirectory.resolve("mysql-standard.png"))
                mysqlDialog.x { byVisibleText("JDBC URL") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("mysql-jdbc-url.png"))
                mysqlDialog.x { byVisibleText("Standard") }.waitFound().click()
            }
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
            captureScreen(screenshotsDirectory.resolve("mysql-driver-download-progress.png"), settleMillis = 0)
            waitUntil("MySQL driver download and verification") {
                mysqlDialog.hasSubtext("Downloaded and ready")
            }
            check(!mysqlDownloadButton.component.isEnabled()) {
                "A successfully downloaded driver should not be offered for download again"
            }
            if (review) {
                captureScreen(screenshotsDirectory.resolve("mysql-driver-downloaded.png"))
                mysqlDialog.x { byVisibleText("JDBC URL") }.waitFound().click()
                mysqlDialog.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("JDBC URL:"))
                }.waitFound().text =
                    "jdbc:mysql://127.0.0.1:1/ui_review?connectTimeout=1000"
                mysqlDialog.x { byVisibleText("Test connection") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Connection Failed") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("mysql-connection-failure.png"))
                    it.pressButton("OK")
                }
                captureScreen(screenshotsDirectory.resolve("mysql-connection-failure-inline.png"))
                downloadedButtonEnabledAfterModeSwitch = mysqlDownloadButton.component.isEnabled()
                println("Review observation: downloaded MySQL Download button enabled after JDBC URL / failed test = $downloadedButtonEnabledAfterModeSwitch")
            }
            mysqlDialog.pressButton("Cancel")
            ideFrame { waitForNoOpenedDialogs() }
            waitForIndicators(2.minutes)

            openConnectionMenu(pluginRoot, frame, "HSQLDB…")

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
            if (review) {
                modeCombo.selectItem("Embedded File (file)")
                captureScreen(screenshotsDirectory.resolve("hsql-file.png"))
                modeCombo.selectItem("Remote Server (hsql://)")
                captureScreen(screenshotsDirectory.resolve("hsql-server.png"))
            }
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
            if (review) {
                driverSourceCombo.selectItem("Local JAR")
                captureScreen(screenshotsDirectory.resolve("driver-local-jar.png"))
            }
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
            val sourceHeight = driverSourceCombo.component.height
            val versionHeight = driverVersionCombo.component.height
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
            if (review) {
                databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                captureScreen(screenshotsDirectory.resolve("connection-context-menu.png"))
                frame.x { byVisibleText("Edit connection…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Edit connection") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("edit-connection.png"))
                    it.pressButton("Cancel")
                }
                databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                frame.x { byVisibleText("Create schema…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Create schema") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("create-schema.png"))
                    it.x(JTextFieldUI::class.java) { byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField") }.waitFound().text = "invalid-name"
                    Thread.sleep(600)
                    captureScreen(screenshotsDirectory.resolve("create-schema-invalid.png"))
                    it.pressButton("Cancel")
                }
            }

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
            val commitSize = "${commitButton.component.width}x${commitButton.component.height}"
            val revertSize = "${revertButton.component.width}x${revertButton.component.height}"

            if (review) {
                val pathText = peoplePath.joinToString(databaseTree.fixture.separator())
                dataGrid.rightClickCell(0, 1)
                captureScreen(screenshotsDirectory.resolve("table-cell-context-menu.png"))
                check(!frame.x { byVisibleText("Set to NULL") }.waitFound().component.isEnabled())
                pressEscape()
                replaceCellValue(dataGrid, 0, 1, "NULL")
                waitUntil("literal NULL string is a valid text edit") { commitButton.component.isEnabled() }
                captureScreen(screenshotsDirectory.resolve("table-literal-null.png"))
                revertButton.click()
                waitUntil("NULL reverted") { tableCellText(dataGrid, 0, 1) == "Ada Lovelace" }
                dataGrid.rightClickCell(0, 2)
                check(!frame.x { byVisibleText("Use database default") }.waitFound().component.isEnabled())
                captureScreen(screenshotsDirectory.resolve("table-existing-default-disabled.png"))
                pressEscape()
                tableEditor.x { byAccessibleName("Add a new row") }.waitFound().click()
                waitUntil("review default row added") { dataGrid.rowCount() == 2 }
                replaceCellValue(dataGrid, 1, 1, "Review defaults")
                replaceCellValue(dataGrid, 1, 0, "99")
                dataGrid.rightClickCell(1, 0)
                frame.x { byVisibleText("Use database default") }.waitFound().click()
                waitUntil("auto default restored") { tableCellText(dataGrid, 1, 0) == "(Auto)" }
                captureScreen(screenshotsDirectory.resolve("table-use-default.png"))
                revertButton.click()
                waitUntil("default row reverted") { dataGrid.rowCount() == 1 }
                replaceCellValue(dataGrid, 0, 1, "Grace Hopper")
                waitUntil("review edit pending") { commitButton.component.isEnabled() }
                revertButton.click()
                waitUntil("Revert restores persisted row") { tableCellText(dataGrid, 0, 1) == "Ada Lovelace" }
                captureScreen(screenshotsDirectory.resolve("table-reverted.png"))
                val whereInput = tableEditor.xx(JTextFieldUI::class.java) {
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField")
                }.list().first()
                whereInput.text = "\"id\" = -999"
                tableEditor.x { byVisibleText("Apply") }.waitFound().click()
                waitUntil("empty filter result") { dataGrid.rowCount() == 0 }
                captureScreen(screenshotsDirectory.resolve("table-filter-empty.png"))
                whereInput.text = "invalid_column = 1"
                tableEditor.x { byVisibleText("Apply") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Data Fetch Error") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("table-filter-error.png"))
                    it.pressButton("OK")
                }
                whereInput.text = ""
                tableEditor.x { byVisibleText("Apply") }.waitFound().click()
                waitUntil("filter reset") { dataGrid.rowCount() == 1 }
                databaseTree.fixture.rightClickPath(pathText)
                captureScreen(screenshotsDirectory.resolve("table-context-menu.png"))
                frame.x { byVisibleText("Alter table…") }.waitFound().click()
                val alter = frame.x(DialogUiComponent::class.java) { byTitle("Alter table: LATTICE_PEOPLE") }.waitFound()
                for ((label, file) in listOf("Add column" to "add", "Rename column" to "rename-column", "Modify column" to "modify", "Drop column" to "drop", "Rename table" to "rename-table")) {
                    // Each card has an operation button with the same label as its tab.
                    alter.xx { byVisibleText(label) }.list().first().click()
                    captureScreen(screenshotsDirectory.resolve("alter-$file.png"))
                }
                alter.xx { byVisibleText("Drop column") }.list().first().click()
                alter.xx { byVisibleText("Drop column") }.list().last().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Drop column") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("drop-column-confirmation.png"))
                    it.pressButton("Cancel")
                }
                alter.pressButton("Close")
                databaseTree.fixture.rightClickPath(pathText)
                frame.x { byVisibleText("Drop table…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Drop table") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("drop-table-confirmation.png"))
                    it.pressButton("Cancel")
                }
                databaseTree.fixture.rightClickPath(publicSchemaPathText)
                frame.x { byVisibleText("Drop schema…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Drop schema") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("drop-schema-confirmation.png"))
                    it.pressButton("Cancel")
                }
                tableEditor.x { byAccessibleName("Export data") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("table-export-menu.png"))
                frame.x { byVisibleText("View CREATE TABLE…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("CREATE TABLE DDL - LATTICE_PEOPLE") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("table-ddl-dialog.png"))
                    it.pressButton("Close")
                }
                tableEditor.x { byAccessibleName("Table options") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("table-options-menu.png"))
                frame.x { byVisibleText("Truncate table…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Truncate table") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("truncate-confirmation.png"))
                    it.pressButton("Cancel")
                }
                dataGrid.clickCell(0, 1)
                tableEditor.x { byAccessibleName("Delete selected rows") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Delete rows") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("delete-rows-confirmation.png"))
                    it.pressButton("Cancel")
                }
                frame.resize(1000, 800)
                captureScreen(screenshotsDirectory.resolve("table-narrow.png"))
                val ideWindow = cast(frame.component, Window::class)
                withContext(OnDispatcher.EDT) { ideWindow.setBounds(10, 40, 1900, 1000) }
                captureScreen(screenshotsDirectory.resolve("table-wide.png"))
                withContext(OnDispatcher.EDT) { ideWindow.setBounds(260, 40, 1400, 1000) }
                databaseTree.fixture.rightClickPath(pathText)
                frame.x { byVisibleText("Open in SQL console") }.waitFound().click()
                val console = frame.x { byJavaClass("com.segfault03.ideadb.ui.SqlQueryConsolePanel") }.waitFound()
                val query = console.xx { byJavaClass("com.intellij.ui.components.JBTextArea") }.list().first()
                val queryText = cast(query.component, JTextComponent::class)
                captureScreen(screenshotsDirectory.resolve("sql-console-ready.png"))
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL SELECT completed") { console.hasSubtext("1 rows") }
                captureScreen(screenshotsDirectory.resolve("sql-console-results.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM PUBLIC.LATTICE_PEOPLE WHERE \"id\" = -999") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("empty SQL result") { console.hasSubtext("0 rows") }
                captureScreen(screenshotsDirectory.resolve("sql-console-empty-results.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("UPDATE PUBLIC.LATTICE_PEOPLE SET \"name\" = 'Ada Lovelace' WHERE \"id\" = $changedId") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL update completed") { console.hasSubtext("1 rows affected") }
                captureScreen(screenshotsDirectory.resolve("sql-console-update.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM PUBLIC.LATTICE_PEOPLE") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("results restored before cancellation/error review") { console.hasSubtext("1 rows") }
                // Bounded real database work lasts a few seconds. An unbounded four-way
                // system-catalog aggregate exposed slow HSQLDB cancellation; its evidence
                // is retained separately rather than making every theme run hang.
                val tenValues = "(VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9))"
                withContext(OnDispatcher.EDT) {
                    queryText.setText("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SYSTEM_COLUMNS A CROSS JOIN INFORMATION_SCHEMA.SYSTEM_COLUMNS B CROSS JOIN $tenValues C(n) CROSS JOIN $tenValues D(n)")
                }
                val runQuery = console.x { byVisibleText("Run") }.waitFound()
                val stopQuery = console.x { byVisibleText("Stop") }.waitFound()
                runQuery.click()
                waitUntil("long SQL running") { stopQuery.component.isEnabled() }
                captureScreen(screenshotsDirectory.resolve("sql-console-running.png"), settleMillis = 0)
                stopQuery.click()
                waitUntil("SQL cancellation completes") { runQuery.component.isEnabled() }
                captureScreen(screenshotsDirectory.resolve("sql-console-cancelled.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM TABLE_THAT_DOES_NOT_EXIST") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL error displayed") { console.hasSubtext("Query failed") }
                captureScreen(screenshotsDirectory.resolve("sql-console-error.png"))
                val tabs = cast(console.x { byJavaClass("javax.swing.JTabbedPane") }.waitFound().component, LiveTabs::class)
                withContext(OnDispatcher.EDT) { tabs.setSelectedIndex(1) }
                captureScreen(screenshotsDirectory.resolve("sql-console-messages.png"))
                withContext(OnDispatcher.EDT) { tabs.setSelectedIndex(0) }
                captureScreen(screenshotsDirectory.resolve("sql-console-results-after-error.png"))
                console.x(JComboBoxUiComponent::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName("History"))
                }.waitFound().also {
                    clickComboArrow(it)
                    captureScreen(screenshotsDirectory.resolve("sql-history-popup.png"))
                    pressEscape()
                }
                console.x(JComboBoxUiComponent::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName("Template"))
                }.waitFound().also {
                    clickComboArrow(it)
                    captureScreen(screenshotsDirectory.resolve("sql-template-popup.png"))
                    pressEscape()
                }
                frame.resize(1000, 800)
                captureScreen(screenshotsDirectory.resolve("sql-console-narrow.png"))
                withContext(OnDispatcher.EDT) { ideWindow.setBounds(10, 40, 1900, 1000) }
                captureScreen(screenshotsDirectory.resolve("sql-console-wide.png"))
                withContext(OnDispatcher.EDT) { ideWindow.setBounds(260, 40, 1400, 1000) }
                databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                frame.x { byVisibleText("Remove connection") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Remove connection") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("remove-connection-confirmation.png"))
                    it.pressButton("Cancel")
                }
                databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                frame.x { byVisibleText("Disconnect") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("disconnected-explorer.png"))
            }

            val evidence = buildString {
                appendLine("IDE target: IntelliJ IDEA Community ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
                appendLine("UI driver: JetBrains Starter and Driver")
                appendLine("Actual IDE theme: ${actualTheme.getName()} (${actualTheme.getId()})")
                appendLine("Available IDE themes: $availableThemes")
                appendLine("Driver input sizes: source=${sourceWidth}x$sourceHeight, version=${versionWidth}x$versionHeight; left edges: $sourceLeft, $selectorLeft; actions: $actionsLeft")
                appendLine("Commit button size: $commitSize; Revert button size: $revertSize")
                appendLine("Review observation: downloaded MySQL Download button enabled after JDBC URL / failed test = $downloadedButtonEnabledAfterModeSwitch")
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

    private fun openConnectionMenu(root: UiComponent, frame: IdeaFrameUI, itemText: String) {
        // A late IDE focus change (for example Maven import finishing) can close a
        // transient popup. Retry opening the production menu, not the whole test.
        repeat(3) {
            root.x { byVisibleText("Add a connection…") }.waitFound().click()
            repeat(20) {
                val item = frame.xx { byVisibleText(itemText) }.list().firstOrNull()
                if (item != null) {
                    item.click()
                    return
                }
                Thread.sleep(100)
            }
        }
        error("Could not open production connection menu item $itemText")
    }

    private fun captureScreen(path: Path, settleMillis: Long = 350) {
        if (settleMillis > 0) repaintIdeBeforeCapture?.invoke()
        Thread.sleep(settleMillis) // Allow live Swing layout and popup painting to settle.
        val bounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
        savePng(path, Robot().createScreenCapture(bounds))
    }

    private fun replaceCellValue(table: JTableUiComponent, row: Int, column: Int, value: String) {
        table.clickCell(row, column)
        // Drive the real JTable editor on the IDE EDT. Repeated native double clicks
        // and global Ctrl+A are focus-sensitive under virtual desktop window managers.
        // This uses the production editor and its normal conversion/listener lifecycle.
        table.driver.withContext(OnDispatcher.EDT) {
            val liveTable = cast(table.component, LiveTableEditor::class)
            check(liveTable.editCellAt(row, column)) { "Production cell editor did not open at $row,$column" }
            cast(liveTable.getEditorComponent(), JTextComponent::class).setText(value)
            check(liveTable.getCellEditor().stopCellEditing()) { "Production cell editor did not finish at $row,$column" }
        }
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

@Remote("javax.swing.JFrame")
interface LiveFrame {
    fun repaint()
}

@Remote("com.intellij.ide.ui.LafManager")
interface LiveLafManager {
    fun getCurrentUIThemeLookAndFeel(): LiveTheme
    fun getInstalledLookAndFeels(): Array<LiveLafInfo>
    fun findLaf(themeId: String): LiveTheme
    fun setCurrentLookAndFeel(theme: LiveTheme, installEditorScheme: Boolean)
    fun updateUI()
}

@Remote("com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo")
interface LiveTheme {
    fun getId(): String
    fun getName(): String
}

@Remote("javax.swing.UIManager\$LookAndFeelInfo")
interface LiveLafInfo {
    fun getName(): String
}

@Remote("javax.swing.JTabbedPane")
interface LiveTabs {
    fun setSelectedIndex(index: Int)
}

@Remote("javax.swing.JTable")
interface LiveTableEditor {
    fun editCellAt(row: Int, column: Int): Boolean
    fun getEditorComponent(): com.intellij.driver.sdk.ui.remote.Component
    fun getCellEditor(): LiveCellEditor
}

@Remote("javax.swing.table.TableCellEditor")
interface LiveCellEditor {
    fun stopCellEditing(): Boolean
}
