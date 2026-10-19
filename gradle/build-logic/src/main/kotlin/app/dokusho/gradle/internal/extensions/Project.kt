package app.dokusho.gradle.internal.extensions

import org.gradle.accessors.dm.LibrariesForDok
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the

internal val Project.libs get() = the<LibrariesForLibs>()
internal val Project.dok get() = the<LibrariesForDok>()

internal fun Project.spotlessTaskName() = if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"
