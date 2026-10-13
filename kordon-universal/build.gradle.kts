// Builds the single distributable build/libs/Kordon.jar: every platform module plus a relocated Gson.
plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    // One jar with both descriptors (bungee.yml and velocity-plugin.json). The proxy APIs are compileOnly in the
    // platform modules and never reach this runtime classpath.
    implementation(project(":kordon-bungee"))
    implementation(project(":kordon-velocity"))
}

// Only the shaded jar is published.
tasks.jar { enabled = false }

tasks.shadowJar {
    archiveFileName = "Kordon.jar"
    relocate("com.google.gson", "com.kordonlabs.plugin.lib.gson")
    dependencies { exclude(dependency("com.google.errorprone:.*")) }
    mergeServiceFiles()
    exclude("module-info.class", "META-INF/versions/*/module-info.class", "META-INF/maven/**",
            "META-INF/proguard/**", "META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest { attributes("Implementation-Title" to "Kordon", "Implementation-Version" to project.version) }
    // Gson is a multi-release jar only because of META-INF/versions/9/module-info.class, which is excluded above.
    addMultiReleaseAttribute = false
}

tasks.assemble { dependsOn(tasks.shadowJar) }
