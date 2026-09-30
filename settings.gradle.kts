pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Prebuilt native AARs fetched by tools/fetch-native.sh (not committed).
        maven {
            url = uri("third_party/maven")
            content { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "basis"

include(
    ":app",
    ":core:common",
    ":core:datastore",
    ":core:crypto",
    ":core:database",
    ":audio:capture",
    ":audio:vad",
    ":audio:buffer",
    ":ml:models",
    ":ml:asr",
    ":ml:llm",
    ":pipeline",
    ":feature:home",
    ":feature:logs",
    ":feature:timeline",
    ":feature:chat",
)
