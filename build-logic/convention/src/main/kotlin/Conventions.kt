import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.Lint
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.report.ReportMergeTask
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

internal object Sdk {
    const val COMPILE = 37
    const val TARGET = 36
    const val MIN = 29
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            if (this is KotlinJvmCompilerOptions) {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

internal fun Project.configureDetekt() {
    pluginManager.apply("dev.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig.set(true)
        allRules.set(true)
        parallel.set(true)
        debug.set(providers.gradleProperty("detektDebug").isPresent)
        config.setFrom(rootProject.file("config/detekt.yml"))
        basePath.set(rootProject.layout.projectDirectory)
    }
    val merge = rootProject.tasks.named("detektReportMerge", ReportMergeTask::class.java)
    tasks.withType<Detekt>().configureEach { finalizedBy(merge) }
    merge.configure { input.from(tasks.withType<Detekt>().map { it.reports.sarif.outputLocation }) }
}

internal fun Lint.configureLint(project: Project) {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = true
    checkReleaseBuilds = true
    lintConfig = project.rootProject.file("config/lint.xml")
}

internal val javaVersion = JavaVersion.VERSION_17

internal fun ApplicationExtension.configureAndroidApp(project: Project) {
    compileSdk = Sdk.COMPILE
    defaultConfig {
        minSdk = Sdk.MIN
        targetSdk = Sdk.TARGET
    }
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    lint { configureLint(project) }
}

internal fun LibraryExtension.configureAndroidLibrary(project: Project) {
    compileSdk = Sdk.COMPILE
    defaultConfig { minSdk = Sdk.MIN }
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    lint {
        configureLint(project)
        targetSdk = Sdk.TARGET
    }
}
