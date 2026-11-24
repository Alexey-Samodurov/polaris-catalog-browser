import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "polaris-catalog-browser"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

include("polaris-client", "polaris-preview", "plugin")
