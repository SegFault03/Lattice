package com.segfault03.ideadb.ui

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.ui
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
import java.awt.Point
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
        val styleOnly = System.getProperty("ui.style.only").toBoolean()
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
        Files.createDirectories(projectDirectory.resolve(".idea"))
        projectDirectory.resolve(".idea/workspace.xml").writeText("""
            <project version="4"><component name="MavenImportPreferences">
              <option name="generalSettings"><MavenGeneralSettings>
                <option name="localRepository" value="${mavenRepository.toString().replace("&", "&amp;").replace("\"", "&quot;")}"/>
              </MavenGeneralSettings></option>
            </component></project>
        """.trimIndent())

        val ideHome = Path.of(requireNotNull(System.getProperty("ui.ide.home")))
        val productCode = System.getProperty("ui.ide.product", "IC")
        val ideInfo = (if (productCode == "IU") IdeProductProvider.IU else IdeProductProvider.IC).copy(
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
        // Starter's first-session marker triggers config migration and automatic trial
        // plugin reloads in unified IDEA. Use a normal initialized test profile.
        Files.deleteIfExists(testContext.paths.configDir.resolve("migrate.config"))
        testContext.paths.configDir.resolve("options/ide.general.xml").writeText(
            """<application><component name="GeneralSettings"><option name="showTipsOnStartup" value="false"/></component></application>""",
        )
        // IDEA's own ProxySelector supersedes JVM proxy flags. Configure the real
        // HTTP Proxy settings in this isolated profile, preserving cloud routing.
        val proxyAddress = System.getenv("HTTPS_PROXY") ?: System.getenv("HTTP_PROXY")
        if (!proxyAddress.isNullOrBlank()) {
            val proxy = java.net.URI.create(proxyAddress)
            testContext.paths.configDir.resolve("options/proxy.settings.xml").writeText("""
                <application><component name="HttpConfigurable">
                  <option name="USE_HTTP_PROXY" value="true"/>
                  <option name="PROXY_HOST" value="${proxy.host}"/>
                  <option name="PROXY_PORT" value="${if (proxy.port < 0) 80 else proxy.port}"/>
                  <option name="PROXY_EXCEPTIONS" value="localhost,127.0.0.1"/>
                </component></application>
            """.trimIndent())
        }
        testContext.paths.configDir.resolve("options/laf.xml").writeText(
            """<application><component name="LafManager" autodetect="false"><laf themeId="$themeId"/></component></application>""",
        )
        // The focused theme pass uses a real embedded DB. Delayed network probes
        // and schema/export flows belong to the complete functional review.
        val displayBounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
        assertTrue(displayBounds.width >= 1440 && displayBounds.height >= 800,
            "Real IDE UI capture requires a display of at least 1440x800; found ${displayBounds.width}x${displayBounds.height}")
        val normalWidth = minOf(1400, displayBounds.width - 40)
        val normalHeight = minOf(1000, displayBounds.height - 80)
        val narrowWidth = minOf(1000, displayBounds.width - 40)
        val narrowHeight = minOf(800, displayBounds.height - 80)
        val mediumWidth = minOf(1120, displayBounds.width - 40)
        val mediumHeight = minOf(900, displayBounds.height - 80)
        val wideWidth = minOf(1900, displayBounds.width - 20)
        val wideHeight = minOf(1000, displayBounds.height - 80)
        val jdbcFixture = if (review && !styleOnly) HsqlUiFixture() else null
        try {
        testContext.apply {
            PluginConfigurator(this).installPluginFromPath(
                Path.of(requireNotNull(System.getProperty("path.to.build.plugin"))),
            )
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            val laf = service(LiveLafManager::class)
            // Newer IDEs migrate saved classic IDs to Islands on startup. Select the
            // requested installed theme explicitly and verify what actually rendered.
            if (laf.getCurrentUIThemeLookAndFeel().getId() != themeId) {
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
                else Path.of(System.getProperty("lattice.test.drivers"), "hsqldb-2.7.4.jar"),
                retainedHsqlFixture,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            ideFrame {
                waitForNoOpenedDialogs()
            }

            ideFrame {
                invokeAction("com.segfault03.lattice.open")
                resize(normalWidth, normalHeight)
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
            // Closing dialogs can leave IDE chrome unpainted under Xvfb, particularly
            // in legacy/high-contrast themes. Paint the live root before desktop capture.
            repaintIdeBeforeCapture = {
                withContext(OnDispatcher.EDT) {
                    val root = cast(frame.component, LiveFrame::class).getRootPane()
                    root.paintImmediately(0, 0, frame.component.width, frame.component.height)
                }
            }
            openConnectionMenu(pluginRoot, frame, "MySQL…")

            val mysqlDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("New connection")
            }.waitFound()
            mysqlDialog.x {
                byVisibleText("8.4.0 · DISCOVERED")
            }.waitFound()
            assertDriverSummary(mysqlDialog, "8.4.0 · DISCOVERED")
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
            val mysqlTypeCombo = mysqlDialog.x(JComboBoxUiComponent::class.java) {
                and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName("Database type:"))
            }.waitFound()
            mysqlTypeCombo.selectItem("HSQLDB")
            assertDriverSummary(mysqlDialog, "2.7.2 · DISCOVERED")
            captureScreen(screenshotsDirectory.resolve("driver-type-switch-hsql-project-default.png"))
            mysqlTypeCombo.selectItem("MySQL")
            assertDriverSummary(mysqlDialog, "8.4.0 · DISCOVERED")
            mysqlAvailableDriverCombo.selectItem("26.7.0 · BUNDLED")
            mysqlTypeCombo.selectItem("HSQLDB")
            assertDriverSummary(mysqlDialog, "2.7.2 · DISCOVERED")
            mysqlTypeCombo.selectItem("MySQL")
            assertDriverSummary(mysqlDialog, "26.7.0 · BUNDLED")
            captureScreen(screenshotsDirectory.resolve("driver-type-switch-mysql-manual-choice.png"))
            mysqlAvailableDriverCombo.selectItem("8.4.0 · DISCOVERED")
            if (review) {
                mysqlAvailableDriverCombo.selectItem("26.7.0 · BUNDLED")
                assertDriverSummary(mysqlDialog, "26.7.0 · BUNDLED")
                captureScreen(screenshotsDirectory.resolve("mysql-driver-summary-bundled.png"))
                mysqlAvailableDriverCombo.selectItem("8.4.0 · DISCOVERED")
            }

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
            assertDriverSummary(mysqlDialog, "5.1.49 · DOWNLOAD")
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
                assertDriverSummary(mysqlDialog, "5.1.49 · DOWNLOADED")
                captureScreen(screenshotsDirectory.resolve("mysql-driver-downloaded.png"))
                mysqlDriverSourceCombo.selectItem("Available drivers")
                mysqlAvailableDriverCombo.selectItem("5.1.49 · DOWNLOADED")
                assertDriverSummary(mysqlDialog, "5.1.49 · DOWNLOADED")
                captureScreen(screenshotsDirectory.resolve("mysql-driver-summary-retained.png"))
                mysqlDriverSourceCombo.selectItem("Download a version")
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
                check(downloadedButtonEnabledAfterModeSwitch == false) {
                    "A retained driver must remain unavailable for download after a failed connection test"
                }
                check(mysqlDialog.x { byVisibleText("More versions") }.waitFound().component.isEnabled()) {
                    "More versions must unlock after connection testing"
                }
            }
            mysqlDialog.pressButton("Cancel")
            ideFrame { waitForNoOpenedDialogs() }
            waitForIndicators(2.minutes)

            openConnectionMenu(pluginRoot, frame, "HSQLDB…")

            val connectionDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("New connection")
            }.waitFound()
            connectionDialog.x {
                byVisibleText("2.7.2 · DISCOVERED")
            }.waitFound()
            assertDriverSummary(connectionDialog, "2.7.2 · DISCOVERED")
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
                val localJar = connectionDialog.x { byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputBrowseField") }.waitFound()
                localJar.x(JTextFieldUI::class.java) { byType("javax.swing.JTextField") }.waitFound().text =
                    mavenRepository.resolve("org/hsqldb/hsqldb/2.7.2/hsqldb-2.7.2.jar").toString()
                assertDriverSummary(connectionDialog, "2.7.2 · LOCAL JAR")
                captureScreen(screenshotsDirectory.resolve("driver-local-jar-selected.png"))
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
            val inputOffsets = assertInputTextPadding(connectionDialog, driverSourceCombo, driverVersionCombo)
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
            assertDriverSummary(connectionDialog, "2.7.3-jdk8 · DOWNLOADED")
            val downloadButton = connectionDialog.x { byVisibleText("Download") }.waitFound()
            waitUntil("Download disabled for an already downloaded driver") {
                !downloadButton.component.isEnabled()
            }
            connectionDialog.x { byVisibleText("Downloaded and ready") }.waitFound()
            captureScreen(screenshotsDirectory.resolve("connection-dialog-download-driver-full.png"))
            if (System.getProperty("ui.inputs.only").toBoolean()) {
                pressEscape() // Close any popup before the focused input capture.
                captureScreen(screenshotsDirectory.resolve("inputs-aligned.png"))
                Files.writeString(screenshotsDirectory.resolve("runtime-evidence.txt"), buildString {
                    appendLine("IDE target: IntelliJ IDEA Community ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
                    appendLine("UI driver: JetBrains Starter and Driver; production ConnectionDialog / DatabaseInputs")
                    appendLine("Actual IDE theme: ${actualTheme.getName()} (${actualTheme.getId()})")
                    appendLine("Live painted input text offsets: $inputOffsets")
                    appendLine("Production root Swing class: com.segfault03.ideadb.ui.DatabaseMainPanel")
                    appendLine("IDE JBR release: ${Files.readString(ideHome.resolve("jbr/release")).lineSequence().filter { it.startsWith("JAVA_VERSION=") || it.startsWith("IMPLEMENTOR=") }.joinToString(", ")}")
                    appendLine("Scope: input/driver dialogs only; table and query flows run in the full scenario")
                })
                return@useDriverAndCloseIde
            }
            driverSourceCombo.selectItem("Available drivers")
            availableDriverCombo.selectItem("2.7.3-jdk8 · DOWNLOADED")
            assertDriverSummary(connectionDialog, "2.7.3-jdk8 · DOWNLOADED")

            if (jdbcFixture != null) {
                connectionDialog.x { byVisibleText("JDBC URL") }.waitFound().click()
                connectionDialog.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("JDBC URL:"))
                }.waitFound().text = jdbcFixture.jdbcUrl
                connectionDialog.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("User:"))
                }.waitFound().text = "SA"
                captureBusyState(jdbcFixture, connectionDialog, "Testing connection…", "connection-testing.png", screenshotsDirectory) {
                    connectionDialog.x { byVisibleText("Test connection") }.waitFound().click()
                }
            } else connectionDialog.x { byVisibleText("Test connection") }.waitFound().click()
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
            if (jdbcFixture != null) captureBusyState(jdbcFixture, pluginRoot, "Connecting to database…", "explorer-connecting.png", screenshotsDirectory) {
                databaseTree.fixture.expandRow(1)
            } else databaseTree.fixture.expandRow(1)
            val connectionPath = arrayOf("Data Sources", "Lattice UI demo")
            val publicSchemaPath = connectionPath + "PUBLIC"
            waitUntil("connected HSQLDB schema in the explorer") {
                databaseTree.pathExists(*publicSchemaPath)
            }
            captureScreen(screenshotsDirectory.resolve("connected-explorer.png"))
            assertExplorerOverflow(pluginRoot, frame, screenshotsDirectory)
            if (review && !styleOnly) {
                databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                captureScreen(screenshotsDirectory.resolve("connection-context-menu.png"))
                frame.x { byVisibleText("Edit connection…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Edit connection") }.waitFound().also {
                    assertDriverSummary(it, "2.7.3-jdk8 · DOWNLOADED")
                    captureScreen(screenshotsDirectory.resolve("edit-connection.png"))
                    it.pressButton("Cancel")
                }
                // A saved manual choice must not silently change when its retained JAR disappears.
                val missingDriverFiles = listOf(retainedHsqlFixture,
                    mavenRepository.resolve("org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3-jdk8.jar"))
                val backups = mutableListOf<Pair<Path, Path>>()
                try {
                    for (jar in missingDriverFiles) {
                        val backup = jar.resolveSibling(jar.fileName.toString() + ".ui-test-backup")
                        Files.move(jar, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        backups.add(jar to backup)
                    }
                    databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                    frame.x { byVisibleText("Edit connection…") }.waitFound().click()
                    frame.x(DialogUiComponent::class.java) { byTitle("Edit connection") }.waitFound().also {
                        assertDriverSummary(it, "2.7.3-jdk8 · UNAVAILABLE")
                        check(!it.x { byVisibleText("Test connection") }.waitFound().component.isEnabled())
                        val save = it.x { byVisibleText("Save") }.waitFound()
                        if (save.component.isEnabled()) save.click()
                        check(it.component.isShowing()) { "Saving an unavailable explicit driver must keep the dialog open" }
                        captureScreen(screenshotsDirectory.resolve("edit-connection-unavailable-driver.png"))
                        it.pressButton("Cancel")
                    }
                } finally {
                    backups.asReversed().forEach { (jar, backup) ->
                        Files.move(backup, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
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
                databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                frame.x { byVisibleText("Create schema…") }.waitFound().click()
                val schemaDialog = frame.x(DialogUiComponent::class.java) { byTitle("Create schema") }.waitFound()
                schemaDialog.x(JTextFieldUI::class.java) { byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField") }.waitFound().text = "UI_REVIEW_SCHEMA"
                captureBusyState(requireNotNull(jdbcFixture), pluginRoot, "Creating schema…", "explorer-creating-schema.png", screenshotsDirectory) {
                    schemaDialog.pressButton("Create schema")
                }
                frame.x(DialogUiComponent::class.java) { byTitle("Created") }.waitFound().pressButton("OK")
                val createdSchemaPath = connectionPath + "UI_REVIEW_SCHEMA"
                waitUntil("created schema is visible") { databaseTree.pathExists(*createdSchemaPath) }
                databaseTree.fixture.rightClickPath(createdSchemaPath.joinToString(databaseTree.fixture.separator()))
                frame.x { byVisibleText("Drop schema…") }.waitFound().click()
                val dropSchema = frame.x(DialogUiComponent::class.java) { byTitle("Drop schema") }.waitFound()
                captureBusyState(jdbcFixture, pluginRoot, "Dropping schema…", "explorer-dropping-schema.png", screenshotsDirectory) {
                    dropSchema.pressButton("Drop schema")
                }
                frame.x(DialogUiComponent::class.java) { byTitle("Dropped") }.waitFound().pressButton("OK")
                waitUntil("dropped schema removed from explorer") { !databaseTree.pathExists(*createdSchemaPath) && databaseTree.pathExists(*publicSchemaPath) }
            }

            val publicSchemaPathText = publicSchemaPath.joinToString(databaseTree.fixture.separator())
            databaseTree.fixture.expandPath(publicSchemaPathText)
            val tablesPath = publicSchemaPath + "Tables (0)"
            waitUntil("empty Tables folder") { databaseTree.pathExists(*tablesPath) }
            if (jdbcFixture != null) {
                // Path lookup can already expand the schema; Refresh always starts a real metadata read.
                databaseTree.fixture.rightClickPath(publicSchemaPathText)
                captureBusyState(jdbcFixture, pluginRoot, "Loading tables…", "explorer-loading-tables.png", screenshotsDirectory) {
                    frame.x { byVisibleText("Refresh tables") }.waitFound().click()
                }
                waitUntil("refreshed Tables folder") { databaseTree.pathExists(*tablesPath) }
            }
            databaseTree.fixture.rightClickPath(publicSchemaPathText)
            frame.x { byVisibleText("Create table…") }.waitFound().click()

            val createTableDialog = frame.x(DialogUiComponent::class.java) {
                byTitle("Create table in PUBLIC")
            }.waitFound()
            createTableDialog.x(JTextFieldUI::class.java) {
                byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField")
            }.waitFound().text = "LATTICE_PEOPLE"
            Thread.sleep(500)
            assertCreateTableLayout(createTableDialog)
            captureScreen(screenshotsDirectory.resolve("create-table-dialog.png"))
            if (jdbcFixture != null) captureBusyState(jdbcFixture, pluginRoot, "Creating table…", "explorer-creating-table.png", screenshotsDirectory) {
                createTableDialog.pressButton("Create table")
            } else createTableDialog.pressButton("Create table")
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
            databaseTree.fixture.rightClickPath(peoplePath.joinToString(databaseTree.fixture.separator()))
            frame.x { byVisibleText("Open data editor") }.waitFound().click()

            lateinit var tableEditor: UiComponent
            tableEditor = frame.x { byJavaClass("com.segfault03.ideadb.ui.TableDataEditorPanel") }.waitFound()
            val dataGrid = tableEditor.x(JTableUiComponent::class.java) {
                byJavaClass("com.segfault03.ideadb.ui.DatabaseTable")
            }.waitFound()
            waitUntil("empty table data loaded") { tableEditor.hasSubtext("0 rows") }
            check(dataGrid.rowCount() == 0) { "New LATTICE_PEOPLE table should start empty" }
            captureScreen(screenshotsDirectory.resolve("table-editor-empty.png"))
            if (review) assertFilterLayout(tableEditor, 1)

            val commitButton = tableEditor.x { byAccessibleName("Commit pending changes to the database") }.waitFound()
            val revertButton = tableEditor.x { byAccessibleName("Revert pending changes") }.waitFound()
            tableEditor.x { byAccessibleName("Add a new row") }.waitFound().click()
            waitUntil("empty insert row validates required fields") {
                dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 invalid cell") && !commitButton.component.isEnabled()
            }
            assertTrue(revertButton.component.isEnabled(), "An invalid empty row should remain revertible")
            check(tableEditor.hasSubtext("cannot be NULL")) { "The required-column reason must be visible inline" }
            val emptyRowValues = dataGrid.content().values.single().values
            println("Empty new-row cell values: ${emptyRowValues.joinToString()}")
            captureScreen(screenshotsDirectory.resolve("table-add-empty-row.png"))

            replaceCellValue(dataGrid, row = 0, column = 1, value = "Ada Lovelace")
            waitUntil("valid required value enables insert") {
                commitButton.component.isEnabled() && !tableEditor.hasSubtext("invalid cell")
            }
            captureScreen(screenshotsDirectory.resolve("table-add-row-valid.png"))

            if (jdbcFixture != null) captureBusyState(jdbcFixture, tableEditor, "Committing changes…", "table-committing.png", screenshotsDirectory) {
                commitButton.click()
            } else commitButton.click()
            frame.x(DialogUiComponent::class.java) {
                byTitle("Changes Saved")
            }.waitFound().pressButton("OK")
            waitUntil("committed row reload") {
                dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 row ·")
            }
            check(dataGrid.content().values.any { row -> row.values.any { "Ada" in it } }) {
                "Committed row should be visible in the live data grid"
            }
            if (jdbcFixture != null) {
                dataGrid.clickCell(0, 1)
                tableEditor.x { byAccessibleName("Delete selected rows") }.waitFound().click()
                val confirm = frame.x(DialogUiComponent::class.java) { byTitle("Delete rows") }.waitFound()
                captureBusyState(jdbcFixture, tableEditor, "Deleting saved rows…", "table-deleting-rows.png", screenshotsDirectory) {
                    confirm.pressButton("Delete rows")
                }
                waitUntil("saved row deleted and editor unlocked") { dataGrid.rowCount() == 0 && tableEditor.hasSubtext("Deleted 1 saved row") }
                captureScreen(screenshotsDirectory.resolve("table-deleted-empty.png"))
                tableEditor.x { byAccessibleName("Add a new row") }.waitFound().click()
                replaceCellValue(dataGrid, 0, 1, "Ada Lovelace")
                commitButton.click()
                frame.x(DialogUiComponent::class.java) { byTitle("Changes Saved") }.waitFound().pressButton("OK")
                waitUntil("replacement saved row loaded") { dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 row ·") }
            }

            val initialId = tableCellText(dataGrid, row = 0, column = 0).toLong()
            if (jdbcFixture != null) {
                tableEditor.x { byAccessibleName("Add a new row") }.waitFound().click()
                replaceCellValue(dataGrid, 1, 1, "Duplicate key fixture")
                replaceCellValue(dataGrid, 1, 0, initialId.toString())
                waitUntil("duplicate key is type-valid before the database check") { commitButton.component.isEnabled() }
                captureBusyState(jdbcFixture, tableEditor, "Committing changes…", "table-commit-failure-busy.png", screenshotsDirectory) {
                    commitButton.click()
                }
                frame.x(DialogUiComponent::class.java) { byTitle("Commit Error") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("table-commit-failure.png"))
                    it.pressButton("OK")
                }
                check(tableEditor.hasSubtext("Commit failed · Pending edits kept") && commitButton.component.isEnabled() && revertButton.component.isEnabled())
                captureScreen(screenshotsDirectory.resolve("table-commit-failure-recovered.png"))
                revertButton.click()
                waitUntil("failed insert reverted without losing saved row") { dataGrid.rowCount() == 1 && !revertButton.component.isEnabled() }
            }
            openCellEditor(dataGrid, 0, 0)
            captureScreen(screenshotsDirectory.resolve("table-integer-cell-editing.png"))
            assertEditorSurface(dataGrid)
            dataGrid.keyboard { enter() }
            waitUntil("opening and closing an unchanged integer cell stays clean") {
                !commitButton.component.isEnabled() && !tableEditor.hasSubtext("invalid cell")
            }
            assertTrue(!revertButton.component.isEnabled(), "An unchanged cell should not enable Revert")
            captureScreen(screenshotsDirectory.resolve("table-edit-unchanged-value.png"))
            openCellEditor(dataGrid, 0, 1)
            captureScreen(screenshotsDirectory.resolve("table-text-cell-editing.png"))
            assertEditorSurface(dataGrid)
            dataGrid.keyboard { enter() }

            replaceCellValue(dataGrid, row = 0, column = 0, value = "not-an-integer")
            waitUntil("invalid integer is marked and blocks commit") {
                tableEditor.hasSubtext("1 invalid cell") && !commitButton.component.isEnabled()
            }
            captureScreen(screenshotsDirectory.resolve("table-edit-invalid-integer.png"))
            check(tableEditor.hasSubtext("Invalid INTEGER value")) { "The selected cell's precise type error must be visible" }
            if (review) {
                replaceCellValue(dataGrid, 0, 1, "x".repeat(256))
                waitUntil("both invalid cells reported") { tableEditor.hasSubtext("2 invalid cells") }
                dataGrid.clickCell(0, 0)
                waitUntil("integer detail follows selection") { tableEditor.hasSubtext("Invalid INTEGER value") }
                dataGrid.clickCell(0, 1)
                waitUntil("text detail follows selection") { tableEditor.hasSubtext("Text exceeds 255 characters") }
                captureScreen(screenshotsDirectory.resolve("table-selected-cell-error.png"))
                replaceCellValue(dataGrid, 0, 1, "Ada Lovelace")
            }

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
            assertActionContrast(commitButton)
            assertActionContrast(revertButton)
            tableEditor.x { byAccessibleName("Table options") }.waitFound().click()
            captureScreen(screenshotsDirectory.resolve("table-options-theme.png"))
            assertPaintedSurface(frame.x { byVisibleText("Count rows") }.waitFound(), dataGrid, "table menu")
            pressEscape()
            val autoRefresh = tableEditor.x(JComboBoxUiComponent::class.java) {
                and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                    byTooltip("Periodic auto-refresh interval"))
            }.waitFound()
            assertAutoRefreshFits(autoRefresh)
            autoRefresh.click()
            captureScreen(screenshotsDirectory.resolve("table-auto-refresh-options.png"))
            pressEscape()
            if (styleOnly) {
                frame.resize(narrowWidth, narrowHeight)
                openToolbarOverflow(tableEditor, "Table actions").also {
                    captureScreen(screenshotsDirectory.resolve("table-narrow-toolbar-expanded-pending.png"))
                    assertToolbarSingleRow(it, "expanded table")
                    assertIconOnlyOverflow(tableEditor.x { byAccessibleName("Table actions") }.waitFound(), it)
                    assertActionContrast(it.x { byAccessibleName("Commit pending changes to the database") }.waitFound())
                    assertActionContrast(it.x { byAccessibleName("Revert pending changes") }.waitFound())
                    closeToolbarOverflow(it)
                }
                Files.writeString(screenshotsDirectory.resolve("runtime-evidence.txt"), buildString {
                    appendLine("IDE target: IntelliJ IDEA $productCode ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
                    appendLine("Actual IDE theme: ${actualTheme.getName()} (${actualTheme.getId()})")
                    appendLine("Available IDE themes: $availableThemes")
                    appendLine("UI driver: JetBrains Starter and Driver; real production DatabaseMainPanel, TableDataEditorPanel, DatabaseTable and JTable editors")
                    appendLine("IDE JBR release: ${Files.readString(ideHome.resolve("jbr/release")).lineSequence().filter { it.startsWith("JAVA_VERSION=") || it.startsWith("IMPLEMENTOR=") }.joinToString(", ")}")
                    appendLine("IDE display: ${displayBounds.width}x${displayBounds.height}; scale 1")
                    appendLine("Verified: six explorer actions in native hover overflow and working Add Connection invoker; failed connection status; valid/invalid/unchanged table edits; text and integer editor theme surfaces; native menu background; full auto-refresh caption; white active action labels and painted glyphs in normal and overflow toolbars")
                    appendLine("Scope: focused UI regressions; the default review continues through commit, export, alter-table and SQL flows")
                })
                println("Focused real IDE UI regression review passed: $themeId")
                return@useDriverAndCloseIde
            }

            commitButton.click()
            frame.x(DialogUiComponent::class.java) {
                byTitle("Changes Saved")
            }.waitFound().pressButton("OK")
            waitUntil("valid integer change is committed and reloaded") {
                dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 row ·") && tableCellText(dataGrid, 0, 0).toLongOrNull() == changedId
            }
            check(dataGrid.content().values.any { row -> row.values.any { "Ada" in it } }) {
                "Committing the integer edit should preserve the other row values"
            }
            captureScreen(screenshotsDirectory.resolve("table-edit-committed.png"))
            val commitSize = "${commitButton.component.width}x${commitButton.component.height}"
            val revertSize = "${revertButton.component.width}x${revertButton.component.height}"

            if (review) {
                assertNullContrast(dataGrid, 0, 2)
                captureScreen(screenshotsDirectory.resolve("table-null-unselected.png"))
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
                captureBusyState(requireNotNull(jdbcFixture), tableEditor, "Loading table data…", "table-loading-data.png", screenshotsDirectory) {
                    tableEditor.x { byAccessibleName("Refresh table data") }.waitFound().click()
                }
                waitUntil("refresh completed and actions restored") { tableEditor.x { byAccessibleName("Refresh table data") }.waitFound().component.isEnabled() }
                assertButtonReset(tableEditor.x { byAccessibleName("Refresh table data") }.waitFound(), dataGrid)
                val applyButton = tableEditor.x { byAccessibleName("Apply filter and sort") }.waitFound()
                applyButton.click()
                waitUntil("Apply completed") { applyButton.component.isEnabled() }
                assertButtonReset(applyButton, dataGrid)
                captureScreen(screenshotsDirectory.resolve("table-action-focus-after-click.png"))
                tableEditor.x { byAccessibleName("Table options") }.waitFound().click()
                captureBusyState(jdbcFixture, tableEditor, "Counting saved rows…", "table-counting-rows.png", screenshotsDirectory) {
                    frame.x { byVisibleText("Count rows") }.waitFound().click()
                }
                waitUntil("row count completed") { tableEditor.hasSubtext("1 saved row matches the filter") }
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
                pressEscape()
                captureBusyState(jdbcFixture, pluginRoot, "Loading columns…", "explorer-loading-columns.png", screenshotsDirectory) {
                    pluginRoot.x { byAccessibleName("Refresh") }.waitFound().click()
                }
                waitUntil("column metadata loaded") { pluginRoot.hasSubtext("Loaded columns") }
                databaseTree.fixture.rightClickPath(pathText)
                captureScreen(screenshotsDirectory.resolve("table-context-menu.png"))
                frame.x { byVisibleText("Alter table…") }.waitFound().click()
                val alter = frame.x(DialogUiComponent::class.java) { byTitle("Alter table: LATTICE_PEOPLE") }.waitFound()
                var formOrigin: Point? = null
                for ((label, file) in listOf("Add column" to "add", "Rename column" to "rename-column", "Modify column" to "modify", "Drop column" to "drop", "Rename table" to "rename-table")) {
                    // Each card has an operation button with the same label as its tab.
                    alter.xx { byVisibleText(label) }.list().first().click()
                    formOrigin = assertAlterTableLayout(alter, label, formOrigin)
                    captureScreen(screenshotsDirectory.resolve("alter-$file.png"))
                }
                alter.xx { byVisibleText("Drop column") }.list().first().click()
                alter.xx { byVisibleText("Drop column") }.list().last().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Drop column") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("drop-column-confirmation.png"))
                    it.pressButton("Cancel")
                }
                alter.xx { byVisibleText("Add column") }.list().first().click()
                alter.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Column name:"))
                }.waitFound().text = "name"
                captureBusyState(jdbcFixture, alter, "Updating table…", "alter-table-updating.png", screenshotsDirectory) {
                    alter.xx { byVisibleText("Add column") }.list().last().click()
                }
                frame.x(DialogUiComponent::class.java) { byTitle("Database Operation Failed") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("alter-table-operation-error.png"))
                    it.pressButton("OK")
                }
                check(alter.hasSubtext("Table operation failed · See error details"))
                check(alter.xx { byVisibleText("Add column") }.list().last().component.isEnabled())
                captureScreen(screenshotsDirectory.resolve("alter-table-recovered.png"))
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
                frame.x { byVisibleText("All persisted rows (current filter)") }.waitFound().click()
                // Scope duplicate CSV entries through their actual Swing submenu owner.
                val csvAction = frame.xx { byVisibleText("CSV…") }.list().first {
                    withContext(OnDispatcher.EDT) {
                        cast(it.component, LiveMenuItem::class).getParent().getInvoker().getText() == "All persisted rows (current filter)"
                    }
                }
                csvAction.click()
                val saveExport = frame.x(DialogUiComponent::class.java) { byTitle("Export All Persisted Rows") }.waitFound()
                val exportFile = screenshotsDirectory.resolve("persisted-rows.csv").toAbsolutePath()
                val filename = saveExport.xx(JTextFieldUI::class.java) { byType(javax.swing.JTextField::class.java) }
                    .list().single { it.text.endsWith(".csv") }
                filename.text = exportFile.toString()
                captureScreen(screenshotsDirectory.resolve("table-export-file-chooser.png"))
                captureBusyState(jdbcFixture, tableEditor, "Exporting persisted rows…", "table-exporting-rows.png", screenshotsDirectory) {
                    saveExport.pressButton("OK")
                }
                frame.x(DialogUiComponent::class.java) { byTitle("Export Complete") }.waitFound().pressButton("OK")
                check(Files.readString(exportFile).contains("Ada Lovelace")) { "Real persisted-row export must contain the saved database value" }
                captureScreen(screenshotsDirectory.resolve("table-export-complete.png"))
                tableEditor.x { byAccessibleName("Export data") }.waitFound().click()
                captureBusyState(jdbcFixture, tableEditor, "Loading CREATE TABLE statement…", "table-loading-ddl.png", screenshotsDirectory) {
                    frame.x { byVisibleText("View CREATE TABLE…") }.waitFound().click()
                }
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
                frame.resize(narrowWidth, narrowHeight)
                captureScreen(screenshotsDirectory.resolve("table-narrow.png"))
                assertFilterLayout(tableEditor, 3)
                assertEditorControlsVisible(tableEditor, "narrow table")
                replaceCellValue(dataGrid, 0, 0, "not-an-integer")
                waitUntil("narrow cell error is visible inline") { tableEditor.hasSubtext("Invalid INTEGER value") }
                captureScreen(screenshotsDirectory.resolve("table-narrow-invalid-cell.png"))
                assertEditorControlsVisible(tableEditor, "narrow table with invalid cell detail")
                replaceCellValue(dataGrid, 0, 0, changedId.toString())
                assertToolbarSingleRow(tableEditor.x { byAccessibleName("Table actions") }.waitFound(), "collapsed table")
                openToolbarOverflow(tableEditor, "Table actions").also {
                    captureScreen(screenshotsDirectory.resolve("table-narrow-toolbar-expanded.png"))
                    assertToolbarSingleRow(it, "expanded table")
                    assertIconOnlyOverflow(tableEditor.x { byAccessibleName("Table actions") }.waitFound(), it)
                    closeToolbarOverflow(it)
                }
                replaceCellValue(dataGrid, 0, 1, "Narrow layout edit")
                waitUntil("narrow edit can be committed") { commitButton.component.isEnabled() }
                captureScreen(screenshotsDirectory.resolve("table-narrow-pending.png"))
                assertEditorControlsVisible(tableEditor, "narrow table with pending edit")
                val pendingOverflow = openToolbarOverflow(tableEditor, "Table actions").also {
                    captureScreen(screenshotsDirectory.resolve("table-narrow-toolbar-expanded-pending.png"))
                    assertIconOnlyOverflow(tableEditor.x { byAccessibleName("Table actions") }.waitFound(), it)
                    assertTrue(it.x { byAccessibleName("Commit pending changes to the database") }.waitFound().component.isEnabled())
                    assertActionContrast(it.x { byAccessibleName("Commit pending changes to the database") }.waitFound())
                    assertActionContrast(it.x { byAccessibleName("Revert pending changes") }.waitFound())
                    it.x { byAccessibleName("Revert pending changes") }.waitFound().click()
                }
                waitUntil("narrow Revert restores row") { tableCellText(dataGrid, 0, 1) == "Ada Lovelace" }
                closeToolbarOverflow(pendingOverflow)
                whereInput.text = "\"id\" = -999"
                tableEditor.x { byVisibleText("Apply") }.waitFound().click()
                waitUntil("narrow Apply filters rows") { dataGrid.rowCount() == 0 }
                captureScreen(screenshotsDirectory.resolve("table-narrow-filter.png"))
                assertEditorControlsVisible(tableEditor, "narrow filtered table")
                whereInput.text = ""
                tableEditor.x { byVisibleText("Apply") }.waitFound().click()
                waitUntil("narrow filter reset") { dataGrid.rowCount() == 1 }
                val ideWindow = cast(frame.component, Window::class)
                frame.resize(mediumWidth, mediumHeight)
                captureScreen(screenshotsDirectory.resolve("table-medium-filters.png"))
                assertFilterLayout(tableEditor, 2)
                assertEditorControlsVisible(tableEditor, "medium table")
                withContext(OnDispatcher.EDT) { ideWindow.setBounds(10, 40, wideWidth, wideHeight) }
                captureScreen(screenshotsDirectory.resolve("table-wide.png"))
                assertFilterLayout(tableEditor, 1)
                assertEditorControlsVisible(tableEditor, "wide table")
                withContext(OnDispatcher.EDT) { ideWindow.setBounds((displayBounds.width - normalWidth) / 2, 40, normalWidth, normalHeight) }
                captureScreen(screenshotsDirectory.resolve("table-normal-after-resize.png"))
                assertFilterLayout(tableEditor, 1)
                assertEditorControlsVisible(tableEditor, "restored table")
                databaseTree.fixture.rightClickPath(pathText)
                jdbcFixture.pauseResponses()
                frame.x { byVisibleText("Open in SQL console") }.waitFound().click()
                val console = frame.x { byJavaClass("com.segfault03.ideadb.ui.SqlQueryConsolePanel") }.waitFound()
                try {
                    waitUntil("console opens immediately with loading feedback") { console.hasSubtext("Connecting · Loading databases…") }
                    jdbcFixture.awaitBlockedResponse()
                    assertAnimatedBusy(console.x { byAccessibleName("SQL query status") }.waitFound())
                    check(!console.x { byVisibleText("Run") }.waitFound().component.isEnabled())
                    captureScreen(screenshotsDirectory.resolve("sql-console-loading-databases.png"))
                } finally { jdbcFixture.resumeResponses() }
                waitUntil("console database picker ready") { console.x { byVisibleText("Run") }.waitFound().component.isEnabled() }
                val query = console.xx { byJavaClass("com.intellij.ui.components.JBTextArea") }.list().first()
                val queryText = cast(query.component, JTextComponent::class)
                captureScreen(screenshotsDirectory.resolve("sql-console-ready.png"))
                val runQuery = console.x { byVisibleText("Run") }.waitFound()
                val stopQuery = console.x { byVisibleText("Stop") }.waitFound()
                // Bounded real HSQLDB work also exercises cancellation with no prior results.
                val tenValues = "(VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9))"
                val cancellationSql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SYSTEM_COLUMNS A CROSS JOIN INFORMATION_SCHEMA.SYSTEM_COLUMNS B CROSS JOIN $tenValues C(n) CROSS JOIN $tenValues D(n)"
                withContext(OnDispatcher.EDT) { queryText.setText(cancellationSql) }
                runQuery.click()
                waitUntil("first SQL query running") { stopQuery.component.isEnabled() }
                stopQuery.click()
                waitUntil("first query cancellation completes") { runQuery.component.isEnabled() }
                assertQueryOutcome(console, cancelled = true)
                captureScreen(screenshotsDirectory.resolve("sql-console-cancelled-empty.png"))
                val initialTabs = cast(console.x { byJavaClass("javax.swing.JTabbedPane") }.waitFound().component, LiveTabs::class)
                withContext(OnDispatcher.EDT) {
                    initialTabs.setSelectedIndex(0)
                    check(initialTabs.getComponentAt(0).getViewport().getView().getRowCount() == 0) {
                        "Cancelling the first query must not invent results"
                    }
                }
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM PUBLIC.LATTICE_PEOPLE") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL SELECT completed") { console.hasSubtext("1 row ·") }
                val sqlGrid = console.x(JTableUiComponent::class.java) { byJavaClass("com.segfault03.ideadb.ui.DatabaseTable") }.waitFound()
                captureScreen(screenshotsDirectory.resolve("sql-console-results.png"))
                assertNullContrast(sqlGrid, 0, 2)
                captureScreen(screenshotsDirectory.resolve("sql-console-null-unselected.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM PUBLIC.LATTICE_PEOPLE UNION ALL SELECT * FROM PUBLIC.LATTICE_PEOPLE") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("plural SQL row count") { console.hasSubtext("2 rows ·") }
                captureScreen(screenshotsDirectory.resolve("sql-console-multiple-rows.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM PUBLIC.LATTICE_PEOPLE WHERE \"id\" = -999") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("empty SQL result") { console.hasSubtext("0 rows") }
                captureScreen(screenshotsDirectory.resolve("sql-console-empty-results.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("UPDATE PUBLIC.LATTICE_PEOPLE SET \"name\" = 'Ada Lovelace' WHERE \"id\" = -999") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("zero affected rows") { console.hasSubtext("0 rows affected ·") }
                captureScreen(screenshotsDirectory.resolve("sql-console-update-zero.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("UPDATE PUBLIC.LATTICE_PEOPLE SET \"name\" = 'Ada Lovelace' WHERE \"id\" = $changedId") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL update completed") { console.hasSubtext("1 row affected ·") }
                captureScreen(screenshotsDirectory.resolve("sql-console-update.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM PUBLIC.LATTICE_PEOPLE") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("results restored before cancellation/error review") { console.hasSubtext("1 row ·") }
                // Bounded real database work lasts a few seconds. An unbounded four-way
                // system-catalog aggregate exposed slow HSQLDB cancellation; its evidence
                // is retained separately rather than making every theme run hang.
                withContext(OnDispatcher.EDT) {
                    queryText.setText(cancellationSql)
                }
                runQuery.click()
                waitUntil("long SQL running") { stopQuery.component.isEnabled() }
                assertAnimatedBusy(console.x { byAccessibleName("SQL query status") }.waitFound())
                captureScreen(screenshotsDirectory.resolve("sql-console-running.png"), settleMillis = 0)
                val cancellationStart = System.nanoTime()
                stopQuery.click()
                waitUntil("immediate cancellation feedback", timeoutMillis = 2000) { console.hasSubtext("Cancelling query…") }
                println("P2 cancellation feedback after ${(System.nanoTime() - cancellationStart) / 1_000_000} ms")
                check(!runQuery.component.isEnabled() && !stopQuery.component.isEnabled()) { "A cancelling query must block Run and repeated Stop" }
                assertAnimatedBusy(console.x { byAccessibleName("SQL query status") }.waitFound())
                captureScreen(screenshotsDirectory.resolve("sql-console-cancelling.png"), settleMillis = 0)
                waitUntil("SQL cancellation completes") { runQuery.component.isEnabled() }
                assertQueryOutcome(console, cancelled = true)
                captureScreen(screenshotsDirectory.resolve("sql-console-cancelled.png"))
                val previousTabs = cast(console.x { byJavaClass("javax.swing.JTabbedPane") }.waitFound().component, LiveTabs::class)
                withContext(OnDispatcher.EDT) { previousTabs.setSelectedIndex(0) }
                check(sqlGrid.rowCount() == 1) { "Cancellation must retain previous results" }
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM PUBLIC.LATTICE_PEOPLE") }
                runQuery.click()
                waitUntil("session usable after cancellation") { runQuery.component.isEnabled() && console.hasSubtext("1 row ·") }
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM TABLE_THAT_DOES_NOT_EXIST") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL error displayed") { console.hasSubtext("Query failed") }
                assertQueryOutcome(console, cancelled = false)
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
                frame.resize(narrowWidth, narrowHeight)
                captureScreen(screenshotsDirectory.resolve("sql-console-narrow.png"))
                assertEditorControlsVisible(console, "narrow SQL console")
                assertToolbarSingleRow(console.x { byAccessibleName("SQL actions") }.waitFound(), "collapsed SQL")
                openToolbarOverflow(console, "SQL actions").also {
                    captureScreen(screenshotsDirectory.resolve("sql-console-narrow-toolbar-expanded.png"))
                    assertToolbarSingleRow(it, "expanded SQL")
                    assertIconOnlyOverflow(console.x { byAccessibleName("SQL actions") }.waitFound(), it)
                    closeToolbarOverflow(it)
                }
                for ((label, file) in listOf("History" to "sql-history-narrow-popup", "Template" to "sql-template-narrow-popup")) {
                    console.x(JComboBoxUiComponent::class.java) {
                        and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName(label))
                    }.waitFound().also {
                        clickComboArrow(it)
                        captureScreen(screenshotsDirectory.resolve("$file.png"))
                        pressEscape()
                    }
                }
                withContext(OnDispatcher.EDT) { ideWindow.setBounds(10, 40, wideWidth, wideHeight) }
                captureScreen(screenshotsDirectory.resolve("sql-console-wide.png"))
                assertEditorControlsVisible(console, "wide SQL console")
                withContext(OnDispatcher.EDT) { ideWindow.setBounds((displayBounds.width - normalWidth) / 2, 40, normalWidth, normalHeight) }
                captureScreen(screenshotsDirectory.resolve("sql-console-normal-after-resize.png"))
                assertEditorControlsVisible(console, "restored SQL console")
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
                appendLine("IDE target: IntelliJ IDEA $productCode ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
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
        } finally { jdbcFixture?.close() }
    }

    private fun captureBusyState(fixture: HsqlUiFixture, root: UiComponent, message: String, filename: String,
                                 directory: Path, action: () -> Unit) {
        fixture.pauseResponses()
        try {
            action()
            waitUntil("visible busy feedback: $message") { root.hasSubtext(message) }
            fixture.awaitBlockedResponse()
            // IDEA's optional plugin recommendation can cover the status bar.
            root.driver.ui.xx { byVisibleText("Don't suggest again") }.list()
                .filter { it.component.isShowing() }.forEach { it.click() }
            // A tree renderer also exposes the loading text. Inspect the persistent
            // status component, whose screen location and repaint lifecycle are real.
            val label = root.xx { byAccessibleName("Database explorer status") }.list().firstOrNull()
                ?: root.x { byVisibleText(message) }.waitFound()
            assertAnimatedBusy(label)
            // Confirm that the actual icon animates on screen, rather than merely having a spinner class.
            val point = label.component.getLocationOnScreen()
            val region = Rectangle(point.x, point.y, minOf(40, label.component.width), label.component.height)
            val robot = Robot()
            val first = robot.createScreenCapture(region)
            var changed = false
            repeat(5) {
                Thread.sleep(100)
                val next = robot.createScreenCapture(region)
                if ((0 until first.width).any { x -> (0 until first.height).any { y -> first.getRGB(x, y) != next.getRGB(x, y) } }) changed = true
            }
            check(changed) { "Busy spinner did not animate on the live display: $message" }
            println("Animated spinner pixels changed on the live IDE display: $message")
            if (message in listOf("Committing changes…", "Loading table data…", "Counting saved rows…", "Deleting saved rows…", "Exporting persisted rows…", "Loading CREATE TABLE statement…")) {
                for (name in listOf("Refresh table data", "Add a new row", "Delete selected rows", "Commit pending changes to the database", "Revert pending changes", "Apply filter and sort")) {
                    check(!root.x { byAccessibleName(name) }.waitFound().component.isEnabled()) { "$name must be disabled during $message" }
                }
                val grid = root.x { byJavaClass("com.segfault03.ideadb.ui.DatabaseTable") }.waitFound()
                check(!grid.component.isEnabled())
            }
            captureScreen(directory.resolve(filename))
        } finally { fixture.resumeResponses() }
    }

    private fun assertButtonReset(button: UiComponent, grid: UiComponent) {
        val location = grid.component.getLocationOnScreen()
        val robot = Robot()
        robot.mouseMove(location.x + grid.component.width / 2, location.y + grid.component.height / 2)
        Thread.sleep(200)
        button.driver.withContext(OnDispatcher.EDT) {
            val live = cast(button.component, LiveToolbarButton::class)
            val model = live.getModel()
            check(!model.isPressed() && !model.isArmed() && !model.isRollover()) { "Button retains pressed/armed/hover state after the pointer leaves: ${live.getToolTipText()}" }
            check(cast(grid.component, LiveSwingComponent::class).requestFocusInWindow())
        }
        Thread.sleep(150)
        val point = button.component.getLocationOnScreen()
        val bounds = Rectangle(point.x, point.y, button.component.width, button.component.height)
        val unfocused = robot.createScreenCapture(bounds)
        button.driver.withContext(OnDispatcher.EDT) {
            check(cast(button.component, LiveToolbarButton::class).requestFocusInWindow())
        }
        Thread.sleep(150)
        val focused = robot.createScreenCapture(bounds)
        check(button.driver.cast(button.component, LiveToolbarButton::class).isFocusOwner()) { "Keyboard focus must still work" }
        check(unfocused.getRGB(4, unfocused.height / 2) == focused.getRGB(4, focused.height / 2)) { "Keyboard focus must not paint a sticky hover fill" }
        println("Action reset: ${button.driver.cast(button.component, LiveToolbarButton::class).getToolTipText()} · model reset; keyboard focus has no hover fill")
    }

    private fun assertAnimatedBusy(label: UiComponent) {
        label.driver.withContext(OnDispatcher.EDT) {
            val icon = cast(label.component, LiveLabel::class).getIcon()
            check(icon != null) { "Busy feedback must have an icon" }
            val className = cast(icon, LiveObject::class).getClass().getName()
            check(className.contains("AnimatedIcon")) { "Busy feedback must use the platform animated icon: $className" }
            println("Loading feedback: ${cast(label.component, LiveLabel::class).getText()} · $className")
        }
    }

    private fun savePng(path: Path, image: BufferedImage) {
        check(ImageIO.write(image, "png", path.toFile())) { "No PNG ImageIO writer available" }
    }

    private fun assertCreateTableLayout(dialog: UiComponent) {
        for (caption in listOf("Remove column", "Move up", "Move down")) dialog.x { byVisibleText(caption) }.waitFound()
        val grid = dialog.x(JTableUiComponent::class.java) { byJavaClass("com.intellij.ui.table.JBTable") }.waitFound()
        val preview = dialog.x { byAccessibleName("Create table SQL preview") }.waitFound()
        dialog.driver.withContext(OnDispatcher.EDT) {
            val table = cast(grid.component, LiveTableEditor::class)
            val columns = table.getColumnModel()
            val header = table.getTableHeader().getDefaultRenderer()
            for (index in 0 until columns.getColumnCount()) {
                val column = columns.getColumn(index)
                val painted = header.getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, index)
                check(column.getWidth() >= painted.getPreferredSize().getWidth()) { "Create Table truncates ${column.getHeaderValue()}" }
            }
            val text = cast(preview.component, LiveTextArea::class)
            check(text.getText().startsWith("CREATE TABLE") && text.getCaretPosition() == 0)
            check(cast(preview.component, LiveSwingComponent::class).getVisibleRect().y == 0) { "SQL preview must show the first line" }
            check(text.getLineCount() <= text.getRows()) { "Initial SQL preview must fit the small default statement" }
        }
        println("P2 Create Table: all headers fit; updated SQL preview starts at CREATE TABLE")
    }

    private fun assertDriverSummary(dialog: UiComponent, expected: String) {
        waitUntil("compact selected-driver summary") { dialog.hasSubtext(expected) }
        val summary = dialog.x { byAccessibleName("Selected JDBC driver") }.waitFound()
        dialog.driver.withContext(OnDispatcher.EDT) {
            check(cast(summary.component, LiveLabel::class).getText() == expected)
            val live = cast(summary.component, LiveSwingComponent::class)
            check(summary.component.width >= live.getPreferredSize().getWidth()) { "Selected driver summary is clipped" }
            val ratio = contrastRatio(live.getForeground().getRGB(), live.getBackground().getRGB())
            check(ratio >= 4.5) { "Selected driver summary contrast is too low: $ratio" }
            println("P3 driver summary: $expected; live palette contrast $ratio:1")
        }
    }

    private fun assertFilterLayout(editor: UiComponent, rows: Int) {
        val where = editor.x(JTextFieldUI::class.java) {
            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("WHERE"))
        }.waitFound().component
        val order = editor.x(JTextFieldUI::class.java) {
            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("ORDER BY"))
        }.waitFound().component
        val apply = editor.x { byAccessibleName("Apply filter and sort") }.waitFound().component
        fun center(component: com.intellij.driver.sdk.ui.remote.Component) = component.getLocationOnScreen().y + component.height / 2
        val first = center(where)
        val second = center(order)
        val action = center(apply)
        when (rows) {
            1 -> check(first == second && second == action) { "Filters and Apply must share one row: $first, $second, $action" }
            2 -> check(first < second && second == action) { "Apply must share the second filter row: $first, $second, $action" }
            3 -> check(first < second && second < action) { "Narrow filters must stack without clipping" }
        }
        check(where.width >= 80 && order.width >= 80) { "Filter fields must keep a usable width" }
        println("P3 table filters: $rows rows; input widths ${where.width}, ${order.width}; Apply aligned")
    }

    private fun assertQueryOutcome(console: UiComponent, cancelled: Boolean) {
        val status = console.x { byAccessibleName("SQL query status") }.waitFound()
        val messages = console.x { byAccessibleName("SQL query messages") }.waitFound()
        val heading = console.x { byVisibleText("SQL query") }.waitFound()
        console.driver.withContext(OnDispatcher.EDT) {
            val statusLabel = cast(status.component, LiveLabel::class)
            val text = cast(messages.component, LiveTextArea::class).getText()
            val normalColor = cast(heading.component, LiveSwingComponent::class).getForeground().getRGB()
            val statusColor = cast(status.component, LiveSwingComponent::class).getForeground().getRGB()
            if (cancelled) {
                check(text.startsWith("Query cancelled") && !text.contains("Query failed"))
                check(statusLabel.getIcon() == null && statusColor == normalColor) { "User cancellation must use normal text without the error glyph" }
            } else {
                check(text.startsWith("Query failed"))
                check(statusLabel.getIcon() != null && statusColor != normalColor) { "Real query failures must retain error feedback" }
            }
        }
        println("P3 SQL outcome: ${if (cancelled) "neutral cancellation" else "real failure retains error tone"}")
    }

    private fun assertAlterTableLayout(dialog: UiComponent, tab: String, origin: Point?): Point {
        val label = when (tab) {
            "Add column" -> "Column name:"
            "Drop column" -> "Column to drop:"
            "Rename table" -> "New table name:"
            else -> "Column:"
        }
        val field = dialog.x {
            and(byAccessibleName(label), or(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"),
                byType("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox")))
        }.waitFound()
        val location = field.component.getLocationOnScreen()
        check(origin == null || origin == location) { "Alter Table card moves its first field: $origin -> $location" }
        val operation = if (tab == "Modify column") "Apply column changes" else tab
        val button = dialog.xx { byVisibleText(operation) }.list().last()
        check(button.component.getLocationOnScreen().x == location.x) { "Alter action must align with the field" }
        dialog.driver.withContext(OnDispatcher.EDT) {
            val live = cast(button.component, LiveSwingComponent::class)
            check(button.component.getBounds().width == live.getPreferredSize().getWidth().toInt()) { "Alter action must stay content-sized" }
        }
        println("P2 Alter Table: $tab first field at $location; content-sized action aligned")
        return location
    }

    private fun assertNullContrast(grid: UiComponent, row: Int, column: Int) {
        var ratio = 0.0
        grid.driver.withContext(OnDispatcher.EDT) {
            val table = cast(grid.component, LiveTableEditor::class)
            table.clearSelection()
            val rendered = table.prepareRenderer(table.getCellRenderer(row, column), row, column)
            check(rendered.getFont().isItalic()) { "SQL NULL must retain an italic cue" }
            ratio = contrastRatio(rendered.getForeground().getRGB(), rendered.getBackground().getRGB())
            check(ratio >= 4.5) { "Unselected SQL NULL contrast is too low: $ratio" }
        }
        println("P2 unselected SQL NULL live palette contrast: $ratio:1")
    }

    private fun assertActionContrast(button: UiComponent) {
        button.driver.withContext(OnDispatcher.EDT) {
            val live = cast(button.component, LiveSwingComponent::class)
            val ratio = contrastRatio(live.getForeground().getRGB(), live.getBackground().getRGB())
            check(live.getForeground().getRGB() == java.awt.Color.WHITE.rgb) { "Active action text must be white" }
            check(ratio >= 4.5) { "Active action label contrast is too low: $ratio" }
            println("Enabled action live palette contrast: $ratio:1")
        }
        val point = button.component.getLocationOnScreen()
        val icon = requireNotNull(button.driver.cast(button.component, LiveToolbarButton::class).getIcon())
        val iconWidth = icon.getIconWidth()
        val left = if (button.driver.cast(button.component, LiveToolbarButton::class).getText().isEmpty())
            (button.component.width - iconWidth) / 2 else 7
        val image = Robot().createScreenCapture(Rectangle(point.x + left,
            point.y + (button.component.height - icon.getIconHeight()) / 2, iconWidth, icon.getIconHeight()))
        val whitePixels = (0 until image.width).sumOf { x -> (0 until image.height).count { y ->
            val rgb = image.getRGB(x, y)
            // Thin native SVG strokes have few fully opaque pixels at scale 1.
            // Their bright cores must exceed native gray glyphs (also in dark themes).
            listOf((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255).all { it >= 220 }
        } }
        check(whitePixels >= 5) { "Active icon is gray rather than white: $whitePixels white glyph pixels" }
        println("Active glyph painted white in the live IDE: $whitePixels pixels")
    }

    private fun assertEditorSurface(grid: UiComponent) {
        assertPaintedSurface(grid.x { byType("javax.swing.JTextField") }.waitFound(), grid, "cell editor")
    }

    private fun openCellEditor(grid: UiComponent, row: Int, column: Int) {
        waitUntil("data grid ready for editing") { grid.component.isEnabled() }
        grid.driver.withContext(OnDispatcher.EDT) {
            val table = cast(grid.component, LiveTableEditor::class)
            check(table.editCellAt(row, column)) { "The production editor did not open" }
            cast(table.getEditorComponent(), LiveSwingComponent::class).requestFocusInWindow()
        }
    }

    private fun assertPaintedSurface(component: UiComponent, grid: UiComponent, description: String) {
        val background = component.driver.cast(component.component, LiveSwingComponent::class).getBackground().getRGB()
        val gridBackground = grid.driver.cast(grid.component, LiveSwingComponent::class).getBackground().getRGB()
        fun light(rgb: Int) = ((rgb shr 16) and 255) + ((rgb shr 8) and 255) + (rgb and 255) > 384
        val point = component.component.getLocationOnScreen()
        val pixels = Robot().createScreenCapture(Rectangle(point.x, point.y,
            component.component.width, component.component.height))
        // The dominant interior color is the surface, even for right-aligned
        // numbers and a blinking caret. A single point can land on a text stroke.
        val colors = mutableMapOf<Int, Int>()
        for (x in 4 until pixels.width - 4) for (y in 4 until pixels.height - 4) {
            val rgb = pixels.getRGB(x, y)
            colors[rgb] = (colors[rgb] ?: 0) + 1
        }
        val painted = colors.maxBy { it.value }.key
        check(light(background) == light(gridBackground) && light(painted) == light(gridBackground)) {
            "$description has the wrong theme background: component=${background.toUInt().toString(16)}, painted=${painted.toUInt().toString(16)}, grid=${gridBackground.toUInt().toString(16)}"
        }
        println("Live $description surface: component=${background.toUInt().toString(16)}, painted=${painted.toUInt().toString(16)}")
    }

    private fun assertAutoRefreshFits(combo: UiComponent) {
        val live = combo.driver.cast(combo.component, LiveSwingComponent::class)
        val textWidth = live.getFontMetrics(live.getFont()).stringWidth("Auto: Off")
        val arrow = combo.x { byType("javax.swing.JButton") }.waitFound()
        check(combo.component.width - arrow.component.width - 16 >= textWidth) {
            "Auto-refresh caption is squeezed: combo=${combo.component.width}, arrow=${arrow.component.width}, text=$textWidth"
        }
        println("Auto-refresh caption fits: combo=${combo.component.width}, arrow=${arrow.component.width}, text=$textWidth")
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(rgb: Int): Double {
            val channels = listOf((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255).map {
                val value = it / 255.0
                if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun assertEditorControlsVisible(editor: UiComponent, description: String) {
        val controls = editor.xx {
            or(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"),
                byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),
                byJavaClass("com.segfault03.ideadb.ui.DatabaseUi\$ActionButton"),
                byJavaClass("com.segfault03.ideadb.ui.WrappingLabel"),
                byJavaClass("com.intellij.ui.components.JBLabel"))
        }.list()
        check(controls.size >= 10) { "Expected production controls in $description" }
        editor.driver.withContext(OnDispatcher.EDT) {
            for (control in controls) {
                val live = cast(control.component, LiveSwingComponent::class)
                if (!live.isVisible()) continue // Optional validation details are absent when the grid is valid.
                val bounds = control.component.getBounds()
                if (live.getClientProperty("lattice.toolbar.control") == true && live.getVisibleRect().isEmpty) continue
                check(live.getVisibleRect() == Rectangle(0, 0, bounds.width, bounds.height)) {
                    "$description clips a control: bounds=$bounds, visible=${live.getVisibleRect()}"
                }
                check(live.getPreferredSize().getHeight() <= bounds.height) {
                    "$description clips control content vertically: preferred height=${live.getPreferredSize().getHeight()}, bounds=$bounds"
                }
            }
        }
        println("P1 visibility checked: $description, ${controls.size} real Swing controls")
    }

    private fun openToolbarOverflow(editor: UiComponent, title: String): UiComponent {
        val toolbar = editor.x { byAccessibleName(title) }.waitFound()
        val bounds = toolbar.component.getBounds()
        toolbar.moveMouse(Point(bounds.width - 16, bounds.height / 2))
        return editor.driver.ui.x { byType("com.intellij.openapi.actionSystem.impl.ActionToolbarImpl\$PopupToolbar") }.waitFound()
    }

    private fun assertExplorerOverflow(root: UiComponent, frame: IdeaFrameUI, directory: Path) {
        val origin = root.component.getLocationOnScreen()
        val originalWidth = root.component.width
        val robot = Robot()
        val dividerX = origin.x - 2
        val dividerY = origin.y + root.component.height / 2
        fun drag(from: Int, to: Int) {
            robot.mouseMove(from, dividerY)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            repeat(10) { step -> robot.mouseMove(from + (to - from) * (step + 1) / 10, dividerY); Thread.sleep(20) }
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(300)
        }
        drag(dividerX, dividerX + originalWidth - 170)
        check(root.component.width < 210) { "The explorer resize did not constrain its toolbar: ${root.component.width}" }
        captureScreen(directory.resolve("explorer-narrow.png"))
        val toolbar = openToolbarOverflow(root, "Explorer actions")
        captureScreen(directory.resolve("explorer-narrow-toolbar-expanded.png"))
        for (name in listOf("Add connection…", "Edit connection…", "Remove connection settings", "Refresh", "Open SQL Console", "Welcome to Lattice")) {
            val button = toolbar.x { byAccessibleName(name) }.waitFound()
            val live = toolbar.driver.cast(button.component, LiveSwingComponent::class)
            check(live.getVisibleRect() == Rectangle(0, 0, button.component.width, button.component.height)) {
                "The explorer hover toolbar clips $name"
            }
        }
        // Exercise an actual mirrored action and its popup invoker, then cancel.
        toolbar.x { byAccessibleName("Add connection…") }.waitFound().click()
        frame.x { byVisibleText("MySQL…") }.waitFound().click()
        frame.x(DialogUiComponent::class.java) { byTitle("New connection") }.waitFound().pressButton("Cancel")
        closeToolbarOverflow(toolbar)
        drag(root.component.getLocationOnScreen().x - 2, dividerX)
        waitUntil("explorer width restored") { kotlin.math.abs(root.component.width - originalWidth) <= 3 }
        println("Native explorer overflow: all six actions visible at 170px; mirrored Add connection opens the production dialog")
    }

    private fun closeToolbarOverflow(toolbar: UiComponent) {
        // The native popup consumes an outside click to dismiss itself. Dismiss explicitly
        // before testing another action rather than relying on its mouse-exit timer.
        val popups = toolbar.driver.ui.xx {
            byType("com.intellij.openapi.actionSystem.impl.ActionToolbarImpl\$PopupToolbar")
        }.list().map { toolbar.driver.cast(it.component, LiveSwingComponent::class) }
        Robot().mouseMove(10, 10)
        pressEscape()
        waitUntil("native hover toolbar dismissed") {
            popups.all { !it.isShowing() }
        }
    }

    private fun assertIconOnlyOverflow(originalToolbar: UiComponent, toolbar: UiComponent) {
        var checked = 0
        for (original in originalToolbar.xx { byJavaClass("com.segfault03.ideadb.ui.DatabaseUi\$ActionButton") }.list()) {
            val source = toolbar.driver.cast(original.component, LiveToolbarButton::class)
            if (source.getText().isEmpty() || source.getIcon() == null) continue
            val tooltip = source.getToolTipText()
            val button = toolbar.x { byTooltip(tooltip) }.waitFound()
            val live = toolbar.driver.cast(button.component, LiveToolbarButton::class)
            check(live.getText().isEmpty() && live.getToolTipText() == tooltip) { "Overflow action should show only its icon and retain its tooltip" }
            val bounds = button.component.getBounds()
            check(bounds.width == 28 && bounds.height == 28) { "Expected a compact 28x28 overflow action" }
            check(live.getIcon() != null) { "Overflow action must keep its icon" }
            checked++
        }
        check(checked >= 3) { "Expected all three labelled toolbar buttons to use their icon-only overflow view" }
        println("P1 native icon-only overflow checked: $checked labelled actions retain their icons/tooltips")
    }

    private fun assertToolbarSingleRow(toolbar: UiComponent, description: String) {
        val controls = toolbar.xx {
            or(byJavaClass("com.segfault03.ideadb.ui.DatabaseUi\$ActionButton"),
                byType("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"))
        }.list()
        val centers = mutableListOf<Int>()
        toolbar.driver.withContext(OnDispatcher.EDT) {
            for (control in controls) {
                val live = cast(control.component, LiveSwingComponent::class)
                val visible = live.getVisibleRect()
                if (visible.isEmpty) continue // Native AUTO_LAYOUT deliberately moves overflow controls offscreen.
                val bounds = control.component.getBounds()
                check(visible == Rectangle(0, 0, bounds.width, bounds.height)) { "$description partially clips an action" }
                centers += control.component.getLocationOnScreen().y + bounds.height / 2
            }
        }
        check(centers.size >= 2 && centers.max() - centers.min() <= 3) { "$description wraps actions: $centers" }
        println("P1 native single-row toolbar checked: $description, ${centers.size} visible controls")
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

    private fun assertInputTextPadding(dialog: UiComponent, source: UiComponent, version: UiComponent): List<Pair<String, Int>> {
        val text = dialog.x {
            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("User:"))
        }.waitFound()
        repaintIdeBeforeCapture?.invoke()
        Thread.sleep(350)
        val image = Robot().createScreenCapture(Rectangle(Toolkit.getDefaultToolkit().screenSize))
        val offsets = listOf("text field" to text, "selector" to source, "editable selector" to version).map { (name, input) ->
            val origin = input.component.getLocationOnScreen()
            val bounds = input.component.getBounds()
            val foreground = input.driver.cast(input.component, LiveSwingComponent::class).getForeground().getRGB()
            // Inspect actual painted glyphs, not just border insets: native combo delegates add their own padding.
            val firstInk = (4 until bounds.width / 2).firstOrNull { x ->
                (5 until bounds.height - 5).any { y ->
                    val pixel = image.getRGB(origin.x + x, origin.y + y)
                    listOf(16, 8, 0).sumOf { shift ->
                        val delta = ((pixel shr shift) and 255) - ((foreground shr shift) and 255)
                        delta * delta
                    } < 2500
                }
            } ?: error("No painted input text found for $name")
            name to firstInk
        }
        check(offsets.maxOf { it.second } - offsets.minOf { it.second } <= 2) {
            "Input text starts at inconsistent left offsets: $offsets"
        }
        println("Live input text left offsets (glyph side bearings may differ): $offsets")
        return offsets
    }

    private fun captureScreen(path: Path, settleMillis: Long = 350) {
        if (settleMillis > 0) repaintIdeBeforeCapture?.invoke()
        Thread.sleep(settleMillis) // Allow live Swing layout and popup painting to settle.
        val bounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
        savePng(path, Robot().createScreenCapture(bounds))
    }

    private fun replaceCellValue(table: JTableUiComponent, row: Int, column: Int, value: String) {
        waitUntil("data grid ready for editing") { table.component.isEnabled() }
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
    fun getRootPane(): LiveSwingComponent
}

@Remote("javax.swing.JComponent")
interface LiveSwingComponent {
    fun requestFocusInWindow(): Boolean
    fun isShowing(): Boolean
    fun paintImmediately(x: Int, y: Int, width: Int, height: Int)
    fun isVisible(): Boolean
    fun getVisibleRect(): Rectangle
    fun getPreferredSize(): LiveDimension
    fun getClientProperty(key: String): Boolean?
    fun getForeground(): LiveColor
    fun getBackground(): LiveColor
    fun getFont(): LiveFont
    fun getFontMetrics(font: LiveFont): LiveFontMetrics
}

@Remote("java.awt.FontMetrics")
interface LiveFontMetrics { fun stringWidth(text: String): Int }

@Remote("java.awt.Dimension")
interface LiveDimension {
    fun getWidth(): Double
    fun getHeight(): Double
}

@Remote("java.awt.Color")
interface LiveColor { fun getRGB(): Int }

@Remote("javax.swing.JLabel")
interface LiveLabel {
    fun getText(): String
    fun getIcon(): LiveIcon?
}

@Remote("java.awt.Font")
interface LiveFont { fun isItalic(): Boolean }

@Remote("javax.swing.JTextArea")
interface LiveTextArea {
    fun getText(): String
    fun getCaretPosition(): Int
    fun getLineCount(): Int
    fun getRows(): Int
}

@Remote("javax.swing.table.TableColumnModel")
interface LiveColumnModel {
    fun getColumnCount(): Int
    fun getColumn(index: Int): LiveColumn
}

@Remote("javax.swing.table.TableColumn")
interface LiveColumn {
    fun getHeaderValue(): String
    fun getWidth(): Int
}

@Remote("javax.swing.table.JTableHeader")
interface LiveTableHeader { fun getDefaultRenderer(): LiveCellRenderer }

@Remote("javax.swing.table.TableCellRenderer")
interface LiveCellRenderer {
    fun getTableCellRendererComponent(table: LiveTableEditor, value: String, selected: Boolean, focus: Boolean, row: Int, column: Int): LiveSwingComponent
}

@Remote("javax.swing.JButton")
interface LiveToolbarButton {
    fun isFocusOwner(): Boolean
    fun getModel(): LiveButtonModel
    fun requestFocusInWindow(): Boolean
    fun getText(): String
    fun getToolTipText(): String
    fun getIcon(): LiveIcon?
}

@Remote("javax.swing.JMenuItem")
interface LiveMenuItem {
    fun getText(): String
    fun getParent(): LivePopupMenu
}

@Remote("javax.swing.JPopupMenu")
interface LivePopupMenu { fun getInvoker(): LiveMenuItem }

@Remote("javax.swing.ButtonModel")
interface LiveButtonModel {
    fun isPressed(): Boolean
    fun isArmed(): Boolean
    fun isRollover(): Boolean
}

@Remote("javax.swing.Icon")
interface LiveIcon {
    fun getIconWidth(): Int
    fun getIconHeight(): Int
}

@Remote("java.lang.Object")
interface LiveObject { fun getClass(): LiveClass }

@Remote("java.lang.Class")
interface LiveClass { fun getName(): String }

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
    fun getComponentAt(index: Int): LiveScrollPane
}

@Remote("javax.swing.JScrollPane")
interface LiveScrollPane { fun getViewport(): LiveViewport }

@Remote("javax.swing.JViewport")
interface LiveViewport { fun getView(): LiveTableEditor }

@Remote("javax.swing.JTable")
interface LiveTableEditor {
    fun getRowCount(): Int
    fun getColumnModel(): LiveColumnModel
    fun getTableHeader(): LiveTableHeader
    fun getCellRenderer(row: Int, column: Int): LiveCellRenderer
    fun prepareRenderer(renderer: LiveCellRenderer, row: Int, column: Int): LiveSwingComponent
    fun clearSelection()
    fun editCellAt(row: Int, column: Int): Boolean
    fun getEditorComponent(): com.intellij.driver.sdk.ui.remote.Component
    fun getCellEditor(): LiveCellEditor
}

@Remote("javax.swing.table.TableCellEditor")
interface LiveCellEditor {
    fun stopCellEditing(): Boolean
}
