import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.springframework.boot") version "3.4.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.owasp.dependencycheck") version "12.2.2"
    id("co.uzzu.dotenv.gradle") version "4.0.0"

}

group = "de.tum.teamteam"
version = "0.0.1-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}
val owaspRoot = rootProject.layout.buildDirectory.dir("reports/security-report").get().asFile
val owaspData = rootProject.layout.projectDirectory.dir("data/owasp-data").asFile

subprojects {

    apply(plugin = "java")

    // Configure central build directory with subproject folders
    layout.buildDirectory = rootProject.layout.buildDirectory.dir(project.name)

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

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
}
configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
        outputDirectory = owaspRoot
        format = org.owasp.dependencycheck.reporting.ReportGenerator.Format.ALL.toString()
        failBuildOnCVSS = 7.0f
        nvd.apiKey = System.getenv("NVD_API_KEY") ?: env.fetchOrNull("NVD_API_KEY") ?: ""

        data {
            directory = owaspData.absolutePath
        }
    }