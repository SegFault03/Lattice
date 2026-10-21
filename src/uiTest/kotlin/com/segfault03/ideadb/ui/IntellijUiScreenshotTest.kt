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
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
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
        val sourceFilterOnly = System.getProperty("ui.sourceFilter.only").toBoolean()
        val explorerSearchOnly = System.getProperty("ui.explorerSearch.only").toBoolean()
        val alterTableOnly = System.getProperty("ui.alterTable.only").toBoolean()
        val createTableOnly = System.getProperty("ui.createTable.only").toBoolean()
        val cellDetailsOnly = System.getProperty("ui.cellDetails.only").toBoolean()
        val postgresPort = Integer.getInteger("ui.postgres.port", 0)
        val postgres = postgresPort > 0
        val h2 = System.getProperty("ui.h2.enabled", "false").toBoolean()
        check(!h2 || !postgres) { "Choose only one database fixture" }
        val postgresPassword = System.getProperty("ui.postgres.password", "lattice-test")
        val peopleSql = if (postgres) "\"public\".\"LATTICE_PEOPLE\"" else if (h2) "\"PUBLIC\".\"LATTICE_PEOPLE\"" else "PUBLIC.LATTICE_PEOPLE"
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
        projectDirectory.resolve(".idea/.name").writeText("lattice-ui-test")
        // JDBC dependencies are supplied locally. Resolving Maven build plugins
        // over the network can otherwise keep startup indicators busy for minutes.
        projectDirectory.resolve(".idea/workspace.xml").writeText("""
            <project version="4"><component name="MavenImportPreferences">
              <option name="generalSettings"><MavenGeneralSettings>
                <option name="workOffline" value="true"/>
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
        // Windows' native idea64 launcher does not consume JDK_JAVA_OPTIONS.
        // Keep the IDE and the Driver/Robot test JVM in the same coordinate space.
        testContext.applyVMOptionsPatch {
            addSystemProperty("sun.java2d.uiScale", "1.0")
            addSystemProperty("ide.ui.scale", System.getProperty("ui.scale", "1.0"))
        }
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
        val downloadFixture = if (!sourceFilterOnly && !explorerSearchOnly && !alterTableOnly && !createTableOnly && !postgres && !System.getProperty("ui.skip.remote.downloads", "false").toBoolean())
            DriverDownloadUiFixture(proxyAddress) else null
        try {
        val configuredProxy = downloadFixture?.let { "http://127.0.0.1:${it.port}" } ?: proxyAddress
        if (!configuredProxy.isNullOrBlank()) {
            val proxy = java.net.URI.create(configuredProxy)
            testContext.paths.configDir.resolve("options/proxy.settings.xml").writeText("""
                <application><component name="HttpConfigurable">
                  <option name="USE_HTTP_PROXY" value="true"/>
                  <option name="PROXY_HOST" value="${proxy.host}"/>
                  <option name="PROXY_PORT" value="${if (proxy.port < 0) 80 else proxy.port}"/>
                  <option name="PROXY_EXCEPTIONS" value="localhost,127.0.0.1"/>
                </component></application>
            """.trimIndent())
        } else Files.deleteIfExists(testContext.paths.configDir.resolve("options/proxy.settings.xml"))
        if (downloadFixture != null) testContext.applyVMOptionsPatch {
            addSystemProperty("https.proxyHost", "127.0.0.1")
            addSystemProperty("https.proxyPort", downloadFixture.port.toString())
            addSystemProperty("http.proxyHost", "127.0.0.1")
            addSystemProperty("http.proxyPort", downloadFixture.port.toString())
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
        val mediumHeight = minOf(900, displayBounds.height - 80)
        val wideWidth = minOf(1900, displayBounds.width - 20)
        val wideHeight = minOf(1000, displayBounds.height - 80)
        val jdbcFixture = if (review && !styleOnly) JdbcUiFixture(postgresPort, postgresPassword, h2) else null
        if (alterTableOnly) {
            check(jdbcFixture != null && !postgres && !h2) { "The focused inspector scenario owns its HSQLDB fixture; enable ui.review.enabled" }
            java.sql.DriverManager.getConnection(jdbcFixture.jdbcUrl,"SA","").use { conn ->
                conn.createStatement().use { statement ->
                    statement.execute("CREATE TABLE \"customers\" (\"id\" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, \"name\" VARCHAR(120) NOT NULL, \"email\" VARCHAR(180), \"status\" VARCHAR(20) DEFAULT 'active', \"balance\" DECIMAL(10,2) DEFAULT 0)")
                    statement.execute("INSERT INTO \"customers\"(\"name\",\"email\") VALUES('Ada Lovelace','ada@example.com')")
                }
            }
        }
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
                    // Environment notices arrive asynchronously and can cover the
                    // footer. Dismiss their balloons, not production status/dialogs.
                    cast(frame.component, LiveIdeFrame::class).getBalloonLayout()?.closeAll()
                    val root = cast(frame.component, LiveFrame::class).getRootPane()
                    root.paintImmediately(0, 0, frame.component.width, frame.component.height)
                }
            }
            if (createTableOnly) {
                check(jdbcFixture != null && !postgres && !h2) { "The focused Composer scenario owns its HSQLDB fixture; enable ui.review.enabled" }
                verifyCreateComposer(frame, pluginRoot, jdbcFixture, screenshotsDirectory)
                screenshotsDirectory.resolve("runtime-evidence.txt").writeText("""
                    Running IDE: ${actualTheme.getName()} ($themeId)
                    Production Column Composer loaded in the installed Lattice plugin.
                    Owned HSQLDB fixture; CREATE SQL, column order, identity, primary key, precision, defaults and inserted data verified using JDBC.
                    Checked hidden invalid drafts, filtering, duplicate names, add/remove/reorder, discard handling, busy animation and database failure with retry.
                    Captures are actual desktop pixels; create-composer-dialog.png is a crop of the live modal.
                """.trimIndent())
                return@useDriverAndCloseIde
            }
            if (alterTableOnly) {
                verifyAlterInspector(frame, pluginRoot, requireNotNull(jdbcFixture), screenshotsDirectory)
                screenshotsDirectory.resolve("runtime-evidence.txt").writeText("""
                    Running IDE: ${actualTheme.getName()} ($themeId)
                    Production Column Inspector loaded in the installed Lattice plugin.
                    Real HSQLDB fixture; a mixed batch combines add, modify, rename column, drop and rename table, verified with JDBC metadata.
                    Checked retained drafts, invalid hidden input, filtering, full SQL preview, busy animation, partial-success recovery, retry and destructive confirmation.
                    Captures are actual desktop pixels; alter-inspector-dialog.png is a crop of the live modal.
                """.trimIndent())
                return@useDriverAndCloseIde
            }
            if (explorerSearchOnly) {
                openConnectionMenu(pluginRoot, frame, "HSQLDB…")
                frame.x(DialogUiComponent::class.java) { byTitle("New connection") }.waitFound().also { dialog ->
                    dialog.x(JComboBoxUiComponent::class.java) {
                        and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName("Mode:"))
                    }.waitFound().selectItem("In-Memory (mem)")
                    for ((name, value) in listOf("Name:" to "Lattice search demo", "Database name:" to "lattice_search_demo", "User:" to "SA")) {
                        dialog.x(JTextFieldUI::class.java) {
                            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName(name))
                        }.waitFound().text = value
                    }
                    dialog.pressButton("Add connection")
                }
                ideFrame { waitForNoOpenedDialogs() }
                openConnectionMenu(pluginRoot, frame, "MySQL…")
                frame.x(DialogUiComponent::class.java) { byTitle("New connection") }.waitFound().also { dialog ->
                    dialog.x(JTextFieldUI::class.java) {
                        and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Name:"))
                    }.waitFound().text = "Analytics offline"
                    dialog.pressButton("Add connection")
                }
                ideFrame { waitForNoOpenedDialogs() }
                val tree = pluginRoot.x(JTreeUiComponent::class.java) { byJavaClass("com.intellij.ui.treeStructure.Tree") }.waitFound()
                tree.fixture.expandRow(2)
                val schema = arrayOf("Data Sources", "HSQLDB", "Lattice search demo", "PUBLIC")
                waitUntil("search fixture schema connected") { tree.pathExists(*schema) }
                tree.fixture.expandPath(schema.joinToString(tree.fixture.separator()))
                waitUntil("search fixture tables loaded") { tree.pathExists(*(schema + "Tables (0)")) }
                for (name in listOf("SEARCH_CUSTOMERS", "SEARCH_ORDERS")) {
                    tree.fixture.rightClickPath(schema.joinToString(tree.fixture.separator()))
                    frame.x { byVisibleText("Create table…") }.waitFound().click()
                    frame.x(DialogUiComponent::class.java) { byTitle("Create table in PUBLIC") }.waitFound().also { dialog ->
                        inspectorInput(dialog, "Table name").text = name
                        dialog.x { byAccessibleName("Create new table") }.waitFound().click()
                    }
                    frame.x(DialogUiComponent::class.java) { byTitle("Table created") }.waitFound().pressButton("OK")
                    ideFrame { waitForNoOpenedDialogs() }
                    waitUntil("$name in the explorer") { tree.pathExists(*(schema + "Tables (${if (name == "SEARCH_CUSTOMERS") 1 else 2})")) }
                }
                val initialRows = withContext(OnDispatcher.EDT) { cast(tree.component, LiveTree::class).getRowCount() }
                pluginRoot.x { byAccessibleName("Search explorer") }.waitFound().click()
                val drawer = pluginRoot.x { byJavaClass("com.segfault03.ideadb.ui.DatabaseExplorerSearchBar") }.waitFound()
                val search = drawer.x(JTextFieldUI::class.java) { byAccessibleName("Search explorer names") }.waitFound()
                val counter = drawer.x { byAccessibleName("Explorer search results") }.waitFound()
                fun waitForMatches(count: Int) = waitUntil("$count explorer search matches") {
                    val icon = cast(counter.component, LiveLabel::class).getIcon()
                    val loading = icon != null && cast(icon, LiveObject::class).getClass().getName().contains("AnimatedIcon")
                    !loading && cast(tree.component, LiveTree::class).getClientProperty("lattice.explorer.search.matches")?.size() == count
                }
                fun openSearchFilters(): UiComponent {
                    drawer.x { byAccessibleName("Search name filters") }.waitFound().click()
                    return ui.x { byAccessibleName("Explorer search filters") }.waitFound()
                }
                fun choose(popup: UiComponent, name: String) = popup.x {
                    and(byType("javax.swing.JCheckBox"), byAccessibleName(name))
                }.waitFound().click()
                search.text = "created_at"
                waitForMatches(2) // Both column folders were still unexpanded when the search began.
                captureScreen(screenshotsDirectory.resolve("explorer-search-all-narrow.png"))
                var popup = openSearchFilters()
                choose(popup, "Tables")
                waitForMatches(0)
                choose(popup, "Keys / columns")
                waitForMatches(2)
                captureScreen(screenshotsDirectory.resolve("explorer-search-multiple-filters.png"))
                pressEscape()
                search.text = "search_"
                waitForMatches(2)
                popup = openSearchFilters()
                choose(popup, "All")
                choose(popup, "Schemas")
                pressEscape()
                search.text = "PUBLIC"
                waitForMatches(1)
                popup = openSearchFilters()
                choose(popup, "Data sources")
                pressEscape()
                search.text = "Analytics"
                waitForMatches(0)
                ideFrame { waitForNoOpenedDialogs() }
                search.text = "Lattice"
                waitForMatches(1)
                popup = openSearchFilters()
                choose(popup, "All")
                pressEscape()
                search.text = ""
                waitForMatches(0)
                check(withContext(OnDispatcher.EDT) { cast(tree.component, LiveTree::class).getRowCount() } == initialRows) {
                    "Clearing search must restore the prior tree expansion"
                }
                search.text = "created_at"
                waitForMatches(2)
                withContext(OnDispatcher.EDT) { cast(frame.component, Window::class).setBounds(10, 40, wideWidth, normalHeight) }
                Thread.sleep(400)
                val origin = pluginRoot.component.getLocationOnScreen()
                val dragX = origin.x - 2
                val dragY = origin.y + pluginRoot.component.height / 2
                val targetX = dragX - (1050 - pluginRoot.component.width)
                robot.mouseMove(dragX, dragY)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                repeat(12) { step -> robot.mouseMove(dragX + (targetX - dragX) * (step + 1) / 12, dragY); Thread.sleep(25) }
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                waitUntil("inline search drawer") {
                    val toolbar = pluginRoot.x { byAccessibleName("Explorer actions") }.waitFound()
                    drawer.component.getLocationOnScreen().y == toolbar.component.getLocationOnScreen().y
                }
                captureScreen(screenshotsDirectory.resolve("explorer-search-wide-inline.png"))
                tree.fixture.rightClickPath(schema.joinToString(tree.fixture.separator()))
                frame.x { byVisibleText("Refresh tables") }.waitFound().click()
                Thread.sleep(400)
                waitForMatches(2)
                drawer.x { byAccessibleName("Close explorer search") }.waitFound().click()
                waitUntil("search drawer closed") {
                    pluginRoot.xx { byJavaClass("com.segfault03.ideadb.ui.DatabaseExplorerSearchBar") }.list().isEmpty()
                }
                println("Explorer search: connected sources only; unexpanded columns; all and multiple filters; all matches highlighted; expansion restored; narrow and inline layouts")
                return@useDriverAndCloseIde
            }
            if (sourceFilterOnly) {
                fun openPicker(): UiComponent {
                    pluginRoot.x { byAccessibleName("View only…") }.waitFound().click()
                    return ui.x { byJavaClass("com.segfault03.ideadb.ui.DatabaseSourceFilterPopup") }.waitFound()
                }
                fun checkbox(picker: UiComponent, name: String) = picker.x {
                    and(byType("javax.swing.JCheckBox"), byAccessibleName(name))
                }.waitFound()
                for ((type, name) in listOf("HSQLDB" to "Scratch database", "MySQL" to "Analytics server", "PostgreSQL" to "Reporting cluster")) {
                    openConnectionMenu(pluginRoot, frame, "$type…")
                    frame.x(DialogUiComponent::class.java) { byTitle("New connection") }.waitFound().also { dialog ->
                        dialog.x(JTextFieldUI::class.java) {
                            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Name:"))
                        }.waitFound().text = name
                        dialog.pressButton("Add connection")
                    }
                    ideFrame { waitForNoOpenedDialogs() }
                    if (type == "MySQL") {
                        val emptyPicker = openPicker()
                        checkbox(emptyPicker, "All data sources").click()
                        checkbox(emptyPicker, "PostgreSQL").click()
                        emptyPicker.x { byVisibleText("Apply") }.waitFound().click()
                        pluginRoot.x { byVisibleText("No connections match this view") }.waitFound()
                        captureScreen(screenshotsDirectory.resolve("view-only-empty.png"))
                        pluginRoot.x { byVisibleText("Show all data sources") }.waitFound().click()
                    }
                }
                val tree = pluginRoot.x(JTreeUiComponent::class.java) {
                    byJavaClass("com.intellij.ui.treeStructure.Tree")
                }.waitFound()
                fun waitForRows(count: Int) = waitUntil("filtered explorer has $count rows") {
                    withContext(OnDispatcher.EDT) { cast(tree.component, LiveTree::class).getRowCount() == count }
                }
                waitForRows(7)
                captureScreen(screenshotsDirectory.resolve("view-only-all.png"))
                var picker = openPicker()
                check(cast(checkbox(picker, "All data sources").component, LiveSourceChoice::class).isSelected())
                captureScreen(screenshotsDirectory.resolve("view-only-picker.png"))
                checkbox(picker, "All data sources").click()
                check(!picker.x { byVisibleText("Apply") }.waitFound().component.isEnabled())
                checkbox(picker, "MySQL").click()
                captureScreen(screenshotsDirectory.resolve("view-only-picker-mysql.png"))
                picker.x { byVisibleText("Apply") }.waitFound().click()
                waitForRows(3)
                check(tree.pathExists("Data Sources", "MySQL"))
                captureScreen(screenshotsDirectory.resolve("view-only-mysql.png"))
                picker = openPicker()
                checkbox(picker, "HSQLDB").click()
                picker.x { byVisibleText("Apply") }.waitFound().click()
                waitForRows(5)
                check(tree.pathExists("Data Sources", "HSQLDB") && tree.pathExists("Data Sources", "MySQL"))
                captureScreen(screenshotsDirectory.resolve("view-only-multiple.png"))
                picker = openPicker()
                checkbox(picker, "All data sources").click()
                picker.x { byVisibleText("Cancel") }.waitFound().click()
                waitForRows(5)
                picker = openPicker()
                checkbox(picker, "All data sources").click()
                picker.x { byVisibleText("Apply") }.waitFound().click()
                waitForRows(7)
                check(tree.pathExists("Data Sources", "PostgreSQL"))
                captureScreen(screenshotsDirectory.resolve("view-only-all-restored.png"))
                println("Source filter: no-match recovery, new source included by All, empty draft disabled, one type, multiple types, Cancel and All restored")
                return@useDriverAndCloseIde
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
            assertDataSourcePicker(mysqlDialog)
            mysqlDialog.x { byAccessibleName("HSQLDB") }.waitFound().click()
            assertDriverSummary(mysqlDialog, "2.7.2 · DISCOVERED")
            captureScreen(screenshotsDirectory.resolve("driver-type-switch-hsql-project-default.png"))
            mysqlDialog.x { byAccessibleName("MySQL") }.waitFound().click()
            assertDriverSummary(mysqlDialog, "8.4.0 · DISCOVERED")
            mysqlAvailableDriverCombo.selectItem("26.7.0 · BUNDLED")
            mysqlDialog.x { byAccessibleName("HSQLDB") }.waitFound().click()
            assertDriverSummary(mysqlDialog, "2.7.2 · DISCOVERED")
            mysqlDialog.x { byAccessibleName("MySQL") }.waitFound().click()
            assertDriverSummary(mysqlDialog, "26.7.0 · BUNDLED")
            captureScreen(screenshotsDirectory.resolve("driver-type-switch-mysql-manual-choice.png"))
            mysqlAvailableDriverCombo.selectItem("8.4.0 · DISCOVERED")
            if (review) {
                mysqlAvailableDriverCombo.selectItem("26.7.0 · BUNDLED")
                assertDriverSummary(mysqlDialog, "26.7.0 · BUNDLED")
                captureScreen(screenshotsDirectory.resolve("mysql-driver-summary-bundled.png"))
                mysqlAvailableDriverCombo.selectItem("8.4.0 · DISCOVERED")
            }

            if (!postgres && !h2 && !System.getProperty("ui.skip.remote.downloads", "false").toBoolean()) {
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
                val downloadGate = requireNotNull(downloadFixture)
                downloadGate.pauseResponses()
                try {
                    mysqlDownloadButton.click()
                    downloadGate.awaitBlockedResponse()
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
                } finally { downloadGate.resumeResponses() }
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
                    val failureTest = mysqlDialog.x { byVisibleText("Test connection") }.waitFound()
                    waitUntil("downloaded driver ready for connection testing") { failureTest.component.isEnabled() }
                    // Switching to JDBC URL resizes the modal. Xvfb can lose Robot
                    // clicks at its old coordinates; dispatch the real button action
                    // on the IDE EDT and still require the backend's failure dialog.
                    withContext(OnDispatcher.EDT) { cast(failureTest.component, LiveToolbarButton::class).doClick() }
                    waitUntil("failed connection status from the downloaded driver") {
                        mysqlDialog.hasSubtext("Connection failed · Check the settings")
                    }
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
                modeCombo.selectItem("Remote Server")
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
            selectComboItemWithKeyboard(driverSourceCombo, "Available drivers")
            waitUntil("available driver card is showing after switching source") {
                availableDriverCombo.component.isShowing()
            }
            availableDriverCombo.selectItem("2.7.3-jdk8 · DOWNLOADED")
            assertDriverSummary(connectionDialog, "2.7.3-jdk8 · DOWNLOADED")

            if (h2) {
                connectionDialog.x { byAccessibleName("H2") }.waitFound().click()
                availableDriverCombo.selectItem("2.3.232 · BUNDLED")
                assertDriverSummary(connectionDialog, "2.3.232 · BUNDLED")
                val mode = connectionDialog.x(JComboBoxUiComponent::class.java) { and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName("Mode:")) }.waitFound()
                mode.selectItem("In-Memory (mem)")
                val h2MemoryName = connectionDialog.x(JTextFieldUI::class.java) { and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Database name:")) }.waitFound()
                h2MemoryName.text = ""
                check(!connectionDialog.x { byVisibleText("Test connection") }.waitFound().component.isEnabled()) { "H2 memory mode requires a database name" }
                h2MemoryName.text = "lattice_h2_ui"
                check(connectionDialog.x { byVisibleText("Test connection") }.waitFound().component.isEnabled())
                captureScreen(screenshotsDirectory.resolve("h2-memory-form.png"))
                mode.selectItem("Embedded File (file)")
                captureScreen(screenshotsDirectory.resolve("h2-file-form.png"))
                mode.selectItem("Remote Server")
                val h2Port = connectionDialog.x(JTextFieldUI::class.java) { and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Port:")) }.waitFound()
                check(h2Port.text == "9092") { "H2 must use its own default TCP port, got ${h2Port.text}" }
                h2Port.text = "0"
                check(!connectionDialog.x { byVisibleText("Test connection") }.waitFound().component.isEnabled())
                h2Port.text = "9092"
                captureScreen(screenshotsDirectory.resolve("h2-server-form.png"))
                captureScreen(screenshotsDirectory.resolve("h2-driver-selection.png"))
            }
            if (postgres) {
                connectionDialog.x { byAccessibleName("PostgreSQL") }.waitFound().click()
                availableDriverCombo.selectItem("42.7.14 · BUNDLED")
                assertDriverSummary(connectionDialog, "42.7.14 · BUNDLED")
                captureScreen(screenshotsDirectory.resolve("postgresql-driver-selection.png"))
            }

            if (jdbcFixture != null) {
                connectionDialog.x { byVisibleText("JDBC URL") }.waitFound().click()
                connectionDialog.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("JDBC URL:"))
                }.waitFound().text = jdbcFixture.jdbcUrl
                connectionDialog.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("User:"))
                }.waitFound().text = if (h2) "sa" else if (postgres) "postgres" else "SA"
                if (postgres) connectionDialog.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputPasswordField"), byAccessibleName("Password:"))
                }.waitFound().text = postgresPassword
                captureBusyState(jdbcFixture, connectionDialog, "Testing connection…", "connection-testing.png", screenshotsDirectory) {
                    connectionDialog.x { byVisibleText("Test connection") }.waitFound().click()
                }
            } else connectionDialog.x { byVisibleText("Test connection") }.waitFound().click()
            waitUntil("successful database connection test") {
                connectionDialog.hasSubtext(if (h2) "Connected · H2" else if (postgres) "Connected · PostgreSQL" else "Connected · HSQL Database Engine")
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
            if (jdbcFixture != null) {
                var overlay: LiveBalloon? = null
                captureBusyState(jdbcFixture, pluginRoot, "Connecting to database…", "explorer-connecting.png", screenshotsDirectory,
                    beforeCapture = {
                    // Reproduce the first-session notice that covered the spinner
                    // in CI. It must be dismissed before inspecting native pixels.
                    overlay = showIdeNotificationOverlay(frame,
                        pluginRoot.x { byAccessibleName("Database explorer status") }.waitFound())
                    savePng(screenshotsDirectory.resolve("explorer-notification-overlay.png"),
                        Robot().createScreenCapture(displayBounds))
                    }) {
                    withContext(OnDispatcher.EDT) { cast(databaseTree.component, LiveTree::class).expandRow(2) }
                }
                check(requireNotNull(overlay).isDisposed()) { "IDE notification overlay must be dismissed before capturing the spinner" }
                println("Native notification overlay dismissed; explorer spinner still animates naturally")
            } else databaseTree.fixture.expandRow(2)
            val connectionPath = arrayOf("Data Sources", if (h2) "H2" else if (postgres) "PostgreSQL" else "HSQLDB", "Lattice UI demo")
            val schemaName = if (postgres) "public" else "PUBLIC"
            val publicSchemaPath = connectionPath + schemaName
            waitUntil("connected schema in the explorer") {
                databaseTree.pathExists(*publicSchemaPath)
            }
            captureScreen(screenshotsDirectory.resolve("connected-explorer.png"))
            if (!cellDetailsOnly) assertExplorerOverflow(pluginRoot, frame, screenshotsDirectory)
            // Keep a saved but unconnected MySQL source beside the live HSQLDB fixture
            // so the actual IDE capture exercises more than one source-type section.
            openConnectionMenu(pluginRoot, frame, "MySQL…")
            frame.x(DialogUiComponent::class.java) { byTitle("New connection") }.waitFound().also { mysqlDialog ->
                mysqlDialog.x(JTextFieldUI::class.java) {
                    and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Name:"))
                }.waitFound().text = "Analytics server"
                mysqlDialog.pressButton("Add connection")
            }
            ideFrame { waitForNoOpenedDialogs() }
            waitUntil("MySQL section alongside the live HSQLDB section") {
                databaseTree.pathExists("Data Sources", "MySQL")
            }
            databaseTree.fixture.expandPath(connectionPath.joinToString(databaseTree.fixture.separator()))
            waitUntil("HSQLDB connection restored after adding the second source type") {
                databaseTree.pathExists(*publicSchemaPath)
            }
            captureScreen(screenshotsDirectory.resolve("data-source-groups-open.png"))
            if (review && !styleOnly) {
                databaseTree.fixture.rightClickPath(connectionPath.joinToString(databaseTree.fixture.separator()))
                captureScreen(screenshotsDirectory.resolve("connection-context-menu.png"))
                frame.x { byVisibleText("Edit connection…") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Edit connection") }.waitFound().also {
                    assertDriverSummary(it, if (h2) "2.3.232 · BUNDLED" else if (postgres) "42.7.14 · BUNDLED" else "2.7.3-jdk8 · DOWNLOADED")
                    captureScreen(screenshotsDirectory.resolve("edit-connection.png"))
                    it.pressButton("Cancel")
                }
                // The HSQL scenario exercises removal of a retained explicit driver.
                if (!postgres && !h2) {
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
                byTitle("Create table in $schemaName")
            }.waitFound()
            inspectorInput(createTableDialog, "Table name").text = "LATTICE_PEOPLE"
            Thread.sleep(500)
            assertCreateTableLayout(createTableDialog)
            captureScreen(screenshotsDirectory.resolve("create-table-dialog.png"))
            if (jdbcFixture != null) captureBusyState(jdbcFixture, createTableDialog, "Creating table…", "explorer-creating-table.png", screenshotsDirectory) {
                createTableDialog.x { byAccessibleName("Create new table") }.waitFound().click()
            } else createTableDialog.x { byAccessibleName("Create new table") }.waitFound().click()
            frame.x(DialogUiComponent::class.java) {
                byTitle("Table created")
            }.waitFound().pressButton("OK")
            ideFrame {
                waitForNoOpenedDialogs()
            }

            if (cellDetailsOnly) {
                java.sql.DriverManager.getConnection(requireNotNull(jdbcFixture).jdbcUrl, "SA", "").use { conn ->
                    conn.createStatement().use { statement ->
                        statement.execute("ALTER TABLE PUBLIC.LATTICE_PEOPLE ADD COLUMN \"email\" VARCHAR(180)")
                        statement.execute("ALTER TABLE PUBLIC.LATTICE_PEOPLE ADD COLUMN \"status\" VARCHAR(20) DEFAULT 'active'")
                        statement.execute("ALTER TABLE PUBLIC.LATTICE_PEOPLE ADD COLUMN \"balance\" DECIMAL(10,2) DEFAULT 0")
                    }
                    val names = listOf("Amelia Brooks", "Liam Chen", "Sofia Patel", "Noah Williams", "Olivia Reyes",
                        "Ethan Kim", "Isabella Rossi", "Lucas Martin", "Mia Thompson", "Aiden Davis",
                        "Charlotte Wilson", "Leo Garcia")
                    conn.prepareStatement("INSERT INTO PUBLIC.LATTICE_PEOPLE (\"name\", \"created_at\", \"email\", \"status\", \"balance\") VALUES (?, ?, ?, ?, ?)").use { insert ->
                        names.forEachIndexed { index, name ->
                            insert.setString(1, name)
                            insert.setTimestamp(2, java.sql.Timestamp.valueOf("2026-10-${(index + 1).toString().padStart(2, '0')} 10:30:00"))
                            insert.setString(3, name.lowercase().replace(" ", ".") + "@example.com")
                            insert.setString(4, if (index == 4 || index == 8) "inactive" else "active")
                            insert.setBigDecimal(5, java.math.BigDecimal.valueOf(if (index % 3 == 0) 240.50 else 0.0))
                            insert.addBatch()
                        }
                        insert.executeBatch()
                    }
                }
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
            val commitButton = tableEditor.x { byAccessibleName("Commit pending changes to the database") }.waitFound()
            val revertButton = tableEditor.x { byAccessibleName("Revert pending changes") }.waitFound()
            if (!cellDetailsOnly) {
            waitUntil("empty table data loaded") { tableEditor.hasSubtext("0 rows") }
            check(dataGrid.rowCount() == 0) { "New LATTICE_PEOPLE table should start empty" }
            captureScreen(screenshotsDirectory.resolve("table-editor-empty.png"))
            if (review) {
                assertFilterLayout(tableEditor, 1)
                val filterReviewWidth = twoRowFilterWindowWidth(frame, tableEditor)
                check(filterReviewWidth <= displayBounds.width - 40) { "Display cannot fit the two-row filter review: $filterReviewWidth" }
                frame.resize(filterReviewWidth, mediumHeight)
                captureScreen(screenshotsDirectory.resolve("table-medium-filters-initial.png"))
                assertFilterLayout(tableEditor, 2)
                frame.resize(normalWidth, normalHeight)
            }

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
            if (jdbcFixture != null && !cellDetailsOnly) {
                dataGrid.clickCell(0, 1)
                tableEditor.x { byAccessibleName("Delete selected rows") }.waitFound().click()
                val confirm = frame.x(DialogUiComponent::class.java) { byTitle("Delete rows") }.waitFound()
                captureBusyState(jdbcFixture, tableEditor, "Deleting saved rows…", "table-deleting-rows.png", screenshotsDirectory) {
                    confirm.pressButton("Delete rows")
                }
                waitUntil("saved row deleted and refreshed from the database") { dataGrid.rowCount() == 0 && tableEditor.hasSubtext("0 rows ·") }
                captureScreen(screenshotsDirectory.resolve("table-deleted-empty.png"))
                tableEditor.x { byAccessibleName("Add a new row") }.waitFound().click()
                replaceCellValue(dataGrid, 0, 1, "Ada Lovelace")
                commitButton.click()
                frame.x(DialogUiComponent::class.java) { byTitle("Changes Saved") }.waitFound().pressButton("OK")
                waitUntil("replacement saved row loaded") { dataGrid.rowCount() == 1 && tableEditor.hasSubtext("1 row ·") }
            }
            }

            if (cellDetailsOnly) {
                check(review && jdbcFixture != null) { "The focused Value Workspace capture requires the real IDE review and its owned HSQLDB fixture" }
                waitUntil("populated Value Workspace fixture") { dataGrid.rowCount() == 12 }
                withContext(OnDispatcher.EDT) {
                    cast(frame.component, Window::class).setBounds(16, 32, displayBounds.width - 32, displayBounds.height - 64)
                }
                ideFrame { invokeAction("HideAllWindows") }
                dataGrid.clickCell(0, 3)
                val details = tableEditor.x { byJavaClass("com.segfault03.ideadb.ui.CellDetailsPanel") }.waitFound()
                waitUntil("email inspector shows its value and cell coordinates") {
                    details.hasSubtext("amelia.brooks@example.com") &&
                        details.hasSubtext("Row 1") && details.hasSubtext("Column 4")
                }
                captureScreen(screenshotsDirectory.resolve("value-workspace.png"))

                details.x { byAccessibleName("Pin cell details") }.waitFound().click()
                waitUntil("cell details are pinned") { details.hasSubtext("Pinned to this cell") }
                dataGrid.clickCell(0, 0)
                waitUntil("pinned details retain Amelia email after another cell is selected") {
                    details.hasSubtext("amelia.brooks@example.com") && details.hasSubtext("Pinned to this cell")
                }
                captureScreen(screenshotsDirectory.resolve("value-workspace-pinned.png"))
                details.x { byAccessibleName("Unpin cell details") }.waitFound().click()

                val headerPoint = withContext(OnDispatcher.EDT) {
                    val table = cast(dataGrid.component, LiveTableEditor::class)
                    val header = table.getTableHeader()
                    val location = header.getLocationOnScreen()
                    val x = (0 until 3).sumOf { table.getColumnModel().getColumn(it).getWidth() }
                    Point(location.x + x + table.getColumnModel().getColumn(3).getWidth() / 2,
                        location.y + header.getHeight() / 2)
                }
                Robot().also { robot ->
                    robot.mouseMove(headerPoint.x, headerPoint.y)
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    robot.waitForIdle()
                }
                waitUntil("column header opens its schema details") {
                    details.hasSubtext("Column 4") && details.hasSubtext("Structure")
                }
                captureScreen(screenshotsDirectory.resolve("value-workspace-header-details.png"))
                val headerDetailsVerified = details.hasSubtext("Column 4") && details.hasSubtext("Structure")
                details.x { byAccessibleName("Pin column details") }.waitFound().click()
                waitUntil("column details are pinned") { details.hasSubtext("Pinned to this column") }
                dataGrid.clickCell(0, 0)
                waitUntil("pinned column details survive a cell selection") {
                    details.hasSubtext("Column 4") && details.hasSubtext("Pinned to this column")
                }
                captureScreen(screenshotsDirectory.resolve("value-workspace-header-pinned.png"))
                details.x { byAccessibleName("Unpin column details") }.waitFound().click()

                dataGrid.clickCell(0, 3)
                waitUntil("Amelia email details reopened") { details.hasSubtext("amelia.brooks@example.com") }
                val oppositeThemes = if (themeId.contains("Dark", true) || themeId == "Darcula")
                    listOf("Islands Light", "ExperimentalLight", "IntelliJ", "JetBrainsLightTheme")
                    else listOf("Islands Dark", "ExperimentalDark", "Darcula")
                val alternateTheme = oppositeThemes.firstOrNull { candidate ->
                    laf.getInstalledLookAndFeels().any { it.getName() == candidate }
                }
                if (alternateTheme != null) {
                    withContext(OnDispatcher.EDT) {
                        laf.setCurrentLookAndFeel(laf.findLaf(alternateTheme), true)
                        laf.updateUI()
                    }
                    waitUntil("IDE applies $alternateTheme") { laf.getCurrentUIThemeLookAndFeel().getId() == alternateTheme }
                    captureScreen(screenshotsDirectory.resolve("value-workspace-opposite-theme.png"))
                    val themedHeaderPoint = withContext(OnDispatcher.EDT) {
                        val table = cast(dataGrid.component, LiveTableEditor::class)
                        val header = table.getTableHeader()
                        val location = header.getLocationOnScreen()
                        val x = (0 until 3).sumOf { table.getColumnModel().getColumn(it).getWidth() }
                        Point(location.x + x + table.getColumnModel().getColumn(3).getWidth() / 2,
                            location.y + header.getHeight() / 2)
                    }
                    Robot().also { themedRobot ->
                        themedRobot.mouseMove(themedHeaderPoint.x, themedHeaderPoint.y)
                        themedRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                        themedRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                        themedRobot.waitForIdle()
                    }
                    waitUntil("opposite theme shows column structure") {
                        details.hasSubtext("Column 4") && details.hasSubtext("Structure")
                    }
                    captureScreen(screenshotsDirectory.resolve("value-workspace-header-opposite-theme.png"))
                    withContext(OnDispatcher.EDT) {
                        laf.setCurrentLookAndFeel(laf.findLaf(themeId), true)
                        laf.updateUI()
                    }
                    waitUntil("IDE restores $themeId") { laf.getCurrentUIThemeLookAndFeel().getId() == themeId }
                    dataGrid.clickCell(0, 3)
                    waitUntil("cell workspace returns after restoring theme") { details.hasSubtext("amelia.brooks@example.com") }
                    captureScreen(screenshotsDirectory.resolve("value-workspace-restored-theme.png"))
                }

                val valueArea = details.x { byAccessibleName("Cell value") }.waitFound()
                val valuePoint = valueArea.component.getLocationOnScreen()
                val robot = Robot()
                robot.mouseMove(valuePoint.x + valueArea.component.width / 2, valuePoint.y + valueArea.component.height / 2)
                repeat(2) {
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    if (it == 0) Thread.sleep(100)
                }
                robot.waitForIdle()
                waitUntil("double-clicking the value area enters edit mode") {
                    details.x { byAccessibleName("Apply") }.waitFound().component.isVisible()
                }
                captureScreen(screenshotsDirectory.resolve("value-workspace-editing.png"))

                details.x { byAccessibleName("Cancel") }.waitFound().click()
                val closeDetails = details.x { byAccessibleName("Close details panel") }.waitFound()
                closeDetails.moveMouse(Point(closeDetails.component.width / 2, closeDetails.component.height / 2))
                captureScreen(screenshotsDirectory.resolve("value-workspace-close-hover.png"))
                val liveDetails = details.component
                closeDetails.click()
                waitUntil("close control removes the inspector from the editor") { !liveDetails.isShowing() }
                dataGrid.clickCell(0, 3)
                waitUntil("cell selection reopens the inspector after closing it") {
                    details.component.isShowing() && details.hasSubtext("Row 1") && details.hasSubtext("Column 4")
                }

                Files.writeString(screenshotsDirectory.resolve("runtime-evidence.txt"), buildString {
                    appendLine("Focused native Value Workspace capture")
                    appendLine("IDE: ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
                    appendLine("Requested theme: $themeId")
                    appendLine("Loaded cell: ${tableCellText(dataGrid, 0, 3)}")
                    appendLine("Pin selection persistence: verified after selecting ID cell")
                    appendLine("Column header details: $headerDetailsVerified")
                    appendLine("Double-click edit mode: verified")
                    appendLine("Close and reopen controls: verified")
                    appendLine("Opposite theme switch: ${alternateTheme ?: "no supported opposite theme found; skipped"}")
                })
                println("Focused native Value Workspace flow completed")
                return@useDriverAndCloseIde
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
                assertFilterLayout(tableEditor, 1)
                tableEditor.x { byAccessibleName("Export data") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("table-export-menu.png"))
                frame.x { byVisibleText("Current page (includes pending edits)") }.waitFound().click()
                val sqlExport = frame.xx { byVisibleText("SQL…") }.list().first {
                    withContext(OnDispatcher.EDT) {
                        cast(it.component, LiveMenuItem::class).getParent().getInvoker().getText() == "Current page (includes pending edits)"
                    }
                }
                captureScreen(screenshotsDirectory.resolve("table-export-sql-submenu.png"))
                assertPaintedSurface(sqlExport, dataGrid, "SQL export submenu")
                pressEscape()
                pressEscape()
                frame.resize(narrowWidth, narrowHeight)
                openToolbarOverflow(tableEditor, "Table actions").also {
                    captureScreen(screenshotsDirectory.resolve("table-narrow-toolbar-expanded-pending.png"))
                    assertToolbarSingleRow(it, "expanded table")
                    assertIconOnlyOverflow(tableEditor.x { byAccessibleName("Table actions") }.waitFound(), it)
                    assertActionContrast(it.x { byAccessibleName("Commit pending changes to the database") }.waitFound())
                    assertActionContrast(it.x { byAccessibleName("Revert pending changes") }.waitFound())
                    closeToolbarOverflow(it)
                }
                frame.resize(normalWidth, normalHeight)
                databaseTree.fixture.rightClickPath(peoplePath.joinToString(databaseTree.fixture.separator()))
                frame.x { byVisibleText("Open in SQL console") }.waitFound().click()
                val console = frame.x { byJavaClass("com.segfault03.ideadb.ui.SqlQueryConsolePanel") }.waitFound()
                waitUntil("console database picker ready") { console.x { byVisibleText("Run") }.waitFound().component.isEnabled() }
                captureScreen(screenshotsDirectory.resolve("sql-console-ready.png"))
                val reviewedLightThemes = mutableListOf(themeId)
                // Reuse the live table and console to check every bundled light
                // palette without repeating IDE startup and database setup.
                if (System.getProperty("ui.style.lightThemes").toBoolean()) {
                    for (lightTheme in listOf("ExperimentalLight", "ExperimentalLightWithLightHeader",
                        "IntelliJ", "JetBrainsLightTheme", "Islands Light").filter {
                        it != themeId && (it != "Islands Light" || "Islands Light" in availableThemes)
                    }) {
                        withContext(OnDispatcher.EDT) {
                            laf.setCurrentLookAndFeel(laf.findLaf(lightTheme), true)
                            laf.updateUI()
                        }
                        check(laf.getCurrentUIThemeLookAndFeel().getId() == lightTheme)
                        val themeDirectory = screenshotsDirectory.resolve(lightTheme)
                        Files.createDirectories(themeDirectory)
                        captureScreen(themeDirectory.resolve("sql-console-ready.png"))
                        databaseTree.fixture.rightClickPath(peoplePath.joinToString(databaseTree.fixture.separator()))
                        frame.x { byVisibleText("Open data editor") }.waitFound().click()
                        assertFilterLayout(tableEditor, 1)
                        captureScreen(themeDirectory.resolve("table-filters.png"))
                        tableEditor.x { byAccessibleName("Export data") }.waitFound().click()
                        frame.x { byVisibleText("Current page (includes pending edits)") }.waitFound().click()
                        val themedSqlExport = frame.xx { byVisibleText("SQL…") }.list().first {
                            withContext(OnDispatcher.EDT) {
                                cast(it.component, LiveMenuItem::class).getParent().getInvoker().getText() == "Current page (includes pending edits)"
                            }
                        }
                        captureScreen(themeDirectory.resolve("table-export-sql-submenu.png"))
                        assertPaintedSurface(themedSqlExport, dataGrid, "$lightTheme SQL export submenu")
                        pressEscape()
                        pressEscape()
                        databaseTree.fixture.rightClickPath(peoplePath.joinToString(databaseTree.fixture.separator()))
                        frame.x { byVisibleText("Open in SQL console") }.waitFound().click()
                        reviewedLightThemes.add(lightTheme)
                    }
                }
                verifyKeyHeaderFlow(frame, databaseTree, peoplePath, tableEditor, console, peopleSql, screenshotsDirectory)
                Files.writeString(screenshotsDirectory.resolve("runtime-evidence.txt"), buildString {
                    appendLine("IDE target: IntelliJ IDEA $productCode ${System.getProperty("ui.ide.version")} (${System.getProperty("ui.ide.build")})")
                    appendLine("Actual IDE theme: ${actualTheme.getName()} (${actualTheme.getId()})")
                    appendLine("Available IDE themes: $availableThemes")
                    appendLine("Light theme palettes reviewed: $reviewedLightThemes")
                    appendLine("UI driver: JetBrains Starter and Driver; real production DatabaseMainPanel, TableDataEditorPanel, DatabaseTable and JTable editors")
                    appendLine("IDE JBR release: ${Files.readString(ideHome.resolve("jbr/release")).lineSequence().filter { it.startsWith("JAVA_VERSION=") || it.startsWith("IMPLEMENTOR=") }.joinToString(", ")}")
                    appendLine("IDE display: ${displayBounds.width}x${displayBounds.height}; scale 1")
                    appendLine("Verified: six explorer actions in native hover overflow and working Add Connection invoker; failed connection status; valid/invalid/unchanged table edits; text and integer editor theme surfaces; native menu background; full auto-refresh caption; white active action labels and painted glyphs in normal and overflow toolbars")
                    appendLine("Key header regressions: primary, foreign and combined badges/colors in table and aliased SQL results; markers clear after a new query or schema refresh")
                    appendLine("Scope: focused UI regressions including SQL export submenu palette, table filters and console layout; the default review continues through commit, export, alter-table and SQL flows")
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
                check(!applyButton.component.isEnabled()) { "Empty clauses must disable Apply" }
                check(!tableEditor.x { byAccessibleName("Clear WHERE and ORDER BY clauses") }.waitFound().component.isEnabled())
                val whereInput = tableEditor.xx(JTextFieldUI::class.java) {
                    byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField")
                }.list().first()
                whereInput.text = "1 = 1"
                applyButton.click()
                waitUntil("Apply completed") { applyButton.component.isEnabled() }
                assertButtonReset(applyButton, dataGrid)
                captureScreen(screenshotsDirectory.resolve("table-action-focus-after-click.png"))
                tableEditor.x { byAccessibleName("Table options") }.waitFound().click()
                captureBusyState(jdbcFixture, tableEditor, "Counting saved rows…", "table-counting-rows.png", screenshotsDirectory) {
                    frame.x { byVisibleText("Count rows") }.waitFound().click()
                }
                waitUntil("row count completed") { tableEditor.hasSubtext("1 saved row matches the filter") }
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
                tableEditor.x { byAccessibleName("Clear WHERE and ORDER BY clauses") }.waitFound().click()
                check(!tableEditor.x { byAccessibleName("Clear WHERE and ORDER BY clauses") }.waitFound().component.isEnabled())
                check(applyButton.component.isEnabled()) { "Clearing an applied filter must allow resetting the data" }
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
                val alter = frame.x(DialogUiComponent::class.java) { byTitle("Edit table: LATTICE_PEOPLE") }.waitFound()
                assertInspectorLayout(alter)
                captureScreen(screenshotsDirectory.resolve("alter-modify.png"))
                alter.x { byAccessibleName("Add column") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("alter-add.png"))
                inspectorInput(alter, "Column name").text = "name"
                check(!alter.x { byAccessibleName("Apply table changes") }.waitFound().component.isEnabled())
                captureScreen(screenshotsDirectory.resolve("alter-validation.png"))
                alter.x { byAccessibleName("Drop column") }.waitFound().click() // Remove the invalid new draft.
                selectInspectorColumn(alter, 0)
                alter.x { byAccessibleName("Rename column") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("alter-rename-column.png"))
                selectInspectorColumn(alter, 0)
                alter.x { byAccessibleName("Drop column") }.waitFound().click()
                waitUntil("drop preview ready") { alter.x { byAccessibleName("Apply table changes") }.waitFound().component.isEnabled() }
                alter.x { byAccessibleName("Apply table changes") }.waitFound().click()
                frame.x(DialogUiComponent::class.java) { byTitle("Drop column") }.waitFound().also {
                    captureScreen(screenshotsDirectory.resolve("drop-column-confirmation.png")); it.pressButton("Cancel")
                }
                alter.x { byAccessibleName("Restore column") }.waitFound().click()
                alter.x { byAccessibleName("Rename table") }.waitFound().click()
                captureScreen(screenshotsDirectory.resolve("alter-rename-table.png"))
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
                    it.component.isShowing() && withContext(OnDispatcher.EDT) {
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
                if (postgres || h2) for (format in listOf("JSON", "SQL")) {
                    tableEditor.x { byAccessibleName("Export data") }.waitFound().click()
                    frame.x { byVisibleText("All persisted rows (current filter)") }.waitFound().click()
                    fun visibleFormat(label: String) = frame.xx { byVisibleText("$label…") }.list().first {
                        it.component.isShowing() && withContext(OnDispatcher.EDT) {
                            cast(it.component, LiveMenuItem::class).getParent().getInvoker().getText() == "All persisted rows (current filter)"
                        }
                    }
                    val firstItem = visibleFormat("CSV").component
                    val chosenItem = visibleFormat(format).component
                    val firstPoint = firstItem.getLocationOnScreen()
                    val chosenPoint = chosenItem.getLocationOnScreen()
                    // Enter the submenu horizontally, then stay inside it while moving down.
                    // Driver's smooth diagonal move can hover a sibling and close the submenu.
                    val formatRobot = Robot().apply { autoDelay = 100 }
                    formatRobot.mouseMove(firstPoint.x + firstItem.width / 2, firstPoint.y + firstItem.height / 2)
                    formatRobot.mouseMove(chosenPoint.x + chosenItem.width / 2, chosenPoint.y + chosenItem.height / 2)
                    formatRobot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                    formatRobot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                    formatRobot.waitForIdle()
                    val save = frame.x(DialogUiComponent::class.java) { byTitle("Export All Persisted Rows") }.waitFound()
                    val exported = screenshotsDirectory.resolve("persisted-rows.${format.lowercase()}").toAbsolutePath()
                    save.xx(JTextFieldUI::class.java) { byType(javax.swing.JTextField::class.java) }
                        .list().single { it.text.endsWith(".${format.lowercase()}") }.text = exported.toString()
                    save.pressButton("OK")
                    frame.x(DialogUiComponent::class.java) { byTitle("Export Complete") }.waitFound().pressButton("OK")
                    val text = Files.readString(exported)
                    check(text.contains("Ada Lovelace")) { "$format persisted export lost its saved value" }
                    if (format == "SQL") check(text.contains("INSERT INTO $peopleSql")) { "SQL export used an unexpected target: $text" }
                    if (format == "JSON") check(text.trimStart().startsWith("["))
                    captureScreen(screenshotsDirectory.resolve("${if (h2) "h2" else "postgresql"}-export-${format.lowercase()}.png"))
                }
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
                    // Revert resets the model immediately, then reloads asynchronously.
                    // Hold a real response to verify that actions remain blocked during that reload.
                    captureBusyState(jdbcFixture, tableEditor, "Loading table data…", "table-narrow-reverting.png", screenshotsDirectory) {
                        it.x { byAccessibleName("Revert pending changes") }.waitFound().click()
                    }
                }
                waitUntil("narrow Revert reload completes") {
                    tableCellText(dataGrid, 0, 1) == "Ada Lovelace" && dataGrid.component.isEnabled()
                }
                closeToolbarOverflow(pendingOverflow)
                whereInput.text = "\"id\" = -999"
                val narrowApply = tableEditor.x { byVisibleText("Apply") }.waitFound()
                waitUntil("narrow Apply enabled after Revert reload") { narrowApply.component.isEnabled() }
                narrowApply.click()
                waitUntil("narrow Apply filters rows") { dataGrid.rowCount() == 0 && dataGrid.component.isEnabled() }
                captureScreen(screenshotsDirectory.resolve("table-narrow-filter.png"))
                assertEditorControlsVisible(tableEditor, "narrow filtered table")
                whereInput.text = ""
                tableEditor.x { byVisibleText("Apply") }.waitFound().click()
                waitUntil("narrow filter reset") { dataGrid.rowCount() == 1 }
                val ideWindow = cast(frame.component, Window::class)
                val mediumWidth = twoRowFilterWindowWidth(frame, tableEditor)
                check(mediumWidth <= displayBounds.width - 40) { "Display cannot fit the two-row filter review: $mediumWidth" }
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
                withContext(OnDispatcher.EDT) { queryText.setText("-- Write your query\n") }
                check(!runQuery.component.isEnabled()) { "Comment-only SQL must disable Run" }
                console.x { byAccessibleName("Clear query text") }.waitFound().click()
                check(!runQuery.component.isEnabled())
                check(!console.x { byAccessibleName("Clear query text") }.waitFound().component.isEnabled())
                // Bounded real HSQLDB work also exercises cancellation with no prior results.
                val tenValues = "(VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9))"
                val cancellationSql = if (h2) "SELECT SUM(SIN(X)) FROM SYSTEM_RANGE(1,1000000000)" else if (postgres) "SELECT pg_sleep(20)" else "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SYSTEM_COLUMNS A CROSS JOIN INFORMATION_SCHEMA.SYSTEM_COLUMNS B CROSS JOIN $tenValues C(n) CROSS JOIN $tenValues D(n)"
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
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM $peopleSql") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL SELECT completed") { console.hasSubtext("1 row ·") }
                val sqlGrid = console.x(JTableUiComponent::class.java) { byJavaClass("com.segfault03.ideadb.ui.DatabaseTable") }.waitFound()
                captureScreen(screenshotsDirectory.resolve("sql-console-results.png"))
                assertNullContrast(sqlGrid, 0, 2)
                captureScreen(screenshotsDirectory.resolve("sql-console-null-unselected.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM $peopleSql UNION ALL SELECT * FROM $peopleSql") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("plural SQL row count") { console.hasSubtext("2 rows ·") }
                captureScreen(screenshotsDirectory.resolve("sql-console-multiple-rows.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM $peopleSql WHERE \"id\" = -999") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("empty SQL result") { console.hasSubtext("0 rows") }
                captureScreen(screenshotsDirectory.resolve("sql-console-empty-results.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("UPDATE $peopleSql SET \"name\" = 'Ada Lovelace' WHERE \"id\" = -999") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("zero affected rows") { console.hasSubtext("0 rows affected ·") }
                captureScreen(screenshotsDirectory.resolve("sql-console-update-zero.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("UPDATE $peopleSql SET \"name\" = 'Ada Lovelace' WHERE \"id\" = $changedId") }
                console.x { byVisibleText("Run") }.waitFound().click()
                waitUntil("SQL update completed") { console.hasSubtext("1 row affected ·") }
                captureScreen(screenshotsDirectory.resolve("sql-console-update.png"))
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM $peopleSql") }
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
                if (postgres || h2) jdbcFixture.pauseResponses()
                stopQuery.click()
                waitUntil("immediate cancellation feedback", timeoutMillis = 2000) { console.hasSubtext("Cancelling query…") }
                println("P2 cancellation feedback after ${(System.nanoTime() - cancellationStart) / 1_000_000} ms")
                check(!runQuery.component.isEnabled() && !stopQuery.component.isEnabled()) { "A cancelling query must block Run and repeated Stop" }
                assertAnimatedBusy(console.x { byAccessibleName("SQL query status") }.waitFound())
                captureScreen(screenshotsDirectory.resolve("sql-console-cancelling.png"), settleMillis = 0)
                if (postgres || h2) jdbcFixture.resumeResponses()
                waitUntil("SQL cancellation completes") { runQuery.component.isEnabled() }
                assertQueryOutcome(console, cancelled = true)
                captureScreen(screenshotsDirectory.resolve("sql-console-cancelled.png"))
                val previousTabs = cast(console.x { byJavaClass("javax.swing.JTabbedPane") }.waitFound().component, LiveTabs::class)
                withContext(OnDispatcher.EDT) { previousTabs.setSelectedIndex(0) }
                check(sqlGrid.rowCount() == 1) { "Cancellation must retain previous results" }
                withContext(OnDispatcher.EDT) { queryText.setText("SELECT * FROM $peopleSql") }
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
                verifyKeyHeaderFlow(frame, databaseTree, peoplePath, tableEditor, console, peopleSql, screenshotsDirectory)
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
                if (downloadedButtonEnabledAfterModeSwitch != null)
                    appendLine("Review observation: downloaded MySQL Download button enabled after JDBC URL / failed test = $downloadedButtonEnabledAfterModeSwitch")
                appendLine("Configured Maven local repository fixture: $mavenRepository")
                appendLine("Project POM default drivers: MySQL 8.4.0, HSQLDB 2.7.2")
                appendLine("Discovered MySQL Connector/J releases: 9.0.0, 8.4.0, 8.0.33")
                appendLine("Discovered HSQLDB releases: 2.7.2, 2.6.1-jdk8, 2.4.1")
                if (!postgres && !h2 && !System.getProperty("ui.skip.remote.downloads", "false").toBoolean())
                    appendLine("Driver download flow: MySQL 5.1.49 downloaded from Maven Central; progress bar displayed and download controls were disabled during transfer")
                if (h2) {
                    appendLine("H2 driver selection: 2.3.232 · BUNDLED; memory/file/server forms inspected")
                    appendLine("H2 TCP fixture: production connection test, schema/table DDL, grid validation/commit/revert, CSV/JSON/SQL exports, query cancellation and session recovery verified")
                }
                if (postgres) {
                    appendLine("PostgreSQL driver selection: 42.7.14 · BUNDLED in the production connection dialog")
                    appendLine("Persisted exports: CSV, JSON and SQL files saved and inspected for the committed value")
                    appendLine("SQL console: SELECT/update/error outcomes, empty/retained-result cancellation and session recovery verified")
                }
                appendLine("IDE display: ${displayBounds.width}x${displayBounds.height}")
                appendLine("IDE frame bounds: $ideFrameBounds")
                appendLine("Production root Swing class: com.segfault03.ideadb.ui.DatabaseMainPanel")
                appendLine("Production root bounds: $rootBounds")
                appendLine("JetBrains child Swing class: com.intellij.ui.components.ActionLink")
                appendLine("JetBrains ActionLink child bounds on Welcome screen: $jetBrainsActionLinkBounds")
                appendLine("IDE JBR release: ${Files.readString(ideHome.resolve("jbr/release")).lineSequence().filter { it.startsWith("JAVA_VERSION=") || it.startsWith("IMPLEMENTOR=") }.joinToString(", ")}")
                appendLine("Test worker JVM: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
                appendLine("Connection flow: ${if (h2) "H2 TCP server / H2 2.3.232" else if (postgres) "PostgreSQL server $postgresPort / pgJDBC 42.7.14" else "HSQLDB in-memory server"}; live test connection succeeded")
                appendLine("Table insert validation: empty row displayed its auto ID and nullable timestamp defaults, marked its missing required name invalid, and kept Commit disabled until the name was supplied")
                appendLine("Table edit validation: unchanged integer stayed clean; invalid integer was highlighted and blocked Commit; restoring the original stayed clean; a valid changed integer committed and reloaded")
                appendLine("Table flow: created LATTICE_PEOPLE through the production dialog, inserted Ada Lovelace, and edited its ID through the production data grid")
                appendLine("Key header regressions: primary, foreign and combined badges/colors in table and aliased SQL results; markers clear after a new query or schema refresh")
                appendLine("Table editor production Swing class: com.segfault03.ideadb.ui.TableDataEditorPanel")
                appendLine("Database grid production Swing class: com.segfault03.ideadb.ui.DatabaseTable")
            }
            Files.writeString(screenshotsDirectory.resolve("runtime-evidence.txt"), evidence)
            println(evidence)
            println("Saved real IDE screenshots to $screenshotsDirectory")
        }
        } finally { jdbcFixture?.close() }
        } finally { downloadFixture?.close() }
    }

    private fun assertDataSourcePicker(dialog: UiComponent) {
        val picker = dialog.x { byJavaClass("com.segfault03.ideadb.dialog.DataSourcePanel") }.waitFound()
        val name = dialog.x(JTextFieldUI::class.java) {
            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Name:"))
        }.waitFound()
        check(picker.component.width <= 180) { "The source picker must stay compact" }
        check(picker.component.getLocationOnScreen().x + picker.component.width < name.component.getLocationOnScreen().x) {
            "The source picker must appear to the left of the connection form"
        }
        for ((source, port) in listOf("PostgreSQL" to "5432", "H2" to "9092", "HSQLDB" to "9001", "MySQL" to "3306")) {
            val choice = dialog.x { byAccessibleName(source) }.waitFound()
            check(requireNotNull(dialog.driver.cast(choice.component, LiveSourceChoice::class).getIcon()).getIconWidth() == 24)
            choice.click()
            check(dialog.driver.cast(choice.component, LiveSourceChoice::class).isSelected())
            if (source == "H2") dialog.x(JComboBoxUiComponent::class.java) {
                and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName("Mode:"))
            }.waitFound().selectItem("Remote Server")
            val field = dialog.x(JTextFieldUI::class.java) {
                and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("Port:"))
            }.waitFound()
            check(field.text == port) { "$source must select its default port $port" }
        }
        println("Compact left source picker: SVGs, source switching, and MySQL/HSQLDB/PostgreSQL/H2 ports verified")
    }

    private fun verifyKeyHeaderFlow(frame: IdeaFrameUI, tree: JTreeUiComponent, peoplePath: Array<String>,
                                    editor: UiComponent, console: UiComponent, peopleSql: String, directory: Path) {
        val query = console.xx { byJavaClass("com.intellij.ui.components.JBTextArea") }.list().first()
        val text = console.driver.cast(query.component, JTextComponent::class)
        val run = console.x { byVisibleText("Run") }.waitFound()
        val stop = console.x { byVisibleText("Stop") }.waitFound()
        fun execute(sql: String) {
            console.driver.withContext(OnDispatcher.EDT) { text.setText(sql) }
            run.click()
            waitUntil("key metadata SQL completed: $sql") { run.component.isEnabled() && !stop.component.isEnabled() }
            check(!console.hasSubtext("Query failed")) { "Key metadata fixture SQL failed: $sql" }
        }
        tree.fixture.rightClickPath(peoplePath.joinToString(tree.fixture.separator()))
        frame.x { byVisibleText("Open data editor") }.waitFound().click()
        val revert = editor.x { byAccessibleName("Revert pending changes") }.waitFound()
        if (revert.component.isEnabled()) revert.click()
        tree.fixture.rightClickPath(peoplePath.joinToString(tree.fixture.separator()))
        frame.x { byVisibleText("Open in SQL console") }.waitFound().click()
        val parent = peopleSql.substringBeforeLast('.') + ".\"UI_KEY_PARENT\""
        execute("CREATE TABLE $parent (\"id\" INTEGER PRIMARY KEY)")
        execute("INSERT INTO $parent SELECT \"id\" FROM $peopleSql")
        execute("ALTER TABLE $peopleSql ADD COLUMN \"parent_id\" INTEGER")
        execute("ALTER TABLE $peopleSql ADD CONSTRAINT \"UI_KEY_ID_FK\" FOREIGN KEY (\"id\") REFERENCES $parent (\"id\")")
        execute("ALTER TABLE $peopleSql ADD CONSTRAINT \"UI_KEY_PARENT_FK\" FOREIGN KEY (\"parent_id\") REFERENCES $parent (\"id\")")
        execute("SELECT \"id\" AS \"key\", \"parent_id\" AS \"key\", \"name\" AS \"label\", \"id\" + 1 AS \"computed\" FROM $peopleSql")
        val results = console.x(JTableUiComponent::class.java) { byJavaClass("com.segfault03.ideadb.ui.DatabaseTable") }.waitFound()
        waitUntil("aliased key query columns loaded") { console.driver.cast(results.component, LiveTableEditor::class).getColumnModel().getColumnCount() == 4 }
        assertKeyHeaders(results, setOf(0), setOf(0, 1), "SQL aliases and dual keys")
        captureScreen(directory.resolve("sql-key-headers.png"))
        execute("SELECT \"name\" FROM $peopleSql")
        waitUntil("ordinary query replaces key columns") { console.driver.cast(results.component, LiveTableEditor::class).getColumnModel().getColumnCount() == 1 }
        assertKeyHeaders(results, emptySet(), emptySet(), "SQL key markers cleared on later query")
        tree.fixture.rightClickPath(peoplePath.joinToString(tree.fixture.separator()))
        frame.x { byVisibleText("Open data editor") }.waitFound().click()
        editor.x { byAccessibleName("Refresh table data") }.waitFound().click()
        val grid = editor.x(JTableUiComponent::class.java) { byJavaClass("com.segfault03.ideadb.ui.DatabaseTable") }.waitFound()
        val live = editor.driver.cast(grid.component, LiveTableEditor::class)
        waitUntil("foreign-key table metadata reloaded") {
            val model = live.getModel()
            (0 until model.getColumnCount()).any { model.getColumnName(it).startsWith("parent_id") }
        }
        val foreignColumn = (0 until live.getModel().getColumnCount()).first { live.getModel().getColumnName(it).startsWith("parent_id") }
        assertKeyHeaders(grid, setOf(0), setOf(0, foreignColumn), "Table metadata and dual keys")
        captureScreen(directory.resolve("table-key-headers.png"))
        tree.fixture.rightClickPath(peoplePath.joinToString(tree.fixture.separator()))
        frame.x { byVisibleText("Open in SQL console") }.waitFound().click()
        execute("ALTER TABLE $peopleSql DROP CONSTRAINT \"UI_KEY_ID_FK\"")
        execute("ALTER TABLE $peopleSql DROP CONSTRAINT \"UI_KEY_PARENT_FK\"")
        execute("ALTER TABLE $peopleSql DROP COLUMN \"parent_id\"")
        execute("DROP TABLE $parent")
        tree.fixture.rightClickPath(peoplePath.joinToString(tree.fixture.separator()))
        frame.x { byVisibleText("Open data editor") }.waitFound().click()
        editor.x { byAccessibleName("Refresh table data") }.waitFound().click()
        waitUntil("removed foreign-key column leaves the grid") { live.getModel().getColumnCount() == foreignColumn }
        assertKeyHeaders(grid, setOf(0), emptySet(), "Table foreign-key markers cleared after schema refresh")
        println("Live table and SQL key metadata regression passed")
    }

    private fun assertKeyHeaders(grid: UiComponent, primary: Set<Int>, foreign: Set<Int>, description: String) {
        val expected = mutableListOf<Int>()
        val samples = mutableListOf<Point>()
        grid.driver.withContext(OnDispatcher.EDT) {
            val table = grid.driver.cast(grid.component, LiveTableEditor::class)
            val header = table.getTableHeader()
            val renderer = header.getDefaultRenderer()
            val columns = table.getColumnModel()
            val point = header.getLocationOnScreen()
            val visible = header.getVisibleRect()
            var offset = 0
            for (index in 0 until columns.getColumnCount()) {
                val column = columns.getColumn(index)
                val painted = renderer.getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, index)
                val icon = grid.driver.cast(painted, LiveLabel::class).getIcon()
                check((icon != null) == (index in primary || index in foreign))
                if (index in primary && index in foreign) check(requireNotNull(icon).getIconWidth() >= 32)
                expected.add(painted.getBackground().getRGB())
                if (visible.contains(offset + 5, 3)) samples.add(Point(point.x + offset + 5, point.y + 3))
                else samples.add(Point(-1, -1))
                offset += column.getWidth()
            }
        }
        for (p in primary) for (f in foreign - primary) check(expected[p] != expected[f]) { "$description: primary and foreign key tints differ" }
        for (key in primary + foreign) for (plain in expected.indices.filter { it !in primary && it !in foreign }) {
            check(expected[key] != expected[plain]) { "$description: key and ordinary header tints differ" }
        }
        val robot = Robot()
        robot.mouseMove(10, 10) // Keep toolbar tooltips away from the sampled header pixels.
        repaintIdeBeforeCapture?.invoke()
        for ((index, point) in samples.withIndex()) if (point.x >= 0) check(robot.getPixelColor(point.x, point.y).rgb == expected[index]) {
            "$description: live painted header $index differs from its renderer"
        }
        println("Key header pixels and badges verified: $description")
    }

    private fun showIdeNotificationOverlay(frame: IdeaFrameUI, status: UiComponent): LiveBalloon {
        val text = "IDE notification overlay regression"
        lateinit var content: LiveSwingComponent
        val balloon = frame.driver.withContext(OnDispatcher.EDT) {
            val label = new(LiveLabel::class, text)
            check(label.getText() == text)
            content = cast(label, LiveSwingComponent::class)
            content.setPreferredSize(new(LiveDimension::class, 350, 80))
            content.setSize(350, 80)
            val balloon = utility(LivePopupFactory::class).getInstance()
                .createBalloonBuilder(content)
                .setAnimationCycle(0).setHideOnClickOutside(false).setHideOnKeyOutside(false)
                .setHideOnFrameResize(false).setHideOnAction(false)
                .createBalloon()
            requireNotNull(cast(frame.component, LiveIdeFrame::class).getBalloonLayout()).add(balloon)
            val owner = cast(frame.component, LiveFrame::class).getRootPane().getLocationOnScreen()
            val footer = status.component.getLocationOnScreen()
            balloon.setBounds(Rectangle(footer.x - owner.x - 20, footer.y - owner.y - 35, 320, 90))
            balloon
        }
        frame.driver.withContext(OnDispatcher.EDT) {
            val root = cast(frame.component, LiveFrame::class).getRootPane()
            root.validate()
            root.paintImmediately(0, 0, frame.component.width, frame.component.height)
        }
        waitUntil("native IDE notification overlay is laid out and showing") {
            content.isShowing() && content.getVisibleRect().let { it.width > 0 && it.height > 0 }
        }
        println("Native IDE balloon content: ${content.getLocationOnScreen()} ${content.getVisibleRect()}; disposed=${balloon.isDisposed()}")
        check(!balloon.isDisposed())
        return balloon
    }

    private fun captureBusyState(fixture: JdbcUiFixture, root: UiComponent, message: String, filename: String,
                                 directory: Path, beforeCapture: (() -> Unit)? = null, action: () -> Unit) {
        fixture.pauseResponses()
        try {
            action()
            waitUntil("visible busy feedback: $message") { root.hasSubtext(message) }
            fixture.awaitBlockedResponse()
            // IDEA's optional plugin recommendation can cover the status bar.
            root.driver.ui.xx { byVisibleText("Don't suggest again") }.list()
                .filter { it.component.isShowing() }.forEach { it.click() }
            beforeCapture?.invoke()
            // A tree renderer also exposes the loading text. Inspect the persistent
            // status component, whose screen location and repaint lifecycle are real.
            val label = root.xx { byAccessibleName("Database explorer status") }.list().firstOrNull()
                ?: root.x { byVisibleText(message) }.waitFound()
            assertAnimatedBusy(label)
            // Confirm that the actual icon animates on screen, rather than merely having a spinner class.
            val robot = Robot()
            robot.mouseMove(10, 10) // A tooltip must not cover the status component.
            // Closing a modal can leave the native tool-window footer unpainted under Xvfb.
            // Prime it once, then require subsequent frames to repaint naturally.
            repaintIdeBeforeCapture?.invoke()
            Thread.sleep(350) // Let dismissed notification balloons finish fading before the baseline.
            // Revalidation after a dialog closes can move the footer. Read its live
            // geometry after painting and restart the baseline whenever it moves.
            fun liveRegion(): Rectangle = root.driver.withContext(OnDispatcher.EDT) {
                val live = cast(label.component, LiveSwingComponent::class)
                check(live.isShowing()) { "Busy status must be showing: $message" }
                val visible = live.getVisibleRect()
                val point = label.component.getLocationOnScreen()
                Rectangle(point.x + visible.x, point.y + visible.y, minOf(40, visible.width), visible.height).also {
                    check(it.width > 0 && it.height > 0 && Rectangle(Toolkit.getDefaultToolkit().screenSize).contains(it)) {
                        "Busy status must be inside the live display: $message; region=$it"
                    }
                }
            }
            var region = liveRegion()
            var first = robot.createScreenCapture(region)
            var last = first
            val started = System.nanoTime()
            val deadline = started + 5_000_000_000L
            var changed = false
            while (!changed && System.nanoTime() < deadline) {
                Thread.sleep(83) // Avoid sampling on the spinner's frame cadence.
                val current = liveRegion()
                if (current != region) {
                    println("Busy status moved during layout: $message; $region -> $current")
                    region = current
                    first = robot.createScreenCapture(region)
                    continue
                }
                last = robot.createScreenCapture(region)
                changed = (0 until first.width).any { x ->
                    (0 until first.height).any { y -> first.getRGB(x, y) != last.getRGB(x, y) }
                }
            }
            if (!changed) {
                javax.imageio.ImageIO.write(first, "png", directory.resolve("$filename-spinner-first.png").toFile())
                javax.imageio.ImageIO.write(last, "png", directory.resolve("$filename-spinner-last.png").toFile())
                captureScreen(directory.resolve("$filename-spinner-failure.png"))
                // Keep the tiny native samples in the job log as well as the artifact,
                // so a failed capture can be diagnosed when artifact downloads fail.
                for ((name, sample) in listOf("first" to first, "last" to last)) {
                    val bytes = java.io.ByteArrayOutputStream()
                    javax.imageio.ImageIO.write(sample, "png", bytes)
                    println("Spinner $name PNG ($message; region=$region): ${java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())}")
                }
            }
            check(changed) { "Busy spinner did not animate on the live display within 5 seconds: $message; region=$region" }
            println("Animated spinner pixels changed on the live IDE display: $message after ${(System.nanoTime() - started) / 1_000_000} ms")
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

    private fun assertTableDialogText(dialog: UiComponent) {
        val labels = listOf("Table dialog title", "Table database engine", "Table SQL preview title")
            .map { dialog.x { byAccessibleName(it) }.waitFound() }
        val actions = dialog.xx { or(byJavaClass("com.segfault03.ideadb.ui.DatabaseUi\$ActionButton"), byJavaClass("com.segfault03.ideadb.ui.DatabaseUi\$DialogButton")) }.list()
        val footer = dialog.x { byAccessibleName("Table dialog actions") }.waitFound()
        val footerButtons = footer.xx { or(byJavaClass("javax.swing.JButton"), byJavaClass("com.segfault03.ideadb.ui.DatabaseUi\$DialogButton")) }.list()
        check(footerButtons.size == 2)
        val sourceIcon = dialog.x { byAccessibleName("Table database icon") }.waitFound()
        val sourceText = dialog.x { byAccessibleName("Table database engine") }.waitFound()
        check(actions.isNotEmpty())
        dialog.driver.withContext(OnDispatcher.EDT) {
            check(utility(LiveUiScale::class).scale(100f) == (System.getProperty("ui.scale", "1.0").toFloat() * 100)) { "IDE did not apply the requested UI scale" }
            val a=footerButtons[0].component.getBounds(); val b=footerButtons[1].component.getBounds()
            check(a.height==b.height && a.y==b.y) { "Footer buttons must share top/bottom edges: $a, $b" }
            val first=cast(footerButtons[0].component,LiveSwingComponent::class)
            val second=cast(footerButtons[1].component,LiveSwingComponent::class)
            check(first.getBaseline(a.width,a.height)==second.getBaseline(b.width,b.height)) { "Footer text baselines differ" }
            check(b.x-a.x-a.width==utility(LiveUiScale::class).scale(8f).toInt()) { "Footer action gap differs from 8 logical pixels" }
            val glyph=sourceIcon.component.getLocationOnScreen(); val caption=sourceText.component.getLocationOnScreen()
            check(caption.x>=glyph.x+sourceIcon.component.width+utility(LiveUiScale::class).scale(8f).toInt()) { "Source text overlaps its icon slot" }
            println("Table dialog geometry: equal footer heights=${a.height}, aligned baselines; separate source icon/text")
            for (label in labels) {
                val text = cast(label.component, LiveLabel::class)
                val live = cast(label.component, LiveSwingComponent::class)
                val available = new(LiveDimension::class, label.component.width, label.component.height)
                utility(LiveInsetsMath::class).removeFrom(available, live.getInsets())
                val icon = text.getIcon()
                val needed = live.getFontMetrics(live.getFont()).stringWidth(text.getText()) +
                    (icon?.getIconWidth() ?: 0) + (if (icon != null) text.getIconTextGap() else 0)
                check(available.getWidth() >= needed) { "Clipped label: ${text.getText()} (${label.component.width} < $needed)" }
            }
            for (action in actions) {
                val button = cast(action.component, LiveToolbarButton::class)
                val live = cast(action.component, LiveSwingComponent::class)
                if (!live.isShowing() || button.getText().isEmpty()) continue
                val available = new(LiveDimension::class, action.component.width, action.component.height)
                utility(LiveInsetsMath::class).removeFrom(available, live.getInsets())
                utility(LiveInsetsMath::class).removeFrom(available, button.getMargin())
                val icon = button.getIcon()
                val needed = live.getFontMetrics(live.getFont()).stringWidth(button.getText()) +
                    (icon?.getIconWidth() ?: 0) + (if (icon != null) button.getIconTextGap() else 0)
                check(available.getWidth() >= needed) { "Clipped action: ${button.getText()} (${action.component.width} < $needed)" }
                if (button.getText() in listOf("Drop column", "Restore column", "Remove column", "Rename", "Revert")) {
                    val nativeSize=button.getUI().getPreferredSize(live)
                    check(action.component.width>=nativeSize.getWidth() && action.component.height>=nativeSize.getHeight()) { "Inspector action bypassed native text padding: ${button.getText()}" }
                }
                if (button.getText() in listOf("Apply changes", "Apply all changes", "Create table")) {
                    check(!live.isOpaque()) { "Primary button must not paint a rectangle outside its native rounded surface" }
                    val nativeSize = button.getUI().getPreferredSize(live)
                    check(action.component.width >= nativeSize.getWidth()) { "Footer button bypassed the native UI's preferred width" }
                    check(action.component.height >= nativeSize.getHeight()) { "Footer button bypassed the native UI's preferred height" }
                    println("Native footer sizing: ${button.getText()}, enabled=${action.component.isEnabled()}, default=${button.isDefaultButton()}, actual=${action.component.width}x${action.component.height}, preferred=${nativeSize.getWidth()}x${nativeSize.getHeight()}")
                }
            }
        }
        println("Table dialog: complete title, source, SQL caption and action labels fit at UI scale ${System.getProperty("ui.scale", "1.0")}")
    }

    private fun assertInspectorViewport(dialog: UiComponent) {
        val inspector=dialog.x { byAccessibleName("Table column inspector scroll pane") }.waitFound()
        dialog.driver.withContext(OnDispatcher.EDT) {
            val scroll=cast(inspector.component,LiveInspectorScroll::class)
            val viewport=scroll.getViewport()
            val minimum=viewport.getView().getMinimumSize().getHeight()
            val available=viewport.getExtentSize().getHeight()
            check(!scroll.getVerticalScrollBar().isVisible()) { "Unnecessary inspector scrollbar: readable minimum=$minimum, viewport=$available" }
        }
    }

    private fun auditTableDialog(dialog: UiComponent, directory: Path, prefix: String, create: Boolean) {
        if (!System.getProperty("ui.dialog.audit", "false").toBoolean()) return
        val panel=dialog.x { byJavaClass(if(create) "com.segfault03.ideadb.dialog.CreateTablePanel" else "com.segfault03.ideadb.dialog.AlterTablePanel") }.waitFound()
        val original=dialog.component.getBounds()
        val overhead=original.width-panel.component.width
        val scale=System.getProperty("ui.scale", "1.0").toDouble()
        val laf=dialog.driver.service(LiveLafManager::class)
        val theme=laf.getCurrentUIThemeLookAndFeel().getId()
        val themes=mutableListOf("ExperimentalLight","ExperimentalDark")
        val installed=laf.getInstalledLookAndFeels().map { it.getName() }
        for(id in listOf("Islands Light","Islands Dark")) if(id in installed)themes.add(id)
        try {
            for (id in themes) {
                dialog.driver.withContext(OnDispatcher.EDT) { laf.setCurrentLookAndFeel(laf.findLaf(id),true);laf.updateUI() }
                check(laf.getCurrentUIThemeLookAndFeel().getId()==id) { "Dialog audit rendered an unexpected theme" }
                for (width in listOf(if(create)840 else 780,if(create)920 else 900,1200)) {
                    val target=(width*scale).toInt()+overhead
                    dialog.driver.withContext(OnDispatcher.EDT) {
                        cast(dialog.component,Window::class).setBounds(20,20,target,original.height)
                    }
                    waitUntil("dialog audit resize") { panel.component.width >= (width*scale).toInt()-1 }
                    Thread.sleep(200)
                    val columns=dialog.x { byAccessibleName("Table columns") }.waitFound()
                    dialog.driver.withContext(OnDispatcher.EDT) { cast(columns.component,LiveColumnList::class).ensureIndexIsVisible(0) }
                    assertTableDialogText(dialog)
                    assertInspectorViewport(dialog)
                    val sql=dialog.x { byAccessibleName(if(create) "Create table SQL preview" else "Alter table SQL preview") }.waitFound()
                    dialog.driver.withContext(OnDispatcher.EDT) {
                        val pane=cast(sql.component,LiveStyledSql::class)
                        val identifier=pane.getText().indexOf('"')
                        check(identifier>=0)
                        val attrs=pane.getStyledDocument().getCharacterElement(identifier).getAttributes()
                        val actual=utility(LiveTextStyle::class).getForeground(attrs).getRGB()
                        val expected=utility(LiveUiColors::class).getColor("Label.foreground").getRGB()
                        check(actual==expected) { "SQL kept a foreground from another theme" }
                    }
                    val name=inspectorInput(dialog,"Column name")
                    val heading=dialog.x { byAccessibleName("Selected column heading") }.waitFound()
                    check(name.component.getLocationOnScreen().x==heading.component.getLocationOnScreen().x) { "Inspector title and fields are misaligned" }
                    dialog.driver.withContext(OnDispatcher.EDT) { cast(name.component,LiveSwingComponent::class).requestFocusInWindow() }
                    Robot().mouseMove(2,2);Thread.sleep(120)
                    val point=dialog.component.getLocationOnScreen()
                    savePng(directory.resolve("$prefix-$id-$width.png"),Robot().createScreenCapture(Rectangle(point.x,point.y,dialog.component.width,dialog.component.height)))
                    dialog.driver.withContext(OnDispatcher.EDT) {
                        cast(dialog.component,Window::class).setBounds(20,20,target,original.height-(8*scale).toInt())
                    }
                    Thread.sleep(180)
                    assertInspectorViewport(dialog)
                    savePng(directory.resolve("$prefix-$id-$width-compact.png"),Robot().createScreenCapture(Rectangle(point.x,point.y,dialog.component.width,dialog.component.height)))
                }
            }
        } finally {
            dialog.driver.withContext(OnDispatcher.EDT) {
                laf.setCurrentLookAndFeel(laf.findLaf(theme),true);laf.updateUI()
                cast(dialog.component,Window::class).setBounds(original.x,original.y,original.width,original.height)
            }
            Thread.sleep(200)
        }
        println("Table dialog audit: $themes at minimum, preferred and wide widths passed")
    }

    private fun assertCreateTableLayout(dialog: UiComponent) {
        val composer = dialog.x { byJavaClass("com.segfault03.ideadb.dialog.CreateTablePanel") }.waitFound()
        check(composer.component.width >= 840 && composer.component.height >= 620)
        assertTableDialogText(dialog)
        for (name in listOf("Remove column", "Move column up", "Move column down")) dialog.x { byAccessibleName(name) }.waitFound()
        val preview = dialog.x { byAccessibleName("Create table SQL preview") }.waitFound()
        val fields = composer.xx { byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField") }.list()
        dialog.driver.withContext(OnDispatcher.EDT) {
            val text = cast(preview.component, LiveTextComponent::class)
            check(text.getText().startsWith("CREATE TABLE") && text.getCaretPosition() == 0)
            val livePreview = cast(preview.component, LiveSwingComponent::class)
            check(livePreview.getVisibleRect().y == 0)
            check(livePreview.getVisibleRect().height >= livePreview.getPreferredSize().getHeight()) { "The small CREATE statement must fit the SQL preview" }
            for (field in fields) {
                val live = cast(field.component, LiveSwingComponent::class)
                if (!live.isShowing()) continue
                check(field.component.height in (24 * System.getProperty("ui.scale", "1.0").toDouble()).toInt()..(34 * System.getProperty("ui.scale", "1.0").toDouble()).toInt()) { "Composer input lost its compact height" }
                check(live.getVisibleRect().width == field.component.width) { "Composer input is clipped horizontally" }
            }
        }
        println("Column Composer: compact inputs fit; SQL preview starts at CREATE TABLE")
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

    private fun twoRowFilterWindowWidth(frame: IdeaFrameUI, editor: UiComponent): Int {
        // Clear adds to the action group; font/theme metrics vary by OS.
        val filters = editor.x { byJavaClass("com.segfault03.ideadb.ui.TableFilterPanel") }.waitFound()
        val where = editor.x(JTextFieldUI::class.java) {
            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("WHERE"))
        }.waitFound()
        val order = editor.x(JTextFieldUI::class.java) {
            and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName("ORDER BY"))
        }.waitFound()
        val apply = editor.x { byAccessibleName("Apply filter and sort") }.waitFound()
        return editor.driver.withContext(OnDispatcher.EDT) {
            val whereGroup = cast(cast(where.component, LiveSwingComponent::class).getParent(), LiveSwingComponent::class)
            val orderGroup = cast(cast(order.component, LiveSwingComponent::class).getParent(), LiveSwingComponent::class)
            val actions = cast(cast(apply.component, LiveSwingComponent::class).getParent(), LiveSwingComponent::class)
            // The filter panel has symmetric horizontal padding. Its group's X
            // position measures that padding without crossing Driver's Insets API.
            val targetWidth = orderGroup.getMinimumSize().getWidth().toInt() +
                actions.getPreferredSize().getWidth().toInt() + 8 +
                whereGroup.getMinimumSize().getWidth().toInt() / 2 + 2 * whereGroup.getX()
            frame.component.width - filters.component.width + targetWidth
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
        val clear = editor.x { byAccessibleName("Clear WHERE and ORDER BY clauses") }.waitFound().component
        fun center(component: com.intellij.driver.sdk.ui.remote.Component) = component.getLocationOnScreen().y + component.height / 2
        val first = center(where)
        val second = center(order)
        val action = center(apply)
        check(center(clear) == action) { "Apply and Clear must stay on the same row" }
        check(clear.getLocationOnScreen().x >= apply.getLocationOnScreen().x + apply.width) {
            "Apply and Clear must not overlap"
        }
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

    private fun inspectorInput(dialog: UiComponent, name: String): JTextFieldUI = dialog.x(JTextFieldUI::class.java) {
        and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField"), byAccessibleName(name))
    }.waitFound()

    private fun selectInspectorColumn(dialog: UiComponent, index: Int) {
        dialog.x(JListUiComponent::class.java) { byAccessibleName("Table columns") }.waitFound().clickItemAtIndex(index)
    }

    private fun assertInspectorLayout(dialog: UiComponent) {
        val inspector = dialog.x { byJavaClass("com.segfault03.ideadb.dialog.AlterTablePanel") }.waitFound()
        val bounds = inspector.component.getBounds()
        check(bounds.width >= 780 && bounds.height >= 570)
        assertTableDialogText(dialog)
        val fields = inspector.xx { byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputTextField") }.list()
        dialog.driver.withContext(OnDispatcher.EDT) {
            for (field in fields) {
                val live = cast(field.component, LiveSwingComponent::class)
                if (!live.isShowing()) continue
                check(field.component.height in (24 * System.getProperty("ui.scale", "1.0").toDouble()).toInt()..(34 * System.getProperty("ui.scale", "1.0").toDouble()).toInt()) { "Inspector input lost its shared compact height" }
                val visible = live.getVisibleRect()
                check(visible.width == field.component.width) { "Inspector field is clipped horizontally" }
            }
        }
    }

    private fun verifyCreateComposer(frame: IdeaFrameUI, root: UiComponent, fixture: JdbcUiFixture, directory: Path) {
        openConnectionMenu(root, frame, "HSQLDB…")
        frame.x(DialogUiComponent::class.java) { byTitle("New connection") }.waitFound().also { dialog ->
            inspectorInput(dialog, "Name:").text = "Local development"
            dialog.x { byVisibleText("JDBC URL") }.waitFound().click()
            inspectorInput(dialog, "JDBC URL:").text = fixture.jdbcUrl
            inspectorInput(dialog, "User:").text = "SA"
            waitUntil("Composer fixture connection ready") { dialog.x { byVisibleText("Add connection") }.waitFound().component.isEnabled() }
            dialog.pressButton("Add connection")
        }
        frame.waitForNoOpenedDialogs()
        val tree = root.x(JTreeUiComponent::class.java) { byJavaClass("com.intellij.ui.treeStructure.Tree") }.waitFound()
        val separator = tree.fixture.separator()
        val schema = arrayOf("Data Sources", "HSQLDB", "Local development", "PUBLIC")
        tree.fixture.expandPath(schema.take(3).joinToString(separator))
        waitUntil("Composer schema connected") { tree.pathExists(*schema) }
        tree.fixture.expandPath(schema.joinToString(separator))
        fun open(): DialogUiComponent {
            tree.fixture.rightClickPath(schema.joinToString(separator))
            frame.x { byVisibleText("Create table…") }.waitFound().click()
            return frame.x(DialogUiComponent::class.java) { byTitle("Create table in PUBLIC") }.waitFound()
        }
        fun select(dialog: UiComponent, name: String) {
            val list = dialog.x(JListUiComponent::class.java) { byAccessibleName("Table columns") }.waitFound()
            val index = list.rawItems.indexOfFirst { it.substringBefore(" : ") == name }
            check(index >= 0) { "Composer column $name is missing: ${list.rawItems}" }; list.clickItemAtIndex(index)
        }
        fun add(dialog: UiComponent, name: String) {
            dialog.x { byAccessibleName("Add column") }.waitFound().click(); inspectorInput(dialog,"Column name").text = name
        }
        fun ready(dialog: UiComponent) = waitUntil("Composer ready") { dialog.x { byAccessibleName("Create new table") }.waitFound().component.isEnabled() }
        fun columns(table: String): List<Pair<String,Int>> = java.sql.DriverManager.getConnection(fixture.jdbcUrl,"SA","").use { conn ->
            conn.metaData.getColumns(null,"PUBLIC",table,"%").use { rows -> buildList { while(rows.next())add(rows.getString("COLUMN_NAME") to rows.getInt("COLUMN_SIZE")) } }
        }
        var dialog = open(); inspectorInput(dialog,"Table name").text = "customers"
        select(dialog,"name"); inspectorInput(dialog,"Length / precision").text = "120"
        select(dialog,"created_at"); dialog.x { byAccessibleName("Allow NULL values") }.waitFound().click()
        inspectorInput(dialog,"Default value").text = "SQL:CURRENT_TIMESTAMP"
        add(dialog,"email"); inspectorInput(dialog,"Length / precision").text = "oops"
        select(dialog,"name"); check(!dialog.x { byAccessibleName("Create new table") }.waitFound().component.isEnabled())
        select(dialog,"email"); check(inspectorInput(dialog,"Length / precision").text == "oops")
        inspectorInput(dialog,"Length / precision").text = "255"; dialog.x { byAccessibleName("Allow NULL values") }.waitFound().click()
        dialog.x { byAccessibleName("Move column up") }.waitFound().click()
        inspectorInput(dialog,"Filter columns").text = "EMAIL"; inspectorInput(dialog,"Filter columns").text = ""
        check(inspectorInput(dialog,"Length / precision").text == "255")
        add(dialog,"status"); inspectorInput(dialog,"Length / precision").text = "20"; inspectorInput(dialog,"Default value").text = "TEXT:active"
        dialog.x { byAccessibleName("Allow NULL values") }.waitFound().click(); dialog.x { byAccessibleName("Move column up") }.waitFound().click()
        add(dialog,"balance")
        selectComboItemWithKeyboard(dialog.x(JComboBoxUiComponent::class.java) { and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"),byAccessibleName("Data type")) }.waitFound(),"DECIMAL")
        inspectorInput(dialog,"Length / precision").text = "10, 2"; inspectorInput(dialog,"Default value").text = "0"
        dialog.x { byAccessibleName("Move column up") }.waitFound().click()
        inspectorInput(dialog,"Column name").text = "email"; check(!dialog.x { byAccessibleName("Create new table") }.waitFound().component.isEnabled())
        inspectorInput(dialog,"Column name").text = "balance"
        add(dialog,"temporary"); dialog.x { byAccessibleName("Remove column") }.waitFound().click()
        select(dialog,"email"); ready(dialog); assertCreateTableLayout(dialog)
        dialog.pressButton("Cancel")
        frame.x(DialogUiComponent::class.java) { byTitle("Discard changes") }.waitFound().pressButton("Keep editing")
        check(columns("customers").isEmpty()); check(inspectorInput(dialog,"Column name").text == "email")
        Robot().mouseMove(10,10); repaintIdeBeforeCapture?.invoke(); Thread.sleep(350)
        auditTableDialog(dialog,directory,"create-composer",true)
        captureScreen(directory.resolve("create-composer-full-ide.png"))
        val point = dialog.component.getLocationOnScreen()
        savePng(directory.resolve("create-composer-dialog.png"),Robot().createScreenCapture(Rectangle(point.x,point.y,dialog.component.width,dialog.component.height)))
        captureBusyState(fixture,dialog,"Creating table…","create-composer-busy.png",directory) {
            dialog.x { byAccessibleName("Create new table") }.waitFound().click()
        }
        frame.x(DialogUiComponent::class.java) { byTitle("Table created") }.waitFound().pressButton("OK"); frame.waitForNoOpenedDialogs()
        check(columns("customers").map { it.first } == listOf("id","name","email","status","balance","created_at"))
        check(columns("customers")[1].second == 120 && columns("customers")[2].second == 255)
        java.sql.DriverManager.getConnection(fixture.jdbcUrl,"SA","").use { conn ->
            conn.metaData.getColumns(null,"PUBLIC","customers","id").use { rows -> check(rows.next() && rows.getString("IS_AUTOINCREMENT") == "YES") }
            conn.metaData.getPrimaryKeys(null,"PUBLIC","customers").use { rows -> check(rows.next() && rows.getString("COLUMN_NAME") == "id") }
            conn.metaData.getColumns(null,"PUBLIC","customers","balance").use { rows -> check(rows.next() && rows.getInt("COLUMN_SIZE") == 10 && rows.getInt("DECIMAL_DIGITS") == 2) }
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO \"PUBLIC\".\"customers\" (\"name\",\"email\") VALUES('Ada Lovelace','ada@example.com')")
                stmt.executeQuery("SELECT \"id\",\"status\",\"balance\",\"created_at\" FROM \"PUBLIC\".\"customers\"").use { rows -> check(rows.next() && rows.getObject(1) != null && rows.getString(2) == "active" && rows.getBigDecimal(3).toPlainString() == "0.00" && rows.getTimestamp(4) != null) }
            }
        }
        waitUntil("created table appears") { tree.pathExists(*(schema + arrayOf("Tables (1)","customers"))) }
        dialog = open(); inspectorInput(dialog,"Table name").text = "customers"; add(dialog,"nickname"); inspectorInput(dialog,"Length / precision").text = "64"
        ready(dialog); dialog.x { byAccessibleName("Create new table") }.waitFound().click()
        frame.x(DialogUiComponent::class.java) { byTitle("Create table failed") }.waitFound().also { captureScreen(directory.resolve("create-composer-error.png")); it.pressButton("OK") }
        check(inspectorInput(dialog,"Column name").text == "nickname" && inspectorInput(dialog,"Length / precision").text == "64")
        inspectorInput(dialog,"Table name").text = "customers_archive"; ready(dialog)
        dialog.x { byAccessibleName("Create new table") }.waitFound().click()
        frame.x(DialogUiComponent::class.java) { byTitle("Table created") }.waitFound().pressButton("OK"); frame.waitForNoOpenedDialogs()
        check(columns("customers_archive").size == 4 && columns("customers_archive").last() == ("nickname" to 64))
        waitUntil("archive appears") { tree.pathExists(*(schema + arrayOf("Tables (2)","customers_archive"))) }
        dialog = open(); inspectorInput(dialog,"Table name").text = "discarded_table"; dialog.pressButton("Cancel")
        frame.x(DialogUiComponent::class.java) { byTitle("Discard changes") }.waitFound().pressButton("Discard"); frame.waitForNoOpenedDialogs()
        check(columns("discarded_table").isEmpty())
        verifyMutationRefresh(frame, tree, schema, directory)
        println("Column Composer: real creation, order, keys, identity, precision, defaults, hidden drafts, validation, cancellation, busy animation and retry verified in the running IDE")
    }

    private fun verifyMutationRefresh(frame: IdeaFrameUI, tree: JTreeUiComponent, schema: Array<String>, directory: Path) {
        val tablePath = schema + arrayOf("Tables (2)", "customers")
        val explorerStatus = frame.driver.cast(frame.x { byAccessibleName("Database explorer status") }.waitFound().component, LiveLabel::class)
        fun menu(label: String) {
            // pathExists uses substring matching: customers also matches customers_archive.
            waitUntil("refresh target present and metadata read finished") {
                !explorerStatus.getText().startsWith("Loading") && tree.component.isEnabled() &&
                    tree.collectExpandedPaths().any { it.path == tablePath.toList() }
            }
            val row = tree.collectExpandedPaths().single { it.path == tablePath.toList() }.row
            tree.fixture.rightClickRow(row)
            frame.x { byVisibleText(label) }.waitFound().click()
        }
        menu("Open data editor")
        val editor = frame.x { byJavaClass("com.segfault03.ideadb.ui.TableDataEditorPanel") }.waitFound()
        val grid = editor.x(JTableUiComponent::class.java) { byJavaClass("com.segfault03.ideadb.ui.DatabaseTable") }.waitFound()
        fun values() = grid.content().values.flatMap { it.values }
        val model = editor.driver.cast(grid.component, LiveTableEditor::class).getModel()
        val status = editor.driver.cast(editor.x { byAccessibleName("Table operation status") }.waitFound().component, LiveLabel::class)
        fun columnNames() = (0 until model.getColumnCount()).map { model.getColumnName(it) }
        waitUntil("refresh fixture grid loaded") { grid.rowCount() == 1 && editor.hasSubtext("1 row ·") }
        val commit = editor.x { byAccessibleName("Commit pending changes to the database") }.waitFound()
        val revert = editor.x { byAccessibleName("Revert pending changes") }.waitFound()

        replaceCellValue(grid, 0, 1, "Grid update")
        commit.click()
        frame.x(DialogUiComponent::class.java) { byTitle("Changes Saved") }.waitFound().pressButton("OK")
        waitUntil("grid update forces reload") { "Grid update" in values() && editor.hasSubtext("1 row ·") }
        editor.x { byAccessibleName("Add a new row") }.waitFound().click()
        replaceCellValue(grid, 1, 1, "Grid insert")
        replaceCellValue(grid, 1, 2, "insert@example.com")
        commit.click()
        frame.x(DialogUiComponent::class.java) { byTitle("Changes Saved") }.waitFound().pressButton("OK")
        waitUntil("grid insert forces reload including identity") { grid.rowCount() == 2 && editor.hasSubtext("2 rows ·") && "(Auto)" !in values() }
        grid.clickCell(1, 1)
        editor.x { byAccessibleName("Delete selected rows") }.waitFound().click()
        frame.x(DialogUiComponent::class.java) { byTitle("Delete rows") }.waitFound().pressButton("Delete rows")
        waitUntil("grid delete forces a server reload") { grid.rowCount() == 1 && editor.hasSubtext("1 row ·") }

        menu("Alter table…")
        frame.x(DialogUiComponent::class.java) { byTitle("Edit table: customers") }.waitFound().also { dialog ->
            dialog.x { byAccessibleName("Add column") }.waitFound().click()
            inspectorInput(dialog, "Column name").text = "refresh_note"
            waitUntil("refresh DDL ready") { dialog.x { byAccessibleName("Apply table changes") }.waitFound().component.isEnabled() }
            if (System.getProperty("ui.dialog.audit", "false").toBoolean()) {
                selectInspectorColumn(dialog,0)
                waitUntil("existing column audit ready") { dialog.x { byAccessibleName("Apply table changes") }.waitFound().component.isEnabled() }
                auditTableDialog(dialog,directory,"alter-refresh",false)
            }
            dialog.x { byAccessibleName("Apply table changes") }.waitFound().click()
        }
        frame.waitForNoOpenedDialogs()
        waitUntil("alter dialog refreshes the existing grid") { columnNames().any { it.startsWith("refresh_note") } }
        menu("Open in SQL console")
        val console = frame.x { byJavaClass("com.segfault03.ideadb.ui.SqlQueryConsolePanel") }.waitFound()
        val text = console.driver.cast(console.xx { byJavaClass("com.intellij.ui.components.JBTextArea") }.list().first().component, JTextComponent::class)
        val run = console.x { byVisibleText("Run") }.waitFound()
        val stop = console.x { byVisibleText("Stop") }.waitFound()
        fun execute(sql: String) {
            console.driver.withContext(OnDispatcher.EDT) { text.setText(sql) }
            run.click()
            waitUntil("refresh SQL completed") { run.component.isEnabled() && !stop.component.isEnabled() }
            check(!console.hasSubtext("Query failed")) { "Refresh fixture SQL failed: $sql" }
        }
        execute("UPDATE \"PUBLIC\".\"customers\" SET \"name\" = 'Console update'")
        waitUntil("console DML refreshes the hidden open grid") { "Console update" in values() }
        execute("ALTER TABLE \"PUBLIC\".\"customers\" ADD COLUMN \"console_note\" INTEGER")
        waitUntil("console DDL refreshes open grid metadata") { columnNames().any { it.startsWith("console_note") } }

        menu("Open data editor")
        replaceCellValue(grid, 0, 1, "Unsaved draft")
        menu("Open in SQL console")
        execute("UPDATE \"PUBLIC\".\"customers\" SET \"name\" = 'Fresh after revert'")
        waitUntil("dirty editor queues automatic refresh") { status.getText().contains("Database changed") }
        check("Unsaved draft" in values()) { "A refresh must preserve uncommitted cells" }
        menu("Open data editor")
        revert.click()
        waitUntil("queued refresh runs after revert") { "Fresh after revert" in values() && !revert.component.isEnabled() }
        captureScreen(directory.resolve("table-mutation-refresh.png"))

        menu("Truncate table…")
        frame.x(DialogUiComponent::class.java) { byTitle("Truncate table") }.waitFound().pressButton("Truncate table")
        frame.x(DialogUiComponent::class.java) { byTitle("Table truncated") }.waitFound().pressButton("OK")
        waitUntil("explorer truncate refreshes the open grid") { grid.rowCount() == 0 && editor.hasSubtext("0 rows ·") }
        menu("Drop table…")
        frame.x(DialogUiComponent::class.java) { byTitle("Drop table") }.waitFound().pressButton("Drop table")
        frame.x(DialogUiComponent::class.java) { byTitle("Table dropped") }.waitFound().pressButton("OK")
        waitUntil("drop refreshes folder counts and the open grid") {
            tree.pathExists(*(schema + arrayOf("Tables (1)", "customers_archive"))) && editor.hasSubtext("Table no longer exists")
        }
        check(grid.rowCount() == 0 && !grid.component.isEnabled())
        println("Automatic refresh: grid insert/update/delete, alter dialog, console DML/DDL, hidden editor, retained draft/revert, explorer truncate/drop and table counts verified without manual refresh")
    }

    private fun verifyAlterInspector(frame: IdeaFrameUI, root: UiComponent, fixture: JdbcUiFixture, directory: Path) {
        openConnectionMenu(root, frame, "HSQLDB…")
        frame.x(DialogUiComponent::class.java) { byTitle("New connection") }.waitFound().also { dialog ->
            inspectorInput(dialog, "Name:").text = "Local development"
            dialog.x { byVisibleText("JDBC URL") }.waitFound().click()
            inspectorInput(dialog, "JDBC URL:").text = fixture.jdbcUrl
            inspectorInput(dialog, "User:").text = "SA"
            waitUntil("fixture connection ready") { dialog.x { byVisibleText("Add connection") }.waitFound().component.isEnabled() }
            dialog.pressButton("Add connection")
        }
        frame.waitForNoOpenedDialogs()
        val tree = root.x(JTreeUiComponent::class.java) { byJavaClass("com.intellij.ui.treeStructure.Tree") }.waitFound()
        val separator = tree.fixture.separator()
        val schema = arrayOf("Data Sources", "HSQLDB", "Local development", "PUBLIC")
        tree.fixture.expandPath(schema.take(3).joinToString(separator))
        waitUntil("inspector schema connected") { tree.pathExists(*schema) }
        tree.fixture.expandPath(schema.joinToString(separator))
        waitUntil("customers loaded") { tree.pathExists(*(schema + arrayOf("Tables (1)", "customers"))) }
        fun open(table: String = "customers"): DialogUiComponent {
            waitUntil("$table in explorer") { tree.pathExists(*(schema + arrayOf("Tables (1)", table))) }
            val path = (schema + arrayOf("Tables (1)", table)).joinToString(separator)
            tree.fixture.rightClickPath(path)
            frame.x { byVisibleText("Alter table…") }.waitFound().click()
            return frame.x(DialogUiComponent::class.java) { byTitle("Edit table: $table") }.waitFound()
        }
        fun select(dialog: UiComponent, name: String) {
            val list = dialog.x(JListUiComponent::class.java) { byAccessibleName("Table columns") }.waitFound()
            val index = list.rawItems.indexOfFirst { it.substringBefore(" : ") == name }
            check(index >= 0) { "Column $name is absent: ${list.rawItems}" }
            list.clickItemAtIndex(index)
        }
        fun ready(dialog: UiComponent) = waitUntil("inspector SQL ready") { dialog.x { byAccessibleName("Apply table changes") }.waitFound().component.isEnabled() }
        fun applied(dialog: UiComponent) {
            ready(dialog); dialog.x { byAccessibleName("Apply table changes") }.waitFound().click()
            frame.waitForNoOpenedDialogs()
        }
        fun columns(table: String = "customers"): Map<String,Int> = java.sql.DriverManager.getConnection(fixture.jdbcUrl,"SA","").use { conn ->
            conn.metaData.getColumns(null,"PUBLIC",table,"%").use { rows -> buildMap { while(rows.next()) put(rows.getString("COLUMN_NAME"),rows.getInt("COLUMN_SIZE")) } }
        }
        fun add(dialog: UiComponent, name: String, length: String = "32") {
            dialog.x { byAccessibleName("Add column") }.waitFound().click()
            inspectorInput(dialog,"Column name").text = name
            inspectorInput(dialog,"Length / precision").text = length
        }
        var dialog = open(); select(dialog,"email"); assertInspectorLayout(dialog)
        inspectorInput(dialog,"Length / precision").text = "oops"
        select(dialog,"balance")
        selectComboItemWithKeyboard(dialog.x(JComboBoxUiComponent::class.java) { and(byJavaClass("com.segfault03.ideadb.ui.DatabaseInputs\$InputComboBox"), byAccessibleName("Data type")) }.waitFound(), "DOUBLE")
        check(!dialog.x { byAccessibleName("Apply table changes") }.waitFound().component.isEnabled())
        select(dialog,"email"); check(inspectorInput(dialog,"Length / precision").text == "oops")
        inspectorInput(dialog,"Length / precision").text = "255"
        inspectorInput(dialog,"Column name").text = "email_address"
        add(dialog,"id"); check(!dialog.x { byAccessibleName("Apply table changes") }.waitFound().component.isEnabled())
        inspectorInput(dialog,"Column name").text = "loyalty"
        add(dialog,"nickname","64")
        select(dialog,"status"); dialog.x { byAccessibleName("Drop column") }.waitFound().click()
        dialog.x { byAccessibleName("Rename table") }.waitFound().click()
        inspectorInput(dialog,"New table name").text = "contacts"
        select(dialog,"email_address")
        inspectorInput(dialog,"Filter columns").text = "EMAIL"
        inspectorInput(dialog,"Filter columns").text = ""
        check(inspectorInput(dialog,"Length / precision").text == "255")
        ready(dialog); assertTableDialogText(dialog)
        dialog.pressButton("Close")
        frame.x(DialogUiComponent::class.java) { byTitle("Discard changes") }.waitFound().pressButton("Keep editing")
        check(inspectorInput(dialog,"Column name").text == "email_address")
        dialog.x { byAccessibleName("Apply table changes") }.waitFound().click()
        frame.x(DialogUiComponent::class.java) { byTitle("Drop column") }.waitFound().pressButton("Cancel")
        check(columns()["email"] == 180 && !columns().containsKey("loyalty"))
        Robot().mouseMove(10,10); repaintIdeBeforeCapture?.invoke(); Thread.sleep(350)
        auditTableDialog(dialog,directory,"alter-inspector",false)
        captureScreen(directory.resolve("alter-inspector-full-ide.png"))
        val point = dialog.component.getLocationOnScreen()
        savePng(directory.resolve("alter-inspector-dialog.png"),Robot().createScreenCapture(Rectangle(point.x,point.y,dialog.component.width,dialog.component.height)))
        captureBusyState(fixture,dialog,"Updating table…","alter-inspector-updating.png",directory) {
            dialog.x { byAccessibleName("Apply table changes") }.waitFound().click()
            frame.x(DialogUiComponent::class.java) { byTitle("Drop column") }.waitFound().pressButton("Apply all changes")
        }
        frame.waitForNoOpenedDialogs()
        val renamed = columns("contacts")
        check(columns().isEmpty() && renamed["email_address"] == 255 && renamed["nickname"] == 64 && renamed.containsKey("loyalty") && !renamed.containsKey("status"))
        java.sql.DriverManager.getConnection(fixture.jdbcUrl,"SA","").use { conn ->
            conn.metaData.getColumns(null,"PUBLIC","contacts","balance").use { rows -> check(rows.next() && rows.getInt("DATA_TYPE") == java.sql.Types.DOUBLE) }
            conn.createStatement().use { stmt -> stmt.executeQuery("SELECT \"email_address\" FROM \"PUBLIC\".\"contacts\"").use { rows -> check(rows.next() && rows.getString(1) == "ada@example.com") } }
        }
        println("Mixed batch applied: modify + rename same column, change another type, add twice, drop and rename table")

        dialog = open("contacts"); select(dialog,"email_address")
        inspectorInput(dialog,"Length / precision").text = "300"
        select(dialog,"nickname"); inspectorInput(dialog,"Column name").text = "preferred_name"
        add(dialog,"region")
        add(dialog,"invalid_column"); inspectorInput(dialog,"Default value").text = "SQL:NO_SUCH_FUNCTION()"
        add(dialog,"later_note")
        ready(dialog); dialog.x { byAccessibleName("Apply table changes") }.waitFound().click()
        frame.x(DialogUiComponent::class.java) { byTitle("Database Operation Failed") }.waitFound().also {
            captureScreen(directory.resolve("alter-inspector-error.png")); it.pressButton("OK")
        }
        val partial = columns("contacts")
        check(partial["email_address"] == 300 && partial.containsKey("preferred_name") && partial.containsKey("region"))
        check(!partial.containsKey("invalid_column") && !partial.containsKey("later_note"))
        select(dialog,"invalid_column"); check(inspectorInput(dialog,"Default value").text == "SQL:NO_SUCH_FUNCTION()")
        inspectorInput(dialog,"Default value").text = ""
        select(dialog,"later_note"); check(inspectorInput(dialog,"Length / precision").text == "32")
        applied(dialog)
        val final = columns("contacts")
        check(final.size == 9 && final.containsKey("invalid_column") && final.containsKey("later_note") && final["email_address"] == 300)
        println("Column Inspector: mixed batch, pending drafts, invalid hidden input, destructive cancellation, busy animation, partial success and retry verified in the running IDE")
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
        var outside = Point()
        toolbar.driver.ui.ideFrame {
            val origin = component.getLocationOnScreen()
            outside = Point(origin.x + component.width / 2, origin.y + component.height / 2)
        }
        val robot = Robot()
        robot.mouseMove(outside.x, outside.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
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
            val welcomeAction = root.xx { byVisibleText("Add a connection…") }.list().firstOrNull()
            if (welcomeAction != null) welcomeAction.click()
            else {
                val addButton = root.xx { byAccessibleName("Add connection…") }.list().firstOrNull()
                if (addButton != null) addButton.click()
                else openToolbarOverflow(root, "Explorer actions").x { byAccessibleName("Add connection…") }.waitFound().click()
            }
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

    private fun selectComboItemWithKeyboard(combo: JComboBoxUiComponent, label: String) {
        val index = combo.listValues().indexOf(label)
        check(index >= 0) { "Missing combo item '$label': ${combo.listValues()}" }
        // Navigate the real popup and verify its result before using the next card.
        clickComboArrow(combo)
        val robot = Robot()
        robot.keyPress(KeyEvent.VK_HOME)
        robot.keyRelease(KeyEvent.VK_HOME)
        repeat(index) {
            robot.keyPress(KeyEvent.VK_DOWN)
            robot.keyRelease(KeyEvent.VK_DOWN)
        }
        robot.keyPress(KeyEvent.VK_ENTER)
        robot.keyRelease(KeyEvent.VK_ENTER)
        robot.waitForIdle()
        waitUntil("combo selection '$label'") {
            combo.driver.cast(combo.component, LiveComboSelection::class).getSelectedIndex() == index
        }
        println("Keyboard combo transition verified: $label")
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

@Remote("com.intellij.openapi.wm.IdeFrame")
interface LiveIdeFrame { fun getBalloonLayout(): LiveBalloonLayout? }

@Remote("com.intellij.ui.BalloonLayout")
interface LiveBalloonLayout {
    fun closeAll()
    fun add(balloon: LiveBalloon)
}

@Remote("com.intellij.openapi.ui.popup.JBPopupFactory")
interface LivePopupFactory {
    fun getInstance(): LivePopupFactory
    fun createBalloonBuilder(content: LiveSwingComponent): LiveBalloonBuilder
}

@Remote("com.intellij.openapi.ui.popup.BalloonBuilder")
interface LiveBalloonBuilder {
    fun setAnimationCycle(milliseconds: Int): LiveBalloonBuilder
    fun setHideOnClickOutside(hide: Boolean): LiveBalloonBuilder
    fun setHideOnKeyOutside(hide: Boolean): LiveBalloonBuilder
    fun setHideOnFrameResize(hide: Boolean): LiveBalloonBuilder
    fun setHideOnAction(hide: Boolean): LiveBalloonBuilder
    fun createBalloon(): LiveBalloon
}

@Remote("com.intellij.openapi.ui.popup.Balloon")
interface LiveBalloon {
    fun isDisposed(): Boolean
    fun setBounds(bounds: Rectangle)
}

@Remote("javax.swing.JComponent")
interface LiveSwingComponent {
    fun validate()
    fun setPreferredSize(size: LiveDimension)
    fun setSize(width: Int, height: Int)
    fun getLocationOnScreen(): Point
    fun requestFocusInWindow(): Boolean
    fun isShowing(): Boolean
    fun paintImmediately(x: Int, y: Int, width: Int, height: Int)
    fun isVisible(): Boolean
    fun isOpaque(): Boolean
    fun getVisibleRect(): Rectangle
    fun getPreferredSize(): LiveDimension
    fun getMinimumSize(): LiveDimension
    fun getParent(): com.intellij.driver.sdk.ui.remote.Component
    fun getX(): Int
    fun getClientProperty(key: String): Boolean?
    fun getForeground(): LiveColor
    fun getBackground(): LiveColor
    fun getFont(): LiveFont
    fun getFontMetrics(font: LiveFont): LiveFontMetrics
    fun getBaseline(width: Int, height: Int): Int
    fun getInsets(): LiveInsets
}

@Remote("javax.swing.JTree")
interface LiveTree {
    fun expandRow(row: Int)
    fun getRowCount(): Int
    fun getClientProperty(key: String): LiveSearchMatches?
}

@Remote("java.util.Map")
interface LiveSearchMatches { fun size(): Int }

@Remote("javax.swing.JList")
interface LiveColumnList { fun ensureIndexIsVisible(index: Int) }

@Remote("javax.swing.JTextPane")
interface LiveStyledSql {
    fun getText(): String
    fun getStyledDocument(): LiveStyledDocument
}
@Remote("javax.swing.text.StyledDocument")
interface LiveStyledDocument { fun getCharacterElement(offset: Int): LiveStyledElement }
@Remote("javax.swing.text.Element")
interface LiveStyledElement { fun getAttributes(): LiveTextAttributes }
@Remote("javax.swing.text.AttributeSet")
interface LiveTextAttributes
@Remote("javax.swing.text.StyleConstants")
interface LiveTextStyle { fun getForeground(attributes: LiveTextAttributes): LiveColor }
@Remote("javax.swing.UIManager")
interface LiveUiColors { fun getColor(key: String): LiveColor }

@Remote("java.awt.FontMetrics")
interface LiveFontMetrics { fun stringWidth(text: String): Int }

@Remote("java.awt.Insets")
interface LiveInsets

@Remote("com.intellij.util.ui.JBInsets")
interface LiveInsetsMath { fun removeFrom(size: LiveDimension, insets: LiveInsets) }

@Remote("com.intellij.util.ui.JBUI")
interface LiveUiScale { fun scale(value: Float): Float }

@Remote("java.awt.Dimension")
interface LiveDimension {
    fun getWidth(): Double
    fun getHeight(): Double
}

@Remote("java.awt.Color")
interface LiveColor { fun getRGB(): Int }

@Remote("javax.swing.JLabel")
interface LiveLabel {
    fun getIconTextGap(): Int
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
interface LiveTableHeader {
    fun getDefaultRenderer(): LiveCellRenderer
    fun getLocationOnScreen(): Point
    fun getVisibleRect(): Rectangle
    fun getHeight(): Int
}

@Remote("javax.swing.table.TableCellRenderer")
interface LiveCellRenderer {
    fun getTableCellRendererComponent(table: LiveTableEditor, value: String, selected: Boolean, focus: Boolean, row: Int, column: Int): LiveSwingComponent
}

@Remote("javax.swing.JButton")
interface LiveToolbarButton {
    fun getUI(): LiveButtonUi
    fun isDefaultButton(): Boolean
    fun getMargin(): LiveInsets
    fun getIconTextGap(): Int
    fun doClick()
    fun isFocusOwner(): Boolean
    fun getModel(): LiveButtonModel
    fun requestFocusInWindow(): Boolean
    fun getText(): String
    fun getToolTipText(): String
    fun getIcon(): LiveIcon?
}

@Remote("javax.swing.plaf.ButtonUI")
interface LiveButtonUi { fun getPreferredSize(component: LiveSwingComponent): LiveDimension }

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

@Remote("javax.swing.JComboBox")
interface LiveComboSelection { fun getSelectedIndex(): Int }

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

@Remote("javax.swing.JScrollPane")
interface LiveInspectorScroll {
    fun getViewport(): LiveInspectorViewport
    fun getVerticalScrollBar(): LiveSwingComponent
}

@Remote("javax.swing.JViewport")
interface LiveInspectorViewport {
    fun getView(): LiveSwingComponent
    fun getExtentSize(): LiveDimension
}

@Remote("javax.swing.JViewport")
interface LiveViewport { fun getView(): LiveTableEditor }

@Remote("javax.swing.JTable")
interface LiveTableEditor {
    fun getModel(): LiveTableModel
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

@Remote("javax.swing.table.TableModel")
interface LiveTableModel {
    fun getColumnCount(): Int
    fun getColumnName(column: Int): String
}

@Remote("javax.swing.JToggleButton")
interface LiveSourceChoice {
    fun getIcon(): LiveIcon?
    fun isSelected(): Boolean
}

@Remote("javax.swing.text.JTextComponent")
interface LiveTextComponent { fun getText(): String; fun getCaretPosition(): Int }
