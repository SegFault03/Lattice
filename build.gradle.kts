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
}

val uiTestImplementation = configurations.getByName("uiTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
val uiTestRuntimeOnly = configurations.getByName("uiTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("lattice.ide.home").orNull
        if (localIde == null) intellijIdeaCommunity("2025.1") else local(localIde)
        bundledPlugins()
        pluginVerifier("1.410")
        testFramework(TestFrameworkType.Starter, configurationName = "uiTestImplementation")
    }

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

    uiTestImplementation(kotlin("stdlib"))
    uiTestRuntimeOnly(kotlin("reflect"))
    uiTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    uiTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    test {
        useJUnitPlatform { excludeTags("integration") }
        systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    }
}

// Live fixtures are opt-in; these tasks contain discoverable JUnit methods rather than main-only programs.
tasks.register<Test>("integrationTest") {
    description = "Run MySQL and HSQLDB functional integration suites"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    // These live JDBC suites run without an IDE sandbox, but need SDK types.
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    useJUnitPlatform { includeTags("integration") }
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    shouldRunAfter(tasks.test)
}

// Keep Gradle and the standalone packager on the same IntelliJ 2025 / Java 21 baseline.
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
tasks.processResources {
    from("LICENSE", "docs/THIRD_PARTY_NOTICES.md")
    from("licenses") { into("licenses") }
}

tasks.register<JavaExec>("fallbackDriverTest") {
    description = "Check JDBC loading and disposal without drivers on the application classpath"
    group = "verification"
    dependsOn(tasks.testClasses)
    mainClass.set("com.segfault03.ideadb.FallbackDriverLifecycleTest")
    classpath = sourceSets["main"].output + sourceSets["test"].output +
        sourceSets["main"].compileClasspath.filter {
            !it.name.startsWith("mysql-connector-") && !it.name.startsWith("hsqldb-")
        }
    args(file("lib").absolutePath)
}

tasks.register<JavaExec>("databaseCompatibilityTest") {
    description = "Run the downloadable MySQL/HSQLDB driver and Java 8 compatibility matrix"
    group = "verification"
    dependsOn(tasks.testClasses)
    mainClass.set("com.segfault03.ideadb.DatabaseCompatibilityTest")
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].compileClasspath
    doFirst {
        val mode = providers.gradleProperty("lattice.compatibility.mode").orNull
            ?: throw GradleException("Set -Plattice.compatibility.mode=mysql|hsqldb")
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
            "hsqldb" -> listOf(mode, java8, probeClasses)
            else -> throw GradleException("Unknown compatibility mode: $mode")
        })
        providers.gradleProperty("lattice.compatibility.cache").orNull?.let {
            systemProperty("lattice.jdbc.cache", it)
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
    pluginVerification {
        ides {
            create("IC", "2025.1")
            create("IC", "2025.2")
            create("IU", "2025.3")
        }
    }
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
        systemProperty("ui.theme.id", providers.gradleProperty("lattice.ui.theme").orElse("ExperimentalDark").get())
        systemProperty("ui.review.enabled", providers.gradleProperty("lattice.ui.review").orElse("false").get())
        systemProperty("ui.inputs.only", providers.gradleProperty("lattice.ui.inputsOnly").orElse("false").get())
        systemProperty("ui.maven.repository", layout.buildDirectory.dir("ui-test-maven/repository").get().asFile.absolutePath)
        val ide = tasks.named<RunIdeTask>("runIde").get()
        systemProperty("ui.ide.home", ide.platformPath.toString())
        systemProperty("ui.ide.build", ide.productInfo.buildNumber)
        systemProperty("ui.ide.version", ide.productInfo.version)
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}
