import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("org.owasp.dependencycheck") version "12.2.2" apply false
    id("co.uzzu.dotenv.gradle") version "4.0.0"

}

group = "de.tum.teamteam"
version = "0.0.1-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {

    apply(plugin = "java")
    apply(plugin = "org.owasp.dependencycheck")

    // Configure central build directory with subproject folders
    layout.buildDirectory = rootProject.layout.buildDirectory.dir(project.name)

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
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

      configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
        format = org.owasp.dependencycheck.reporting.ReportGenerator.Format.ALL.toString()
        outputDirectory = layout.buildDirectory.dir("security-report").get().asFile
        failBuildOnCVSS = 9.0f
        nvd.apiKey = System.getenv("NVD_API_KEY") ?: env.fetchOrNull("NVD_API_KEY") ?: ""

        data {
            directory = layout.buildDirectory.dir("owasp-data").get().asFile.absolutePath
        }
    }
}