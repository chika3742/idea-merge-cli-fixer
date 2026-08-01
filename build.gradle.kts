import org.jetbrains.changelog.date
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = "net.chikach"
// The version is taken from the Git tag name (the leading "v" is stripped),
// passed in as the `pluginVersion` Gradle property by the release workflow.
// Falls back to a default for local builds.
val projectVersion = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")
version = projectVersion

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1.2")
    }
}

intellijPlatform {
    pluginConfiguration {
        description = providers.fileContents(
            layout.projectDirectory.file("PLUGIN_DESCRIPTION.md")
        ).asText.map(::markdownToHTML)

        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1.2")
            create(IntelliJPlatformType.IntellijIdea, "262.8665.81")
        }
    }

    signing {
        val chainFile = layout.projectDirectory.file("./chain.crt")
        if (chainFile.asFile.exists()) {
            certificateChainFile.set(chainFile)
        } else {
            certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        }
        val pemFile = layout.projectDirectory.file("./private.pem")
        if (pemFile.asFile.exists()) {
            privateKeyFile.set(pemFile)
        } else {
            privateKey = providers.environmentVariable("PRIVATE_KEY")
        }

        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.named("verifyPluginSignature") {
    dependsOn(tasks.named("signPlugin"))
}

tasks {
    patchPluginXml {
        changeNotes = changelog.renderItem(
            changelog.getUnreleased()
                .withHeader(false)
                .withEmptySections(false)
        )
    }
}

changelog {
    version = projectVersion
    path = file("CHANGELOG.md").canonicalPath
    header = provider { "${version.get()} - ${date()}" }
    headerParserRegex = """(\d+\.\d+\.\d+)""".toRegex()
}
