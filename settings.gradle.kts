pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    val errorText = """

        =====================[ ERROR ]=====================
         The Stratum project directory is not a properly cloned Git repository.

         In order to build Stratum from source you must clone
         the Stratum repository using Git, not download a code
         zip from GitHub.

         Built Stratum jars are available for download at
         https://github.com/StratumMC/Stratum/releases

         See CONTRIBUTING.md for further information on building
         and modifying Stratum.
        ===================================================
    """.trimIndent()
    error(errorText)
}

rootProject.name = "stratum"

for (name in listOf("stratum-api", "stratum-server")) {
    include(name)
    file(name).mkdirs()
}

optionalInclude("test-plugin")
optionalInclude("paper-generator")

fun optionalInclude(name: String, op: (ProjectDescriptor.() -> Unit)? = null) {
    val settingsFile = file("$name.settings.gradle.kts")
    if (settingsFile.exists()) {
        apply(from = settingsFile)
        findProject(":$name")?.let { op?.invoke(it) }
    } else {
        settingsFile.writeText(
            """
            // Uncomment to enable the '$name' project
            // include(":$name")

            """.trimIndent()
        )
    }
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val stratumVersionChannel = providers.gradleProperty("channel").get().trim()
    val stratumBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (stratumBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$stratumBuildNumber-${stratumVersionChannel.lowercase()}"
    }
    version = versionString
}

if (providers.gradleProperty("stratumBuildCacheEnabled").orNull.toBoolean()) {
    val buildCacheUsername = providers.gradleProperty("stratumBuildCacheUsername").orElse("").get()
    val buildCachePassword = providers.gradleProperty("stratumBuildCachePassword").orElse("").get()
    if (buildCacheUsername.isBlank() || buildCachePassword.isBlank()) {
        println("The Stratum remote build cache is enabled, but no credentials were provided. Remote build cache will not be used.")
    } else {
        val buildCacheUrl = providers.gradleProperty("stratumBuildCacheUrl")
            .orElse("https://gradle-build-cache.stratum.mc/")
            .get()
        val buildCachePush = providers.gradleProperty("stratumBuildCachePush").orNull?.toBoolean()
            ?: System.getProperty("CI").toBoolean()
        buildCache {
            remote<HttpBuildCache> {
                url = uri(buildCacheUrl)
                isPush = buildCachePush
                credentials {
                    username = buildCacheUsername
                    password = buildCachePassword
                }
            }
        }
    }
}
