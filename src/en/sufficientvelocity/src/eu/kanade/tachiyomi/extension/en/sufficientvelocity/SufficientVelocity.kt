package eu.kanade.tachiyomi.extension.en.sufficientvelocity

import dokusho.annotation.Source
import eu.kanade.tachiyomi.multisrc.xenforo.XenForo

@Source
abstract class SufficientVelocity : XenForo() {
    override val fictionForumPath = "/forums/user-fiction.2/"
}
