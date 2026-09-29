plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.basis"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.basis.diary"
        minSdk = 31
        targetSdk = 37
        // CI run number → monotonically increasing, so every artifact installs over the previous one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.4.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}-stage4"
    }

    signingConfigs {
        // Fixed dev key committed to the repo (not a secret): lets CI builds update each other in place.
        create("dev") {
            storeFile = file("basis-dev.keystore")
            storePassword = "basisdev"
            keyAlias = "basis"
            keyPassword = "basisdev"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
            // x86_64 only for CI emulators.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.getByName("dev")
            ndk { abiFilters += listOf("arm64-v8a") }
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // llama.cpp loads its CPU backend variants with dlopen() from nativeLibraryDir,
        // so native libraries must be extracted on install.
        jniLibs.useLegacyPackaging = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":audio:capture"))
    implementation(project(":feature:home"))
    implementation(project(":feature:logs"))
    implementation(project(":feature:timeline"))
    implementation(project(":pipeline"))
    implementation(project(":ml:models"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
