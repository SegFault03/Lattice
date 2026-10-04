plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.6.0"
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

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("lattice.ide.home").orNull
        if (localIde == null) intellijIdeaCommunity("2025.1") else local(localIde)
        bundledPlugins()
        pluginVerifier("1.410")
    }

    implementation("org.hsqldb:hsqldb:2.7.3")
    implementation("com.mysql:mysql-connector-j:9.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    // IntelliJ 2025 test logging still references JUnit 4 runtime types.
    testRuntimeOnly("junit:junit:4.13.2")
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
    from("LICENSE", "THIRD_PARTY_NOTICES.md")
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
