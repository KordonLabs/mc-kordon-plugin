// Platform-independent core. Never depends on a proxy API.
plugins {
    `java-library`
}

dependencies {
    // Shaded and relocated by kordon-universal.
    implementation(libs.gson)
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "apiUrl" to providers.gradleProperty("kordonApiUrl").get(),
    )
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("kordon/version.properties") { expand(props) }
}
