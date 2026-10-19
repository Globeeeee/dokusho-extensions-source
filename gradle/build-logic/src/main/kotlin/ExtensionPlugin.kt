import app.dokusho.gradle.api.DeeplinkFilter
import app.dokusho.gradle.api.dsl.DokushoExtension
import app.dokusho.gradle.api.dsl.DokushoThemeExtension
import app.dokusho.gradle.api.dsl.ExtensionDeeplink
import app.dokusho.gradle.internal.EXTENSIONS_LIB_PROJECT
import app.dokusho.gradle.internal.ExtensionMetadata
import app.dokusho.gradle.internal.ResolvedSource
import app.dokusho.gradle.internal.SourceMetadata
import app.dokusho.gradle.internal.VALID_LIB_VERSIONS
import app.dokusho.gradle.internal.extensions.alias
import app.dokusho.gradle.internal.extensions.compileOnly
import app.dokusho.gradle.internal.extensions.dok
import app.dokusho.gradle.internal.extensions.implementation
import app.dokusho.gradle.internal.extensions.ksp
import app.dokusho.gradle.internal.extensions.libs
import app.dokusho.gradle.internal.extensions.plugins
import app.dokusho.gradle.internal.toMetadata
import app.dokusho.gradle.tasks.CreateExtensionJarTask
import app.dokusho.gradle.tasks.GenerateManifestTask
import app.dokusho.gradle.tasks.GenerateSourceInfoTask
import app.dokusho.gradle.tasks.SignExtensionJarTask
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.tasks.PackageAndroidArtifact
import com.google.devtools.ksp.gradle.KspExtension
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("UNUSED")
class ExtensionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.serialization)
            alias(libs.plugins.ksp)

            alias(dok.plugins.android.base)
            alias(dok.plugins.spotless)
        }

        val dokusho = extensions.create<DokushoExtension>("dokusho")
        val dirSuffix = "${project.parent?.name}.${project.name}"
        val pkgName = dokusho.pkgName.orElse(dirSuffix)

        android {
            namespace = "eu.kanade.tachiyomi.extension"

            sourceSets {
                named("main") {
                    manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            lint {
                checkReleaseBuilds = false
            }

            // SIGNING_KEYSTORE may point outside the repo (for example ~/.dokusho/signing/*.jks);
            // the default name is what CI decodes the secret into. Never commit either.
            val releaseKeystore = providers.environmentVariable("SIGNING_KEYSTORE").orNull
                ?.let { java.io.File(it) }
                ?: rootProject.file("signingkey.jks")

            signingConfigs {
                create("release") {
                    storeFile = releaseKeystore
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = if (releaseKeystore.exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = true
                    proguardFiles(rootProject.file("common/proguard-rules.pro"))
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        val themeExtension = dokusho.theme.map { themeName ->
            evaluationDependsOn(":lib-multisrc:$themeName").extensions.findByType<DokushoThemeExtension>()
                ?: throw AssertionError("Theme project :lib-multisrc:$themeName must apply dok.plugins.multisrc")
        }

        val versionCodeProvider = themeExtension.flatMap { themeDokusho ->
            val themeLib = themeDokusho.libVersion.get()
            val extLib = dokusho.libVersion.get()
            check(themeLib == extLib) {
                "Multisrc libVersion ($themeLib) and extension libVersion ($extLib) must match."
            }
            themeDokusho.baseVersionCode.zip(dokusho.versionCode) { base, ext -> base + ext }
        }.orElse(dokusho.versionCode)

        val versionNameProvider = dokusho.libVersion.flatMap { libVersion ->
            check(libVersion in VALID_LIB_VERSIONS) {
                "libVersion $libVersion is not supported. Supported versions: $VALID_LIB_VERSIONS"
            }
            versionCodeProvider.map { "$libVersion.$it" }
        }

        val androidVersionCodeProvider = dokusho.libVersion.flatMap { libVersion ->
            versionCodeProvider.map { versionCode ->
                libVersion.split(".").joinToString("") { it.padStart(2, '0') }.toInt().times(1000) + versionCode
            }
        }

        val themeDeeplinks = themeExtension
            .flatMap { it.deeplinks }
            .orElse(emptyList())

        val defaultHostsProvider = dokusho.sources.map { sources ->
            sources.flatMap { it.resolvedBaseUrl.get().allUrls() }.mapNotNull(::extractHost)
        }

        val deeplinksProvider = dokusho.deeplinks
            .zip(themeDeeplinks) { local, theme -> local + theme }
            .zip(defaultHostsProvider) { specs, defaultHosts ->
                specsToFilters(specs, defaultHosts)
            }

        val manifestTask = tasks.register<GenerateManifestTask>("generateExtensionManifest") {
            this.filters.set(deeplinksProvider)
            this.extensionName.set(dokusho.name)
            this.contentWarning.set(dokusho.contentWarning)
            this.extensionLib.set(dokusho.libVersion)
        }

        val sourceInfoTask = tasks.register<GenerateSourceInfoTask>("generateSourceInfo") {
            this.outputFile.set(layout.buildDirectory.file("dokusho-source-info.json"))
        }

        val proguardConfiguration = configurations.create("proguard") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies { add("proguard", libs.proguard) }

        val providedClasspath = configurations.create("extensionProvidedClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(configurations.getByName("compileOnly"))
        }

        val signingConfig = extensions.getByType(ApplicationExtension::class.java).signingConfigs
            .getByName(if (rootProject.file("signingkey.jks").exists()) "release" else "debug")

        androidComponents {
            val bootClasspath = sdkComponents.bootClasspath

            finalizeDsl {
                val suffix = pkgName.get()
                check(APPLICATION_ID_SUFFIX_REGEX.matches(suffix)) {
                    "pkgName '$suffix' is invalid. Expected dot-separated alphanumeric segments (e.g. '$dirSuffix')."
                }
                it.defaultConfig.applicationIdSuffix = suffix
            }

            onVariants { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercase() }

                variant.sources.manifests.addStaticManifestFile("AndroidManifest.xml")
                variant.sources.manifests.addGeneratedManifestFile(manifestTask) { it.outputFile }

                val filenameProvider = versionNameProvider.map { "dokusho-${pkgName.get()}-v$it" }

                variant.outputs.forEach { output ->
                    output.versionCode.set(androidVersionCodeProvider)
                    output.versionName.set(versionNameProvider)
                    @Suppress("UnstableApiUsage")
                    output.outputFileName.set(filenameProvider.map { "$it.apk" })
                }

                if (variant.buildType == "release") {
                    val externalLibs = providedClasspath.incoming.artifactView {
                        attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
                    }.files

                    val createTask = tasks.register<CreateExtensionJarTask>("create${variantName}ExtensionJar") {
                        libraryClasspath.from(externalLibs, bootClasspath)
                        proguardConfigFile.set(layout.buildDirectory.file("outputs/mapping/${variant.name}/configuration.txt"))
                        manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                        apkDir.set(variant.artifacts.get(SingleArtifact.APK))
                        proguardClasspath.from(proguardConfiguration)
                        outputJar.set(layout.buildDirectory.file("intermediates/extension_jar/${variant.name}/unsigned.jar"))
                    }

                    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                        .use(createTask)
                        .toGet(
                            ScopedArtifact.CLASSES,
                            CreateExtensionJarTask::jars,
                            CreateExtensionJarTask::dirs,
                        )

                    val signTask = tasks.register<SignExtensionJarTask>("sign${variantName}ExtensionJar") {
                        inputJar.set(createTask.flatMap { it.outputJar })
                        signingConfig.storeFile?.let { keystore.from(it) }
                        storePassword.set(signingConfig.storePassword.orEmpty())
                        keyAlias.set(signingConfig.keyAlias.orEmpty())
                        keyPassword.set(signingConfig.keyPassword.orEmpty())
                        minSdkVersion.set(dok.versions.android.sdk.min.map { it.toInt() })
                        outputJar.set(layout.buildDirectory.file(filenameProvider.map { "outputs/jar/${variant.name}/$it.jar" }))
                    }

                    tasks.matching { it.name == "assemble$variantName" }
                        .configureEach { dependsOn(signTask) }
                }
            }
        }

        dependencies {
            addProvider("implementation", dokusho.theme.map { project(":lib-multisrc:$it") })
            implementation(project(":core"))
            compileOnly(libs.bundles.common)
            compileOnly(project(EXTENSIONS_LIB_PROJECT))
            ksp(project(":compiler"))
        }

        afterEvaluate {
            val specs = dokusho.sources.get()
            check(specs.isNotEmpty()) { "At least one source { } block is required" }

            val extName = dokusho.name.get()
            val resolvedSources = specs.map { spec ->
                val name = spec.name.orElse(extName).get()
                val lang = spec.lang.get()
                val baseUrl = spec.resolvedBaseUrl.get().toMetadata()

                val id = spec.id.orElse(
                    providers.provider {
                        computeSourceId(name, lang, spec.versionId.orElse(1).get())
                    },
                ).get()
                ResolvedSource(name, lang, id, baseUrl)
            }
            val translationsFile = project(":core").projectDir.resolve("translations/strings.json")
            extensions.configure<KspExtension> {
                arg("kei_sources", Json.encodeToString<List<ResolvedSource>>(resolvedSources))
                arg("kei_translations", translationsFile.absolutePath)
            }
            tasks.matching { it.name.startsWith("ksp") }.configureEach {
                inputs.file(translationsFile)
            }

            val packageName = "eu.kanade.tachiyomi.extension.${pkgName.get()}"
            val sourceInfos = resolvedSources.map { source ->
                SourceMetadata(
                    id = source.id,
                    name = source.name,
                    lang = source.lang,
                    baseUrl = source.baseUrl.defaultUrl,
                    mirrorUrls = source.baseUrl.mirrors.map { it.url },
                )
            }
            // Proto dokusho.gradle.api.ContentWarning: UNSPECIFIED=0, SAFE=1, MIXED=2, NSFW=3 (enum ordinal + 1).
            val contentWarningOrdinal = dokusho.contentWarning.get().ordinal + 1
            val libVersionValue = dokusho.libVersion.get()

            val sourceInfoJsonProvider = androidVersionCodeProvider.zip(versionNameProvider) { code, name ->
                Json.encodeToString(
                    ExtensionMetadata(
                        // Always the module directory (e.g. "en.example"), even when pkgName
                        // is overridden - the publish pipeline derives the icon path from it.
                        module = dirSuffix,
                        theme = dokusho.theme.orNull,
                        packageName = packageName,
                        name = extName,
                        versionCode = code,
                        versionName = name,
                        extensionLib = libVersionValue,
                        contentWarning = contentWarningOrdinal,
                        sources = sourceInfos,
                    ),
                )
            }
            sourceInfoTask.configure { content.set(sourceInfoJsonProvider) }
            tasks.named("assembleRelease").configure { dependsOn(sourceInfoTask) }

            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }
}

private val APPLICATION_ID_SUFFIX_REGEX = Regex("""^\w+(\.\w+)+$""")

private fun computeSourceId(name: String, lang: String, versionId: Int = 1): Long {
    val key = "${name.lowercase()}/$lang/$versionId"
    val bytes = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff }
        .reduce { acc, l -> (acc shl 8) or l } and Long.MAX_VALUE
}

private fun extractHost(url: String): String? = url.split("://").getOrNull(1)?.split("/")?.first()?.takeIf { it.isNotEmpty() }

private fun specsToFilters(specs: List<ExtensionDeeplink>, defaultHosts: List<String>): List<DeeplinkFilter> = specs.mapNotNull { spec ->
    val hosts = spec.hosts.getOrElse(emptyList()).ifEmpty { defaultHosts }
    val paths = spec.pathPatterns.getOrElse(emptyList())
    if (paths.isNotEmpty()) {
        check(hosts.isNotEmpty()) {
            "deeplink has path patterns but no host could be resolved — set a source baseUrl or specify host() explicitly"
        }
        DeeplinkFilter(hosts, paths)
    } else {
        null
    }
}

private fun Project.android(block: ApplicationExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.androidComponents(block: ApplicationAndroidComponentsExtension.() -> Unit) {
    extensions.configure(block)
}
