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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 29
        targetSdk = 37
        versionName = "0.5.1"
        // Derivado do versionName (0.1.0 → 100, 1.2.3 → 10203): o Android recusa atualizar para um
        // versionCode menor, e a atualização pelo GitHub (update/AppUpdater) compara o versionName.
        // Só X.Y.Z com cada parte de 0 a 99, senão uma versão mais nova poderia ter código menor.
        val version = requireNotNull(versionName)
        val parts = version.split('.').map { it.toIntOrNull() }
        require(parts.size == 3 && parts.all { it != null && it in 0..99 }) { "versionName \"$version\": use X.Y.Z, cada parte de 0 a 99" }
        versionCode = parts.fold(0) { code, part -> code * 100 + part!! }
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
            // R8: o código Kotlin/Compose cai de ~24 MB. As classes usadas pelo JNI ficam em
            // slicer-core/consumer-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

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
