# Extension pack

Read for SOURCE, TOOLCHAIN and BUGFIX work. Only the sections your mode needs.

## Module anatomy

```kotlin
// src/en/royalroad/build.gradle.kts
import app.dokusho.gradle.api.ContentWarning   // required, the DSL does not import it

plugins { alias(dok.plugins.extension) }
dokusho {
    name = "Royal Road"
    versionCode = 1
    contentWarning = ContentWarning.SAFE   // SAFE / MIXED / NSFW
    libVersion = "2.0"
    source { lang = "en"; baseUrl = "https://www.royalroad.com" }
    // theme = "madara-novel"              // when a lib-multisrc theme applies
    // deeplink { path("/fiction/..*") }
}
```

- Kotlin lives in `src/eu/kanade/tachiyomi/extension/<lang>/<name>/` (note: `src`, not `src/main/java`).
- Exactly one `@Source abstract class X : DokushoSource()`. Do not override `lang`; the KSP
  processor injects `name`, `lang`, `id`, `baseUrl`.
- Icons in `res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`. No manifest.
- Source id = first 8 bytes of `MD5("<name lowercase>/<lang>/<versionId>")`. Rename means
  passing the old `id` explicitly in `source { }`.
- Multi-language or multi-domain: several `source { }` blocks, not a `SourceFactory`.

## DokushoSource contract (after phase 1)

| Member | Purpose |
|--------|---------|
| `OkHttpClient.Builder.configureClient()` | add `rateLimit(permits, period)`, interceptors. Cloudflare interceptor is moved after yours automatically |
| `Headers.Builder.configureHeaders()` | Referer, UA, `setRandomUserAgent()` from `lib/randomua` |
| `getPopularManga(page)`, `getLatestUpdates(page)`, `getSearchMangaList(page, query, filters)` | return `MangasPage(list, hasNextPage)` |
| `fetchMangaUpdate(manga, chapters, fetchDetails, fetchChapters)` | details and chapter list in one call; follow pagination; cache site ids in `manga.memo` |
| `getChapterContent(chapter)` | **new in 2.0**: fetch chapter page, return `ChapterContent(html, title)` |
| `getPageList(chapter)` | returns empty for novels |
| `getMangaByUrl(url)` | optional, pasted-link search |
| `getMangaUrl` / `getChapterUrl` | for "open in WebView" |

Helpers: `client.get(url).asJsoup()`, `parseAs<T>()`, `tryParseDate`, `attrOrNull`,
`textOrNull`, `runWebViewBlocking`, `TurnstileHelper.getTurnstileToken`, `addCookie`.

## Content sanitising

Do it in the extension so the app's safelist has little to remove:

```kotlin
private fun Element.toChapterHtml(): String {
    select("script, style, ins, iframe, .ads, [class*=watermark], [id*=google]").remove()
    select("p:matchesOwn(^\\s*$)").remove()
    select("a[href]").unwrap()
    select("img").forEach { it.attr("src", it.absUrl("src")) }
    return html()
}
```

Strip: ads, "read at <site>" footers, author begging lines injected by aggregators, hidden
anti-copy spans (`style*=display:none`, random-class spans with junk text), inline styles.
Keep: `p`, `h1`-`h4`, `em`, `strong`, `br`, `hr`, `blockquote`, `img`.

## Accounts (PRIVACY)

- The app owns the WebView login; cookies land in the shared jar and your `client` sends them.
- Detect a locked chapter from the page (paywall marker, HTTP 401/403, truncated body) and
  return `ChapterContent(html = "", locked = true)` (field added in 2.0). Never fetch the
  text from a mirror or cached copy as a fallback.
- Rate limit paid sites harder (1 request per 2 to 3 s). Set a browser-like UA.

## Themes (`lib-multisrc`)

A theme is `abstract class Theme : DokushoSource()` with open selectors and `open val`s. Children
set `theme = "<name>"` in the DSL and extend the class in a few lines. Bump the theme's
`baseVersionCode` when theme code changes; every child's version code is `base + own`.

## Toolchain (TOOLCHAIN)

- `gradle/build-logic/.../internal/Constant.kt`: `VALID_LIB_VERSIONS`.
- `GenerateManifestTask.kt`: writes `uses-feature dokusho.extension` and the `dokusho.*` meta-data the app loader requires.
- `ExtensionPlugin.kt`: `compileOnly(project(":extensions-lib"))`; the provided-classpath configuration pins the stub's release variant. APKs are named `dokusho-<lang>.<name>-v<version>.apk`.
- `extensions-lib/`: compile-time stubs (vendored from keiyoushi/extensions-lib, Apache 2.0) plus `NovelSource`, `ChapterContent`, `HttpNovelSource`. Must mirror `dokusho/source-api`; change both in the same PR.
- `ext-bootstrap.py`: scaffolds a lib 2.0 novel module (listing, details with chapters, sanitized `getChapterContent`); selectors are placeholders until `/ext-recon` fills them.
- `core/.../DokushoSource.kt`: base class; `getChapterContent` added in phase 1.
- `compiler/.../SourceProcessor.kt`: generates `dokusho.source.Generated`.
- Version code math: Android `versionCode = libDigits * 1000 + versionCode`, `versionName = "$libVersion.$versionCode"`.

## Debugging (BUGFIX)

Reproduce with `curl -sL -A "Mozilla/5.0" <url> | head -c 20000 > $SCRATCH/page.html` and
inspect selectors with grep before touching code. Then:

| Symptom | Look at |
|---------|---------|
| Empty popular or search | selector drift, site moved to JS rendering (`runWebViewBlocking`), Cloudflare (open in WebView once) |
| Chapter list short | pagination not followed, AJAX endpoint changed, chapters sorted wrong |
| Blank chapter | content selector drift, text split across spans, sanitiser removed the container |
| HTTP 403 | missing Referer, UA, or rate limit too high |
| App says "not found / update app" | lib version or manifest keys mismatch with the app loader |

State the root cause in one sentence, fix one selector or one guard, bump `versionCode`.
