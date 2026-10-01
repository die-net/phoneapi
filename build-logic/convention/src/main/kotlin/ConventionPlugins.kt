import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> { configureAndroidApp(target) }
            configureKotlin()
            configureDetekt()
            pluginManager.apply("com.autonomousapps.dependency-analysis")
            tasks.withType<Test>().configureEach { useJUnitPlatform() }
        }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> { configureAndroidLibrary(target) }
            configureKotlin()
            configureDetekt()
            pluginManager.apply("com.autonomousapps.dependency-analysis")
            tasks.withType<Test>().configureEach { useJUnitPlatform() }
        }
}

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = javaVersion
                targetCompatibility = javaVersion
            }
            configureKotlin()
            configureDetekt()
            pluginManager.apply("com.autonomousapps.dependency-analysis")
            tasks.withType<Test>().configureEach { useJUnitPlatform() }
        }
}
