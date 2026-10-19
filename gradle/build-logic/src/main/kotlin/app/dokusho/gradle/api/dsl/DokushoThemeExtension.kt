package app.dokusho.gradle.api.dsl

import app.dokusho.gradle.internal.addDeeplink
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class DokushoThemeExtension @Inject constructor(
    private val objects: ObjectFactory,
) {
    abstract val baseVersionCode: Property<Int>
    abstract val libVersion: Property<String>

    abstract val deeplinks: ListProperty<ExtensionDeeplink>

    fun deeplink(block: ExtensionDeeplink.() -> Unit) = addDeeplink(objects, deeplinks, block)
}
