import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    java
    id("com.modrinth.minotaur") version "2.9.0"
    id("io.papermc.hangar-publish-plugin") version "0.1.4"
}

group = "dev.yoshiro"
version = "1.3.2"

// Minecraft versions declared on Modrinth and Hangar uploads.
// Update this list when new Minecraft versions come out.
val supportedMinecraftVersions = listOf("26.1", "26.1.1", "26.1.2")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

// Publishing: run by .github/workflows/publish.yml on GitHub release.
// The changelog comes from the release body via the CHANGELOG env var.

modrinth {
    token.set(System.getenv("MODRINTH_TOKEN"))
    projectId.set("versioneye")
    versionNumber.set(version.toString())
    versionType.set("release")
    uploadFile.set(tasks.jar)
    gameVersions.set(supportedMinecraftVersions)
    loaders.set(listOf("paper", "purpur", "folia"))
    changelog.set(System.getenv("CHANGELOG") ?: "")
}

hangarPublish {
    publications.register("plugin") {
        version.set(project.version.toString())
        channel.set("Release")
        id.set("VersionEye")
        apiKey.set(System.getenv("HANGAR_API_KEY"))
        changelog.set(System.getenv("CHANGELOG") ?: "")
        platforms {
            register(Platforms.PAPER) {
                jar.set(tasks.jar.flatMap { it.archiveFile })
                platformVersions.set(supportedMinecraftVersions)
            }
        }
    }
}
