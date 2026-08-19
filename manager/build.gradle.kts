import java.util.Locale

val defaultManagerPackageName: String by rootProject.extra
val apiCode: Int by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val coreVerCode: Int by rootProject.extra
val coreVerName: String by rootProject.extra

plugins {
    // Kotlin comes from AGP 9's built-in support; applying org.jetbrains.kotlin.android is an error
    // since AGP 9.0. The compose / ksp / parcelize plugins still attach to that built-in Kotlin.
    alias(libs.plugins.agp.app)
    alias(lspatch.plugins.compose.compiler)
    alias(lspatch.plugins.google.devtools.ksp)
    alias(lspatch.plugins.rikka.tools.refine)
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    defaultConfig {
        applicationId = defaultManagerPackageName
    }

    androidResources {
        noCompress.add(".so")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        all {
            sourceSets[name].assets.srcDirs(rootProject.projectDir.resolve("out/assets/$name"))
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    namespace = "org.lsposed.lspatch"
}

// Pinned, not inferred. compose-destinations otherwise derives the generated package from the
// longest common prefix of every @Destination composable, so adding one destination outside
// ui.page would silently move NavGraphs and ui.page.destinations.* up a level and break every
// import that names them.
ksp {
    arg("compose-destinations.codeGenPackageName", "org.lsposed.lspatch.ui.page")
}

afterEvaluate {
    android.applicationVariants.forEach { variant ->
        val variantLowered = variant.name.lowercase()
        val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

        // The loader dex/so land in out/assets/<variant> (the assets srcDir above) from tasks in the
        // sibling loader modules. Gradle cannot infer those producers from a shared directory, so the
        // asset merge names them directly: a dependency routed through an aggregator lifecycle task
        // carries no output and does not satisfy the input/output validation, which a parallel build
        // (CI) turns into a hard error rather than a warning.
        val loaderArtifacts = listOf(
            ":meta-loader:copyDex$variantCapped",
            ":patch-loader:copyDex$variantCapped",
            ":patch-loader:copySo$variantCapped",
        )
        tasks.named("merge${variantCapped}Assets") { dependsOn(loaderArtifacts) }

        // Lint reads that same directory to model the variant, and infers its producers no better than
        // the asset merge does. Undeclared, the validation is a hard error rather than a warning, so
        // `gradlew build` fails on a project that assembles perfectly well.
        tasks.matching { it.name.contains("lint", ignoreCase = true) && it.name.contains(variantCapped) }
            .configureEach { dependsOn(loaderArtifacts) }

        tasks.register<Copy>("build$variantCapped") {
            dependsOn(tasks["assemble$variantCapped"])
            from(variant.outputs.map { it.outputFile })
            into("${rootProject.projectDir}/out/$variantLowered")
            rename(".*.apk", "manager-v$verName-$verCode-$variantLowered.apk")
        }
    }
}

dependencies {
    implementation(projects.patch)
    implementation(projects.apkzlib)
    implementation("vector:axml")
    implementation("vector:daemon-service")
    implementation("vector:manager-ui")
    implementation(projects.share.android)
    implementation(projects.share.java)
    implementation(platform(lspatch.androidx.compose.bom))

    annotationProcessor(lspatch.androidx.room.compiler)
    compileOnly(lspatch.rikka.hidden.stub)
    debugImplementation(lspatch.androidx.compose.ui.tooling)
    debugImplementation(lspatch.androidx.customview)
    debugImplementation(lspatch.androidx.customview.poolingcontainer)
    implementation(lspatch.androidx.activity.compose)
    implementation(lspatch.androidx.compose.material.icons.extended)
    implementation(lspatch.androidx.compose.material3)
    implementation(lspatch.androidx.compose.material3.adaptive.navigation.suite)
    implementation(lspatch.androidx.compose.ui)
    implementation(lspatch.androidx.compose.ui.tooling.preview)
    implementation(lspatch.androidx.core.ktx)
    implementation(lspatch.androidx.lifecycle.viewmodel.compose)
    implementation(lspatch.androidx.navigation.compose)
    implementation(lspatch.androidx.preference)
    implementation(lspatch.androidx.room.ktx)
    implementation(lspatch.androidx.room.runtime)
    implementation(lspatch.google.accompanist.navigation.animation)
    implementation(lspatch.google.accompanist.pager)
    implementation(lspatch.google.accompanist.swiperefresh)
    implementation(lspatch.material)
    implementation(libs.gson)
    implementation(lspatch.rikka.shizuku.api)
    implementation(lspatch.rikka.shizuku.provider)
    implementation(lspatch.rikka.refine)
    implementation(lspatch.raamcosta.compose.destinations)
    implementation(lspatch.appiconloader)
    implementation(lspatch.hiddenapibypass)
    ksp(lspatch.androidx.room.compiler)
    ksp(lspatch.raamcosta.compose.destinations.ksp)
}
