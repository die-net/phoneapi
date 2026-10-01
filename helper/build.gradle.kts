plugins { id("phoneapi.android.library") }

android {
    namespace = "net.die.phoneapi.helper"
    buildFeatures { aidl = true }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}
