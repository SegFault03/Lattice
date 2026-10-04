plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.0.0"
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
        intellijIdeaCommunity("2024.2")
        bundledPlugins()
    }

    implementation("org.hsqldb:hsqldb:2.7.3")
    implementation("com.mysql:mysql-connector-j:9.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    test {
        useJUnitPlatform { excludeTags("integration") }
    }
}

// Live fixtures are opt-in; these tasks contain discoverable JUnit methods rather than main-only programs.
tasks.register<Test>("integrationTest") {
    description = "Run MySQL and HSQLDB functional integration suites"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
}
