package dokusho.utils

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterHtmlTest {

    private fun clean(html: String): String = Jsoup.parseBodyFragment(html, "https://example.com/novel/1/").body().sanitizedChapterHtml()

    @Test
    fun removesScriptsAndEventAttributes() {
        val html = clean("""<p onclick="steal()" class="x" style="color:red">Text</p><script>alert(1)</script><iframe src="https://ads"></iframe>""")
        assertEquals("<p>Text</p>", html)
    }

    @Test
    fun dropsJavascriptAndDataImages() {
        val html = clean("""<p><img src="javascript:alert(1)"><img src="data:image/png;base64,AAAA">Text</p>""")
        assertFalse(html.contains("<img"))
        assertEquals("<p>Text</p>", html)
    }

    @Test
    fun makesRelativeImageAbsolute() {
        val html = clean("""<p><img src="/images/map.png" alt="map" onerror="x()"></p>""")
        assertEquals("""<p><img src="https://example.com/images/map.png"></p>""", html)
    }

    @Test
    fun usesCustomImageUrl() {
        val body = Jsoup.parseBodyFragment("""<p><img src="/lazy.gif" data-src="/real.png"></p>""", "https://example.com/").body()
        assertEquals("""<p><img src="https://example.com/real.png"></p>""", body.sanitizedChapterHtml(imageUrl = { it.absUrl("data-src") }))
    }

    @Test
    fun removesHiddenElements() {
        val html = clean("""<p>Shown</p><p style="display: none">Stolen from site</p><span style="DISPLAY:NONE">x</span><p hidden>y</p>""")
        assertEquals("<p>Shown</p>", html)
    }

    @Test
    fun unwrapsLinksAndSpans() {
        val html = clean("""<p>Read <a href="https://example.com/next">the <span class="c">next</span> part</a> now</p>""")
        assertEquals("<p>Read the next part now</p>", html)
    }

    @Test
    fun turnsTextDivsIntoParagraphs() {
        val html = clean("""<div class="wrap"><div>One</div><div><em>Two</em></div></div>""")
        assertEquals("<p>One</p>\n<p><em>Two</em></p>", html)
    }

    @Test
    fun dropsEmptyParagraphs() {
        val html = clean("<p>One</p><p> </p><p>&nbsp;</p><p><br></p><p>Two</p>")
        assertEquals("<p>One</p>\n<p>Two</p>", html)
    }

    @Test
    fun keepsBlankParagraphsWhenAsked() {
        val body = Jsoup.parseBodyFragment("<p>One</p><p><br></p><p>Two</p>").body()
        val paragraphs = Jsoup.parseBodyFragment(body.sanitizedChapterHtml(keepBlankParagraphs = true)).select("p")
        assertEquals(listOf("One", "", "Two"), paragraphs.map { it.text() })
        assertEquals(1, paragraphs[1].select("br").size)
    }

    @Test
    fun keepsRuby() {
        val html = clean("""<p><ruby class="r">漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby></p>""")
        assertTrue(html, html.contains("<ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>"))
    }
}
