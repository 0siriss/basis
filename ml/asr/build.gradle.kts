plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.basis.ml.asr"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":ml:models"))
    implementation(project(":core:common"))
    implementation(libs.sherpa.onnx)
}
