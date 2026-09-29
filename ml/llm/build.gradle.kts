plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.basis.ml.llm"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        externalNativeBuild {
            cmake {
                // llama.cpp must be optimized even in debug APKs, otherwise it is unusably slow.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                cppFlags += listOf("-O3", "-DNDEBUG")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":ml:models"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
