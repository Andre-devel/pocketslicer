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

// Os arquivos de resources/ do OrcaSlicer que libslic3r lê em tempo de execução, mais os
// perfis dos fatiadores dos fabricantes (vendors/, ver Engine::vendors()),
// num único zip (asset), extraído por OrcaResources na primeira execução.
// Só os JSON dos perfis por enquanto: imagens e modelos de mesa entram com o viewport 3D.
// Em i18n/<idioma>/ ficam as traduções dos textos do motor (Engine::set_language): o
// catálogo do Orca e o da fachada, para os idiomas do app.
val orcaResources = rootProject.file("third_party/OrcaSlicer/resources")
val appLanguages = listOf("en", "pt_BR")
val zipOrcaResources = tasks.register<Zip>("zipOrcaResources") {
    from(orcaResources) {
        include("profiles/**/*.json", "info/**", "flush/**")
    }
    // Perfis copiados dos fatiadores dos fabricantes (scripts/import-vendor-profiles.py),
    // sem os presets do usuário.
    from(rootProject.file("profiles/imported")) {
        include("*/*.json", "*/*/**/*.json")
        exclude("*/user/**")
        into("vendors")
    }
    from(rootProject.file("third_party/OrcaSlicer/localization/i18n")) {
        include(appLanguages.map { "$it/OrcaSlicer_$it.po" })
        into("i18n")
    }
    from(rootProject.file("native/i18n")) {
        into("i18n")
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
