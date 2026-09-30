plugins {
    id("phoneapi.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin { explicitApi() }

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
