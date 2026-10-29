import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.andredevel.slicer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.andredevel.pocketslicer"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Chave de release fora do repositório (scripts/make-release-key.sh). Sem ela, o
    // assembleRelease gera um APK não assinado.
    val releaseKey = File(System.getProperty("user.home"), ".android-slicer/keystore.properties")
    signingConfigs {
        if (releaseKey.isFile) {
            create("release") {
                val props = Properties().apply { releaseKey.inputStream().use(::load) }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // Idiomas do app (res/values*) para a escolha por app do Android 13+.
        generateLocaleConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        // CAs da Bambu Lab (printer.cer) para conferir o certificado das impressoras no envio.
        variant.sources.assets?.addStaticSourceDirectory(rootProject.file("third_party/OrcaSlicer/resources/cert").path)
    }
}

dependencies {
    implementation(project(":slicer-core"))
    implementation(project(":profiles"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
