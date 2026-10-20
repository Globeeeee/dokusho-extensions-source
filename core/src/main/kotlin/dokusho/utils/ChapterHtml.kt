package dokusho.utils

import org.jsoup.nodes.Element

/**
 * Cleans this chapter body in place to the reader's keep-list and returns it, so a source can run
 * a last site-specific pass on the result before calling [Element.html].
 *
 * Removes scripts, styles, frames, forms and hidden (`display:none`, `visibility:hidden`, `hidden`)
 * elements. Keeps `p`, `h1`-`h4`, `em`, `strong`, `b`, `i`, `br`, `hr`, `blockquote`, `img` and
 * ruby markup; other block containers holding only text become `p`, everything else (links, spans,
 * unknown tags) is unwrapped to its contents. Every attribute is stripped except an absolute
 * http(s) `img` src; an image without one is dropped. Paragraphs without text or image are dropped
 * unless [keepBlankParagraphs] is set.
 *
 * Site-specific removals (ads, author notes, watermark lines, unlock gates) belong in the source,
 * before this call. [imageUrl] picks the image address, e.g. a lazy-load `data-src`.
 */
fun Element.sanitizeChapter(
    imageUrl: (Element) -> String? = { it.absUrl("src") },
    keepBlankParagraphs: Boolean = false,
): Element {
    select(REMOVED).remove()
    select(HIDDEN).remove()
    // Deepest first, so a container sees its children already cleaned.
    getAllElements().drop(1).asReversed().forEach { element ->
        val tag = element.normalName()
        when {
            tag == "img" -> {
                val src = imageUrl(element)?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                if (src == null) {
                    element.remove()
                } else {
                    element.clearAttributes()
                    element.attr("src", src)
                }
            }
            tag in KEPT -> element.clearAttributes()
            tag == "h5" || tag == "h6" -> element.clearAttributes().tagName("h4")
            tag in BLOCKS && element.children().none { it.normalName() in KEPT_BLOCKS } ->
                element.clearAttributes().tagName("p")
            else -> element.unwrap()
        }
    }
    if (!keepBlankParagraphs) {
        select("p").filter { it.text().isBlank() && it.selectFirst("img") == null }.forEach { it.remove() }
    }
    return this
}

/** [sanitizeChapter] and return the inner HTML. */
fun Element.sanitizedChapterHtml(
    imageUrl: (Element) -> String? = { it.absUrl("src") },
    keepBlankParagraphs: Boolean = false,
): String = sanitizeChapter(imageUrl, keepBlankParagraphs).html()

private const val REMOVED = "script, style, link, meta, title, template, noscript, iframe, frame, frameset, object, embed, " +
    "ins, form, input, button, select, textarea, svg, math, canvas, video, audio, source, track, map"

private const val HIDDEN = "[hidden], [style~=(?i)display\\s*:\\s*none], [style~=(?i)visibility\\s*:\\s*hidden]"

private val KEPT = setOf(
    "p", "h1", "h2", "h3", "h4", "em", "strong", "b", "i", "br", "hr", "blockquote",
    "ruby", "rb", "rt", "rp",
)

/** Kept tags that start a block; a container holding one of these is unwrapped rather than turned into `p`. */
private val KEPT_BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "hr", "blockquote")

/** Block containers that become a `p` when they hold only text and inline markup. */
private val BLOCKS = setOf(
    "div", "section", "article", "main", "aside", "header", "footer", "nav", "center", "address",
    "li", "dd", "dt", "td", "th", "caption", "figure", "figcaption", "pre", "details", "summary",
)
