package eu.kanade.tachiyomi.extension.en.fanfictionnet

import dokusho.annotation.Source
import eu.kanade.tachiyomi.multisrc.fanfiction.FanFiction

@Source
abstract class FanFictionNet : FanFiction() {
    override val popularPath = "/book/Harry-Potter/"
}
