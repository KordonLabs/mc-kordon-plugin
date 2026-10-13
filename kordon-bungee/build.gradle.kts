// BungeeCord / Waterfall module.
plugins {
    `java-library`
}

// Non-transitive on purpose: bungeecord-protocol depends on com.mojang:brigadier, which is not on Maven Central.
val bungeeApi = listOf(
    libs.bungeecord.api,
    libs.bungeecord.chat,
    libs.bungeecord.config,
    libs.bungeecord.event,
    libs.bungeecord.protocol,
    libs.bungeecord.serializer,
    libs.bungeecord.dialog,
)

dependencies {
    implementation(project(":kordon-common"))
    for (api in bungeeApi) {
        compileOnly(api) { isTransitive = false }
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("bungee.yml", "kordon/version.properties")) { expand(props) }
}
