import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.6.0"
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

val uiTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val uiTestRuntimeOnly by configurations.getting {
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

    implementation("org.hsqldb:hsqldb:2.7.3")
    implementation("com.mysql:mysql-connector-j:9.0.0")
    constraints {
        implementation("com.google.protobuf:protobuf-java:4.28.2") {
            because("Fix CVE-2024-7254 in Connector/J 9.0.0's protobuf runtime")
        }
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    // IntelliJ 2025 test logging still references JUnit 4 runtime types.
    testRuntimeOnly("junit:junit:4.13.2")

    uiTestImplementation(kotlin("stdlib"))
    uiTestRuntimeOnly(kotlin("reflect"))
    uiTestImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    uiTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    uiTestRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
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
intellijPlatform {
    pluginConfiguration {
        version.set(project.version.toString())
        ideaVersion { sinceBuild.set("251") }
        providers.gradleProperty("releaseNotesFile").orNull?.let { changeNotes.set(file(it).readText()) }
    }
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
}

val uiScreenshotTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        val uiTestSourceSet = sourceSets["uiTest"]
        testClassesDirs = uiTestSourceSet.output.classesDirs
        classpath = uiTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        systemProperty("java.awt.headless", "false")
        systemProperty("ui.screenshot.dir", layout.buildDirectory.dir("ui-test-results").get().asFile.absolutePath)
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
