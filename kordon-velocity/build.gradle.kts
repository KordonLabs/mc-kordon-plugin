// Velocity 3.4+ / 4.x module. The Velocity API is compileOnly and never ends up in Kordon.jar.
plugins {
    `java-library`
}

dependencies {
    implementation(project(":kordon-common"))
    compileOnly(libs.velocity.api)
}

// velocity-api ships an annotation processor that would generate a second velocity-plugin.json.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-proc:none") }

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("velocity-plugin.json") { expand(props) }
}
