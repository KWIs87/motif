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
        // Spotify App Remote and Auth AARs are dropped into app/libs by CI (see .github/workflows/build.yml)
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Motif"
include(":app")
