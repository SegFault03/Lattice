plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.vibe.lattice"
version = "1.0.0"

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
intellijPlatform {
    pluginConfiguration { ideaVersion { sinceBuild.set("251") } }
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.2")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
}
