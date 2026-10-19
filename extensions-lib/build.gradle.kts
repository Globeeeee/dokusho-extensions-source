// Compile-time stubs of the Dokusho extension API. The app provides the real classes at runtime.
// Vendored from keiyoushi/extensions-lib 42255ee5fa (Apache 2.0, see LICENSE in this folder),
// plus the lib 2.0 text API: NovelSource, ChapterContent, HttpNovelSource.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(dok.plugins.android.base)
    alias(dok.plugins.spotless)
}

android {
    namespace = "eu.kanade.tachiyomi.extensions"
}

dependencies {
    compileOnly(libs.bundles.common)
}
