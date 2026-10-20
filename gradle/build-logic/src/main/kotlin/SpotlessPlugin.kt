import app.dokusho.gradle.internal.extensions.alias
import app.dokusho.gradle.internal.extensions.libs
import app.dokusho.gradle.internal.extensions.plugins
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.FormatterFunc
import com.diffplug.spotless.FormatterStep
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import java.io.Serializable

@Suppress("UNUSED")
class SpotlessPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.spotless)
        }

        // Configuration should be synced with [/gradle/build-logic/build.gradle.kts]
        val ktlintVersion = libs.ktlint.bom.get().version
        // Every module applies this plugin itself, so the root checks only its own files and never
        // reads module sources or their build output (KSP, spotless-clean) under src/<lang>/<name>/.
        val isRoot = this == rootProject
        spotless {
            kotlin {
                if (isRoot) target("*.kts") else target("src/**/*.kt", "*.kts")
                targetExclude("**/build/**")
                ktlint(ktlintVersion)
                    .editorConfigOverride(
                        mapOf(
                            "max_line_length" to 2147483647,
                        ),
                    )
                trimTrailingWhitespace()
                endWithNewline()
                addStep(RandomUAChecker.toFormatterStep())
            }

            if (!isRoot) {
                java {
                    target("src/**/*.java")
                    targetExclude("**/build/**")
                    googleJavaFormat()
                    removeUnusedImports()
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }

            format("gradle") {
                target("*.gradle")
                trimTrailingWhitespace()
                endWithNewline()
            }

            if (!isRoot) {
                format("xml") {
                    target("src/**/*.xml")
                    targetExclude("**/build/**")
                    trimTrailingWhitespace()
                    endWithNewline()
                }
            }
        }
    }
}

private fun Project.spotless(block: SpotlessExtension.() -> Unit) {
    extensions.configure(block)
}

private class RandomUAChecker : Serializable {

    fun getFormatter() = FormatterFunc { content ->
        if ("package dokusho.lib.randomua" !in content &&
            "dokusho.lib.randomua" in content &&
            "override fun getMangaUrl(" !in content
        ) {
            throw AssertionError(
                "usage of :lib:randomua requires override of getMangaUrl()",
            )
        }
        content
    }

    companion object {
        fun toFormatterStep(): FormatterStep = FormatterStep.create(
            "randomua-requires-getMangaUrl",
            RandomUAChecker(),
            RandomUAChecker::getFormatter,
        )
    }
}
