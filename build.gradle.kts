plugins {
    alias(libs.plugins.shadow) apply false
}

subprojects {
    apply(plugin = "java-library")
    group = "com.kordonlabs"
    version = rootProject.version

    // Builds on JDK 21 or newer; the bytecode always targets Java 21.
    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all,-processing,-serial")
    }

    // Reproducible archives: the same sources produce a byte-identical jar.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
