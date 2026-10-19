# Dokusho extensions - plan

The extension slice of the master plan in `dokusho/docs/PLAN.md`. This repo builds the
extension APKs; `Globeeeee/dokusho-extensions` (branch `repo`) is where they get published.

Status (2026-10-19): scaffold from keiyoushi/extensions-source `314a0d1b` with manga modules
removed; publish scripts point at the Dokusho repos. Nothing builds a novel yet.

## Phase 1. Toolchain for text (with app phase 1)

- [ ] `:extensions-lib` stub module replaces the JitPack `com.github.keiyoushi:extensions-lib` dependency. It mirrors `dokusho/source-api` exactly: `NovelSource`, `ChapterContent`, `HttpNovelSource`, plus everything Mihon already had.
- [ ] `gradle/build-logic` `Constant.kt`: `VALID_LIB_VERSIONS = listOf("2.0")`; `GenerateManifestTask` emits feature `dokusho.extension` and `dokusho.*` meta-data keys.
- [ ] `core/KeiSource.kt`: `abstract suspend fun getChapterContent(chapter: SChapter): ChapterContent`; `getPageList` defaults to empty.
- [ ] `ext-bootstrap.py` generates a novel template (popular, latest, search, `fetchMangaUpdate`, `getChapterContent`).
- [ ] Hello-world extension `src/en/royalroad` builds, installs in the app, lists chapters, opens one.
- Done when: `./gradlew :src:en:royalroad:assembleDebug` produces an APK the app loads.

## Phase 4. Sources

| Batch | Site | Notes |
|-------|------|-------|
| 1 | royalroad.com | Public, clean HTML, chapter list in one page, author notes in separate divs |
| 1 | scribblehub.com | Public, chapter list paginated via AJAX |
| 2 | shadowslave.space | Fan reader for Shadow Slave, about 3,200 chapters listed |
| 2 | freewebnovel.com, novellive.com, readnovelfull.com, novelhi.com, noveltrust.com | Aggregators; verify live, check for Cloudflare, likely share a theme → `lib-multisrc/madara-novel` or a `novelfull` theme |
| 2 | wuxiaworld.eu | Aggregator, not wuxiaworld.com |
| 3 | webnovel.com | Official home of LOTM and Shadow Slave. WebView login, cookies reused, locked chapters shown locked, never fetched |
| 3 | wuxiaworld.com | Official, API-based, subscription chapters locked unless the account has them |

Candidates came from a web search on 2026-10-19; each needs recon (`/ext-recon`) before
code. Qasim may add or replace sites.

Per source: popular, latest, search · details + full chapter list (follow pagination) ·
`getChapterContent` returns sanitized HTML (strip ads, scripts, watermark spans, "read at"
footers) · `configureClient { rateLimit(...) }` · `getMangaByUrl` for pasted links ·
`baseUrl { mirrors }` for domain-hopping sites.

## Publishing

- [ ] Generate a signing keystore, store it only in GitHub secrets (`SIGNING_KEY` base64, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`) and a `BOT_PAT` with write access to `dokusho-extensions`.
- [ ] First `build_push.yml` run writes `index.pb`, `index.json`, `index.html` to the `repo` branch and uploads APKs and JARs to Releases. `repo.json` there must then carry the real `signingKeyFingerprint`.
- [ ] The app's extension store trusts the index only when APK signatures match `signingKey`.

## Rules of the road

- Bump `versionCode` on every user-visible change. Never change a source `id`; pass `id =` explicitly on renames.
- No paywall or login bypass of any kind. Sources with accounts use the app WebView session.
- No chapter text, cookies or tokens in logs.
