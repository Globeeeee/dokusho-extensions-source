package eu.kanade.tachiyomi.extension.en.spacebattles

import dokusho.annotation.Source
import eu.kanade.tachiyomi.multisrc.xenforo.XenForo

@Source
abstract class SpaceBattles : XenForo() {
    override val fictionForumPath = "/forums/creative-writing.18/"
}
