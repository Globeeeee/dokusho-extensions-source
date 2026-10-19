plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(dok.plugins.android.base)
    alias(dok.plugins.spotless)
}

android {
    namespace = "dokusho.core"

    buildFeatures {
        resValues = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(libs.bundles.common)
    compileOnly(project(":extensions-lib"))

    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
    testImplementation(libs.junit)
}
