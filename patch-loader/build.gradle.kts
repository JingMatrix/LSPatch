import java.util.Locale

plugins {
    alias(libs.plugins.agp.app)
}

android {
    defaultConfig {
        multiDexEnabled = false
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }
    namespace = "org.lsposed.lspatch.loader"
}

androidComponents.onVariants { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }

    task<Copy>("copyDex$variantCapped") {
        dependsOn("assemble$variantCapped")
        from("$buildDir/intermediates/dex/${variant.name}/mergeDex$variantCapped/classes.dex")
        rename("classes.dex", "loader.dex")
        into("${rootProject.projectDir}/out/assets/${variant.name}/lspatch")
    }

    task<Copy>("copySo$variantCapped") {
        dependsOn("assemble$variantCapped")
        dependsOn("strip${variantCapped}DebugSymbols")
        val libDir = variant.name + "/strip${variantCapped}DebugSymbols"
        from(
            fileTree(
                "dir" to "$buildDir/intermediates/stripped_native_libs/$libDir/out/lib",
                "include" to listOf("**/liblspatch.so")
            )
        )
        into("${rootProject.projectDir}/out/assets/${variant.name}/lspatch/so")
    }

    task("copy$variantCapped") {
        dependsOn("copySo$variantCapped")
        dependsOn("copyDex$variantCapped")

        doLast {
            println("Dex and so files has been copied to ${rootProject.projectDir}${File.separator}out")
        }
    }
}

dependencies {
    compileOnly("vector:stubs")
    implementation("vector:core")
    implementation("vector:bridge")
    implementation("vector:daemon-service")
    implementation(projects.share.android)
    implementation(projects.share.java)

    implementation(libs.gson)
}
