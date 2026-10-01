plugins {
    id("phoneapi.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin { explicitApi() }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.core)
}
