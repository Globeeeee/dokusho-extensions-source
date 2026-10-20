package eu.kanade.tachiyomi.extension.en.wuxiaworldsite

import dokusho.annotation.Source
import eu.kanade.tachiyomi.multisrc.madara.Madara

@Source
abstract class WuxiaWorldSite : Madara() {
    override val promoLine = Regex("""^You.re Reading .* at WuxiaWorld\.Site$""", RegexOption.IGNORE_CASE)
}
