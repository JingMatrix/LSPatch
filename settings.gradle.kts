enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("core/gradle/libs.versions.toml"))
        }
        create("lspatch") {
            from(files("gradle/lspatch.versions.toml"))
        }
    }
}

rootProject.name = "LSPatch"
include(
    ":apkzlib",
    ":jar",
    ":manager",
    ":meta-loader",
    ":patch",
    ":patch-loader",
    ":share:android",
    ":share:java",
)

includeBuild("core") {
    dependencySubstitution {
        substitute(module("vector:axml")).using(project(":external:axml"))
        substitute(module("vector:bridge")).using(project(":hiddenapi:bridge"))
        substitute(module("vector:core")).using(project(":core"))
        substitute(module("vector:daemon-service")).using(project(":services:daemon-service"))
        substitute(module("vector:stubs")).using(project(":hiddenapi:stubs"))
    }
}
