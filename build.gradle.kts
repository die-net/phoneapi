plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.dependency.analysis)
}

spotless {
    val ktfmtVersion = libs.versions.ktfmt.get()
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktfmt(ktfmtVersion).kotlinlangStyle()
    }
}

tasks.register<dev.detekt.gradle.report.ReportMergeTask>("detektReportMerge") {
    output.set(layout.buildDirectory.file("reports/detekt/merge.sarif"))
}

dependencyAnalysis {
    issues {
        all {
            onAny { severity("fail") }
            onUnusedDependencies {
                // The JUnit Platform loads the engine through ServiceLoader, so bytecode
                // analysis never sees a reference to it.
                exclude("org.junit.jupiter:junit-jupiter-engine")
            }
        }
    }
}
