pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // velocity-api and velocity-brigadier exist only here; nothing else may come from this repository.
        exclusiveContent {
            forRepository { maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" } }
            filter { includeGroup("com.velocitypowered") }
        }
    }
}
rootProject.name = "kordon-plugin"
include("kordon-common", "kordon-bungee", "kordon-velocity", "kordon-universal")
