plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "org.lsposed.lspatch.share"

    buildFeatures {
        androidResources = false
        buildConfig = false
    }
}

dependencies {
    implementation("vector:daemon-service")
}
