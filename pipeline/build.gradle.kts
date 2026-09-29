plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.basis.pipeline"
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
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    api(libs.work.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:common"))
    api(project(":core:database"))
    api(project(":core:datastore"))
    api(project(":audio:buffer"))
    api(project(":ml:asr"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
