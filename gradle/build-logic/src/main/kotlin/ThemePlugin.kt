import com.android.build.api.dsl.LibraryExtension
import app.dokusho.gradle.api.dsl.DokushoThemeExtension
import app.dokusho.gradle.internal.VALID_LIB_VERSIONS
import app.dokusho.gradle.internal.extensions.alias
import app.dokusho.gradle.internal.extensions.compileOnly
import app.dokusho.gradle.internal.extensions.implementation
import app.dokusho.gradle.internal.extensions.dok
import app.dokusho.gradle.internal.extensions.libs
import app.dokusho.gradle.internal.extensions.plugins
import app.dokusho.gradle.internal.extensionsLib
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies

@Suppress("UNUSED")
class ThemePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.library)
            alias(libs.plugins.kotlin.serialization)

            alias(dok.plugins.android.base)
            alias(dok.plugins.spotless)
        }

        val dokusho = extensions.create<DokushoThemeExtension>("dokusho")

        android {
            namespace = "eu.kanade.tachiyomi.multisrc.${project.name}"

            sourceSets {
                named("main") {
                    manifest.srcFile("AndroidManifest.xml")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }
        }

        dependencies {
            compileOnly(libs.bundles.common)
            compileOnly(dokusho.libVersion.flatMap { libs.extensionsLib(it) })
            implementation(project(":core"))
        }

        afterEvaluate {
            val libVersionValue = dokusho.libVersion.get()
            check(libVersionValue in VALID_LIB_VERSIONS) {
                "libVersion $libVersionValue is not supported. Supported versions: $VALID_LIB_VERSIONS"
            }
        }
    }
}

private fun Project.android(block: LibraryExtension.() -> Unit) {
    extensions.configure(block)
}
