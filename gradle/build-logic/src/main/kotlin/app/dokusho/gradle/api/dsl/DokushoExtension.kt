package app.dokusho.gradle.api.dsl

import app.dokusho.gradle.api.ContentWarning
import app.dokusho.gradle.internal.addDeeplink
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

abstract class DokushoExtension @Inject constructor(
    private val objects: ObjectFactory,
) {
    abstract val name: Property<String>
    abstract val versionCode: Property<Int>
    abstract val contentWarning: Property<ContentWarning>
    abstract val theme: Property<String>
    abstract val libVersion: Property<String>
    abstract val pkgName: Property<String>

    abstract val deeplinks: ListProperty<ExtensionDeeplink>
    abstract val sources: ListProperty<ExtensionSource>

    fun deeplink(block: ExtensionDeeplink.() -> Unit) = addDeeplink(objects, deeplinks, block)

    fun source(block: ExtensionSource.() -> Unit) {
        sources.add(objects.newInstance<ExtensionSource>().apply(block))
    }
}
