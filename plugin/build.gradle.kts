import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Call the platform interfaces' default methods instead of generating overrides of
        // them, which the Plugin Verifier reports as uses of deprecated and experimental API.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    // The client's coroutines and serialization come from the IDE, so nothing
    // but the client's own classes lands in the plugin's lib directory.
    implementation(project(":polaris-client"))
    implementation(project(":polaris-preview")) {
        // The IDE provides SLF4J; Hadoop's logging bindings are never wanted.
        exclude(group = "org.slf4j")
        exclude(group = "ch.qos.reload4j")
        exclude(group = "log4j")
    }

    testImplementation(libs.junit4)

    intellijPlatform {
        pycharm(libs.versions.pycharmCompile.get())
        testFramework(TestFrameworkType.Platform)
    }
}

val pluginVersion = project.version.toString()

intellijPlatform {
    projectName = "polaris-catalog-browser"
    buildSearchableOptions = false

    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = pluginVersion
        vendor {
            name = providers.gradleProperty("vendorName")
            email = providers.gradleProperty("vendorEmail").map { it.trim() }.filter { it.isNotEmpty() }
            url = providers.gradleProperty("vendorUrl")
        }
        // The notes of the version being built, from its section of CHANGELOG.md. A local copy of
        // the version: the lambda must not capture the build script (configuration cache).
        val heading = "## $pluginVersion"
        changeNotes = providers.fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md")).asText.map { text ->
            val section = text.substringAfter(heading, "").substringBefore("\n## ").trim()
            section.lines().filter { it.startsWith("- ") }
                .joinToString("", "<ul>", "</ul>") { "<li>" + it.removePrefix("- ").replace("&", "&amp;").replace("<", "&lt;") + "</li>" }
        }
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }

    // Values come from the environment (CI secrets), never from the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // -PpluginChannel=beta publishes to a channel users opt into; the default is the main one.
        channels = providers.gradleProperty("pluginChannel").map { listOf(it) }.orElse(listOf("default"))
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.PyCharm, libs.versions.pycharmLatestPrevious.get())
            create(IntelliJPlatformType.PyCharm, libs.versions.pycharmLatestCurrent.get())
        }
    }
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
