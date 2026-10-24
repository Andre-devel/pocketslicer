plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.andredevel.slicer.profiles"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }
}

// Os arquivos de resources/ do OrcaSlicer que libslic3r lê em tempo de execução,
// num único zip (asset), extraído por OrcaResources na primeira execução.
// Só os JSON dos perfis por enquanto: imagens e modelos de mesa entram com o viewport 3D.
val orcaResources = rootProject.file("third_party/OrcaSlicer/resources")
val zipOrcaResources = tasks.register<Zip>("zipOrcaResources") {
    from(orcaResources) {
        include("profiles/**/*.json", "info/**", "flush/**")
    }
    archiveFileName = "orca-resources.zip" // OrcaResources.ASSET
    destinationDirectory = layout.buildDirectory.dir("generated/orcaResources")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(zipOrcaResources, Zip::getDestinationDirectory)
    }
}
