import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
}

group = "com.segfault03.lattice"
val pluginReleaseVersion = providers.gradleProperty("releaseVersion").orElse(providers.gradleProperty("pluginVersion")).get()
require(pluginReleaseVersion.matches(Regex("(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)(-[0-9A-Za-z-]+([.][0-9A-Za-z-]+)*)?")) &&
    pluginReleaseVersion.substringAfter('-', "").split('.').none { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) {
    "Version must be X.Y.Z or X.Y.Z-prerelease without numeric leading zeroes: $pluginReleaseVersion"
}
version = pluginReleaseVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

sourceSets {
    create("uiTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
    // IDE fixture tests (BasePlatformTestCase) live apart from the JUnit 5 tiers: they need the platform
    // test framework at runtime and must not be discovered by tasks that replace the test classpath.
    create("platformTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val uiTestImplementation = configurations.getByName("uiTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
val uiTestRuntimeOnly = configurations.getByName("uiTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}
configurations.getByName("platformTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.getByName("platformTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
// The platform plugin attaches the IDE classes to the standard source sets only: through testCompileOnly,
// and by tagging the resolvable classpath so its archive transforms run. Reproduce both for this source set.
configurations.getByName("platformTestCompileOnly") { extendsFrom(configurations.testCompileOnly.get()) }
configurations.getByName("platformTestCompileClasspath") {
    listOf("intellijPlatformCollected", "intellijPlatformExtracted", "intellijPlatformPluginLibrariesFiltered").forEach { name ->
        attributes.attribute(Attribute.of(name, Boolean::class.javaObjectType), true)
    }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("lattice.ide.home").orNull
        if (localIde == null) intellijIdeaCommunity("2025.1") else local(localIde)
        bundledPlugins()
        pluginVerifier("1.410")
        zipSigner("0.1.24")
        testFramework(TestFrameworkType.Starter, configurationName = "uiTestImplementation")
        testFramework(TestFrameworkType.Platform, configurationName = "platformTestImplementation")
    }

    implementation("org.postgresql:postgresql:42.7.14") { exclude(group = "org.checkerframework", module = "checker-qual") }
    implementation("com.h2database:h2:2.3.232")
    implementation("org.hsqldb:hsqldb:2.7.4")
    implementation("com.mysql:mysql-connector-j:26.7.0")
    constraints {
        implementation("com.google.protobuf:protobuf-java:4.36.2") {
            because("Use the current patched protobuf runtime required by Connector/J")
        }
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
    // IntelliJ 2025 test logging still references JUnit 4 runtime types.
    testRuntimeOnly("junit:junit:4.13.2")

    // BasePlatformTestCase is JUnit 3/4 style; the vintage engine runs it on the JUnit Platform.
    "platformTestImplementation"("junit:junit:4.13.2")
    "platformTestRuntimeOnly"("org.junit.vintage:junit-vintage-engine:5.14.4")

    uiTestImplementation(kotlin("stdlib"))
    uiTestRuntimeOnly(kotlin("reflect"))
    uiTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    uiTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    inputs.property("releaseVersion", project.version.toString())
    filesMatching("release-notes/release.properties") {
        expand("version" to project.version.toString())
    }
    from("screenshots") {
        include("side-panel.png", "alter-table-dialog.png", "connection-dialog.png")
        into("release-notes/images")
    }
    from("docs/CHANGELOG.md") { into("release-notes") }
}

tasks {
    test {
        useJUnitPlatform { excludeTags("integration") }
        systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    }
}

// Functional checks are the commit tier; the remaining ordinary tests form the PR regression tier.
tasks.register<Test>("functionalTest") {
    description = "Run embedded provider workflows and connection/driver feature contracts"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    useJUnitPlatform { includeTags("functional"); excludeTags("integration") }
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
}

intellijPlatformTesting.testIde.register("regressionTest") {
    task {
        description = "Run service, model and Swing regressions outside the functional tier"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
        useJUnitPlatform { excludeTags("functional", "integration") }
        systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    }
}

intellijPlatformTesting.testIde.register("platformTest") {
    task {
        description = "Run IDE fixture tests for editor features (language, completion, injection, documentation)"
        group = "verification"
        testClassesDirs = sourceSets["platformTest"].output.classesDirs
        // Append, never replace: the IDE test runtime (platform test framework, sandbox) is already on the classpath.
        classpath += sourceSets["platformTest"].runtimeClasspath
        useJUnitPlatform { includeEngines("junit-vintage") }
        systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    }
}

// Live fixtures are opt-in; these tasks contain discoverable JUnit methods rather than main-only programs.
tasks.register<Test>("integrationTest") {
    description = "Run MySQL, HSQLDB and PostgreSQL server integration suites"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    // These live JDBC suites run without an IDE sandbox, but need SDK types.
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    useJUnitPlatform {
        includeTags("integration")
        excludeTags("h2integration") // Owned H2 transports run once in h2IntegrationTest.
        if (!providers.environmentVariable("LATTICE_TEST_POSTGRES_ENABLED").orElse(providers.environmentVariable("LATTICE_TEST_POSTGRES_PORT").map { "true" }.orElse("false")).get().toBoolean()) excludeTags("postgres")
    }
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("h2IntegrationTest") {
    description = "Run owned H2 TCP/SSL functional and regression suites"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    useJUnitPlatform { includeTags("h2integration") }
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    shouldRunAfter(tasks.test)
}

// Keep Gradle and the standalone packager on the same IntelliJ 2025 / Java 21 baseline.
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
tasks.processResources {
    from("LICENSE", "docs/THIRD_PARTY_NOTICES.md")
    from("licenses") { into("licenses") }
}

val fallbackDriverJars = tasks.register<Sync>("fallbackDriverJars") {
    from(configurations.runtimeClasspath.map { files ->
        files.filter { it.name.startsWith("mysql-connector-") || it.name.startsWith("hsqldb-") || it.name.startsWith("postgresql-") || it.name.startsWith("h2-") }
    })
    into(layout.buildDirectory.dir("fallback-drivers"))
}

// Keep the legacy cancellation fixture outside the application/driver classpath.
val cancellationDriver = configurations.create("cancellationDriver") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies { add(cancellationDriver.name, "org.hsqldb:hsqldb:2.7.3:jdk8") }
val cancellationDriverJar = tasks.register<Sync>("cancellationDriverJar") {
    from(cancellationDriver)
    into(layout.buildDirectory.dir("cancellation-driver"))
}

tasks.withType<Test>().configureEach {
    dependsOn(fallbackDriverJars)
    dependsOn(cancellationDriverJar)
    systemProperty("lattice.test.cancellation.driver", layout.buildDirectory.file("cancellation-driver/hsqldb-2.7.3-jdk8.jar").get().asFile.absolutePath)
    systemProperty("lattice.test.drivers", layout.buildDirectory.dir("fallback-drivers").get().asFile.absolutePath)
    systemProperty("lattice.test.mysql.port", providers.environmentVariable("LATTICE_TEST_MYSQL_PORT").orElse("3306").get())
    systemProperty("lattice.test.hsqldb.port", providers.environmentVariable("LATTICE_TEST_HSQLDB_PORT").orElse("9001").get())
    systemProperty("lattice.test.postgres.port", providers.environmentVariable("LATTICE_TEST_POSTGRES_PORT").orElse("5432").get())
    systemProperty("lattice.test.postgres.password", providers.environmentVariable("LATTICE_TEST_POSTGRES_PASSWORD").orElse("lattice-test").get())
    systemProperty("lattice.test.postgres.authRequired", providers.environmentVariable("LATTICE_TEST_POSTGRES_AUTH_REQUIRED").orElse("false").get())
    systemProperty("lattice.test.postgres.enabled", providers.environmentVariable("LATTICE_TEST_POSTGRES_ENABLED").orElse("false").get())
}

tasks.register<JavaExec>("fallbackDriverTest") {
    description = "Check JDBC loading and disposal without drivers on the application classpath"
    group = "verification"
    dependsOn(tasks.testClasses)
    mainClass.set("com.segfault03.ideadb.FallbackDriverLifecycleTest")
    systemProperty("lattice.test.mysql.port", providers.environmentVariable("LATTICE_TEST_MYSQL_PORT").orElse("3306").get())
    systemProperty("lattice.test.hsqldb.port", providers.environmentVariable("LATTICE_TEST_HSQLDB_PORT").orElse("9001").get())
    systemProperty("lattice.test.postgres.port", providers.environmentVariable("LATTICE_TEST_POSTGRES_PORT").orElse("5432").get())
    systemProperty("lattice.test.postgres.password", providers.environmentVariable("LATTICE_TEST_POSTGRES_PASSWORD").orElse("lattice-test").get())
    systemProperty("lattice.test.postgres.authRequired", providers.environmentVariable("LATTICE_TEST_POSTGRES_AUTH_REQUIRED").orElse("false").get())
    systemProperty("lattice.test.postgres.enabled", providers.environmentVariable("LATTICE_TEST_POSTGRES_ENABLED").orElse("false").get())
    classpath = sourceSets["main"].output + sourceSets["test"].output +
        sourceSets["main"].compileClasspath.filter {
            !it.name.startsWith("mysql-connector-") && !it.name.startsWith("hsqldb-") && !it.name.startsWith("postgresql-") && !it.name.startsWith("h2-")
        }
    // Reuse the live metadata assertions without putting JDBC drivers on the parent classpath.
    classpath += sourceSets["test"].runtimeClasspath.filter {
        it.name.startsWith("junit-jupiter-api-") || it.name.startsWith("junit-platform-commons-") ||
            it.name.startsWith("opentest4j-") || it.name.startsWith("apiguardian-api-")
    }
    dependsOn(fallbackDriverJars)
    args(layout.buildDirectory.dir("fallback-drivers").get().asFile.absolutePath)
}

tasks.register<JavaExec>("h2FallbackDriverTest") {
    description = "Exercise packaged H2 fallback loading without JDBC drivers on the parent classpath"
    group = "verification"
    dependsOn(tasks.testClasses, fallbackDriverJars)
    mainClass.set("com.segfault03.ideadb.H2FallbackDriverTest")
    classpath = sourceSets["main"].output + sourceSets["test"].output +
        sourceSets["main"].compileClasspath.filter {
            !it.name.startsWith("h2-") && !it.name.startsWith("hsqldb-") &&
                !it.name.startsWith("mysql-connector-") && !it.name.startsWith("postgresql-")
        }
    classpath += sourceSets["test"].runtimeClasspath.filter {
        it.name.startsWith("junit-jupiter-api-") || it.name.startsWith("junit-platform-commons-") ||
            it.name.startsWith("opentest4j-") || it.name.startsWith("apiguardian-api-")
    }
    args(layout.buildDirectory.dir("fallback-drivers").get().asFile.absolutePath)
}

tasks.register<JavaExec>("databaseCompatibilityTest") {
    description = "Run the downloadable database driver and Java 8 compatibility matrix"
    group = "verification"
    dependsOn(tasks.testClasses)
    mainClass.set(when (providers.gradleProperty("lattice.compatibility.mode").orNull) {
        "postgres" -> "com.segfault03.ideadb.provider.PostgresCompatibilityTest"
        "h2" -> "com.segfault03.ideadb.provider.H2CompatibilityTest"
        else -> "com.segfault03.ideadb.DatabaseCompatibilityTest"
    })
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    doFirst {
        val mode = providers.gradleProperty("lattice.compatibility.mode").orNull
            ?: throw GradleException("Set -Plattice.compatibility.mode=mysql|hsqldb|postgres|h2")
        val java8 = providers.gradleProperty("lattice.compatibility.java8").orNull
            ?: throw GradleException("Set -Plattice.compatibility.java8 to a Java 8 runtime")
        val probeClasses = providers.gradleProperty("lattice.compatibility.probe").orNull
            ?: throw GradleException("Set -Plattice.compatibility.probe to the compiled Java 8 probe directory")
        setArgs(when (mode) {
            "mysql" -> listOf(
                mode,
                providers.gradleProperty("lattice.compatibility.port").orNull
                    ?: throw GradleException("Set -Plattice.compatibility.port"),
                providers.gradleProperty("lattice.compatibility.server").orNull
                    ?: throw GradleException("Set -Plattice.compatibility.server"),
                java8,
                probeClasses,
            )
            "postgres" -> listOf(mode,
                providers.gradleProperty("lattice.compatibility.port").get(),
                providers.gradleProperty("lattice.compatibility.server").get(), java8, probeClasses)
            "hsqldb", "h2" -> listOf(mode, java8, probeClasses)
            else -> throw GradleException("Unknown compatibility mode: $mode")
        })
        systemProperty("lattice.test.keepDependencies", !providers.gradleProperty("lattice.compatibility.cleanup").orElse("true").get().toBoolean())
        providers.gradleProperty("lattice.compatibility.cache").orNull?.let {
            systemProperty("lattice.jdbc.cache", it)
        }
        providers.gradleProperty("lattice.compatibility.drivers").orNull?.let {
            systemProperty("lattice.test.compatibility.drivers", it)
        }
        providers.gradleProperty("lattice.compatibility.output").orNull?.let {
            systemProperty("lattice.test.output", it)
        }
    }
}
intellijPlatform {
    pluginConfiguration {
        version.set(project.version.toString())
        ideaVersion { sinceBuild.set("251") }
        providers.gradleProperty("releaseNotesFile").orNull?.let { changeNotes.set(file(it).readText()) }
    }
    signing {
        privateKey.set(providers.environmentVariable("LATTICE_SIGNING_PRIVATE_KEY"))
        certificateChain.set(providers.environmentVariable("LATTICE_SIGNING_CERTIFICATE_CHAIN"))
        password.set(providers.environmentVariable("LATTICE_SIGNING_PRIVATE_KEY_PASSWORD"))
    }
    pluginVerification {
        ides {
            create("IC", "2025.1")
            create("IC", "2025.2")
            create("IU", "2025.3")
        }
    }
}

// Publishing must fail closed; ordinary development builds need no signing secrets.
if (providers.gradleProperty("lattice.requireSigning").orElse("false").get().toBoolean()) {
    for (name in listOf("LATTICE_SIGNING_PRIVATE_KEY", "LATTICE_SIGNING_CERTIFICATE_CHAIN")) {
        require(!providers.environmentVariable(name).orNull.isNullOrBlank()) { "Missing signing secret: $name" }
    }
}

tasks.named("verifyPluginSignature") {
    dependsOn("signPlugin")
}

intellijPlatformTesting.testIdeUi.register("uiScreenshotTest") {
    task {
        val uiTestSourceSet = sourceSets["uiTest"]
        testClassesDirs = uiTestSourceSet.output.classesDirs
        classpath = uiTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        outputs.upToDateWhen { false } // Rendering must launch the IDE even when code is unchanged.
        systemProperty("java.awt.headless", "false")
        systemProperty("ui.screenshot.dir", providers.gradleProperty("lattice.ui.output").orElse(layout.buildDirectory.dir("ui-test-results").get().asFile.absolutePath).get())
        systemProperty("ui.dialog.audit", providers.gradleProperty("lattice.ui.dialogAudit").orElse("false").get())
        systemProperty("ui.scale", providers.gradleProperty("lattice.ui.scale").orElse("1.0").get())
        systemProperty("ui.theme.id", providers.gradleProperty("lattice.ui.theme").orElse("ExperimentalDark").get())
        systemProperty("ui.review.enabled", providers.gradleProperty("lattice.ui.review").orElse("false").get())
        systemProperty("ui.inputs.only", providers.gradleProperty("lattice.ui.inputsOnly").orElse("false").get())
        systemProperty("ui.sourceFilter.only", providers.gradleProperty("lattice.ui.sourceFilterOnly").orElse("false").get())
        systemProperty("ui.explorerSearch.only", providers.gradleProperty("lattice.ui.explorerSearchOnly").orElse("false").get())
        systemProperty("ui.alterTable.only", providers.gradleProperty("lattice.ui.alterTableOnly").orElse("false").get())
        systemProperty("ui.createTable.only", providers.gradleProperty("lattice.ui.createTableOnly").orElse("false").get())
        systemProperty("ui.cellDetails.only", providers.gradleProperty("lattice.ui.cellDetailsOnly").orElse("false").get())
        systemProperty("ui.skip.remote.downloads", providers.environmentVariable("LATTICE_UI_SKIP_REMOTE_DOWNLOADS").orElse("false").get())
        systemProperty("ui.h2.enabled", providers.gradleProperty("lattice.ui.h2").orElse(providers.environmentVariable("LATTICE_UI_H2")).orElse("false").get())
        systemProperty("ui.postgres.port", providers.gradleProperty("lattice.ui.postgres.port").orElse(providers.environmentVariable("LATTICE_UI_POSTGRES_PORT")).orElse("0").get())
        systemProperty("ui.postgres.password", providers.environmentVariable("LATTICE_UI_POSTGRES_PASSWORD").orElse("lattice-test").get())
        systemProperty("ui.style.only", providers.gradleProperty("lattice.ui.styleOnly").orElse("false").get())
        systemProperty("ui.style.lightThemes", providers.gradleProperty("lattice.ui.lightThemes").orElse("false").get())
        systemProperty("ui.maven.repository", providers.gradleProperty("lattice.ui.maven.repository").orElse(layout.buildDirectory.dir("ui-test-maven/repository").map { it.asFile.absolutePath }).get())
        val ide = tasks.named<RunIdeTask>("runIde").get()
        // Launch a newer installed IDE without changing the plugin's compilation SDK.
        val uiIdeHome = providers.gradleProperty("lattice.ui.ide.home").orNull
        if (uiIdeHome == null) {
            systemProperty("ui.ide.home", ide.platformPath.toString())
            systemProperty("ui.ide.build", ide.productInfo.buildNumber)
            systemProperty("ui.ide.version", ide.productInfo.version)
            systemProperty("ui.ide.product", ide.productInfo.productCode)
        } else {
            val info = groovy.json.JsonSlurper().parse(file("$uiIdeHome/product-info.json")) as Map<*, *>
            systemProperty("ui.ide.home", file(uiIdeHome).absolutePath)
            systemProperty("ui.ide.build", requireNotNull(info["buildNumber"]).toString())
            systemProperty("ui.ide.version", requireNotNull(info["version"]).toString())
            systemProperty("ui.ide.product", requireNotNull(info["productCode"]).toString())
        }
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Reuse the compilation SDK for CI UI fixtures instead of downloading a second IDE.
tasks.register("writeUiIdeHome") {
    val ideHome = tasks.named<RunIdeTask>("runIde").get().platformPath.toString()
    val output = layout.buildDirectory.file("ui-ide-home.txt").get().asFile
    doLast {
        output.parentFile.mkdirs()
        output.writeText(ideHome)
    }
}
