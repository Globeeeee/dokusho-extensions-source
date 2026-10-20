package eu.kanade.tachiyomi.extension.en.lightnovelheaven

import dokusho.annotation.Source
import eu.kanade.tachiyomi.multisrc.madara.Madara

@Source
abstract class LightNovelHeaven : Madara() {
    override val novelPath = "series"
}
