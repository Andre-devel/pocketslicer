plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.andredevel.slicer.core"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // x86_64 só para o emulador; o app publicado é arm64-v8a.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // libslic3r em Debug (-O0 -g) gera gigabytes de objetos e fatia
                // devagar demais; sempre Release, inclusive no build debug do app.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
                targets += "slicer_jni"
            }
        }
    }

    externalNativeBuild {
        cmake {
            // Pré-requisito: scripts/build-deps.sh para cada ABI acima.
            path = file("../native/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

androidComponents {
    onVariants { variant ->
        // Modelos de teste e G-code de referência (testdata/) para os testes instrumentados.
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory(rootProject.file("testdata").path)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(project(":profiles"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
