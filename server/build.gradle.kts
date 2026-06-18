import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false

    id("org.owasp.dependencycheck") version "12.2.2"
    id("co.uzzu.dotenv.gradle") version "4.0.0"

    // Quality tools
    id("com.diffplug.spotless") version "6.25.0"
    id("com.github.spotbugs") version "6.0.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
    id("net.ltgt.errorprone") version "4.1.0" apply false
}

group = "de.tum.teamteam"
version = "0.0.1-SNAPSHOT"

// Root build output
layout.buildDirectory = layout.projectDirectory.dir("build")

allprojects {
    repositories {
        mavenCentral()
    }
}

val owaspRoot =
    rootProject.layout.buildDirectory.dir("reports/security-report").get().asFile

val owaspData =
    rootProject.layout.projectDirectory.dir("../data/owasp-data").asFile

subprojects {

    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "com.github.spotbugs")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "net.ltgt.errorprone")

    // -----------------------------
    // Spring dependency management
    // -----------------------------
    the<DependencyManagementExtension>().apply {
        imports {
            mavenBom(
                org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES
            )
        }
    }

    // -----------------------------
    // Build dirs per module
    // -----------------------------
    layout.buildDirectory = rootProject.layout.buildDirectory.dir(project.name)

    // -----------------------------
    // Java toolchain
    // -----------------------------
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // -----------------------------
    // Spotless (formatting)
    // -----------------------------
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    // -----------------------------
    // SpotBugs (static analysis)
    // -----------------------------
    extensions.configure<com.github.spotbugs.snom.SpotBugsExtension> {
        effort.set(com.github.spotbugs.snom.Effort.MAX)
        reportLevel.set(com.github.spotbugs.snom.Confidence.LOW)
    }

    // -----------------------------
    // Detekt (Kotlin analysis)
    // -----------------------------
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom("$rootDir/config/detekt/detekt.yml")
    }

    // -----------------------------
    // Error Prone (Java compile-time checks)
    // -----------------------------
    dependencies {
        add("errorprone", "com.google.errorprone:error_prone_core:2.23.0")
        add("compileOnly", "com.github.spotbugs:spotbugs-annotations:4.8.6")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-Werror")
    }

    // -----------------------------
    // Tests
    // -----------------------------
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            exceptionFormat = TestExceptionFormat.FULL
            showCauses = true
            showStackTraces = true
            showStandardStreams = true
        }
    }

    // -----------------------------
    // Aggregate quality checks
    // -----------------------------
    tasks.named("check") {
    dependsOn(
        "spotlessCheck",
        "spotbugsMain",
        "detekt"
    )
}

// Make build run the same quality gate as check
tasks.named("build") {
    dependsOn("check")
}
}

// -----------------------------
// OWASP Dependency Check
// -----------------------------
configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    outputDirectory = owaspRoot
    format = org.owasp.dependencycheck.reporting.ReportGenerator.Format.ALL.toString()
    failBuildOnCVSS = 7.0f

    nvd.apiKey =
        System.getenv("NVD_API_KEY")
            ?: env.fetchOrNull("NVD_API_KEY")
            ?: ""

    data {
        directory = owaspData.absolutePath
    }
}
