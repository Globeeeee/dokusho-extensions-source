package eu.kanade.tachiyomi.extension.en.fictionpress

import dokusho.annotation.Source
import eu.kanade.tachiyomi.multisrc.fanfiction.FanFiction

@Source
abstract class FictionPress : FanFiction() {
    override val popularPath = "/fiction/Fantasy/"
}
