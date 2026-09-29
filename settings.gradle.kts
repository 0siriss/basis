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
    }
}

rootProject.name = "basis"

include(
    ":app",
    ":core:common",
    ":core:datastore",
    ":audio:capture",
    ":feature:home",
    ":feature:logs",
)
