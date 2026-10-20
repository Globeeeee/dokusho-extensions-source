# Dokusho extensions - plan

The extension slice of the master plan in `dokusho/docs/PLAN.md`. This repo builds the
extension APKs; `Globeeeee/dokusho-extensions` (branch `repo`) is where they get published.

Status (2026-10-20): phase 1 toolchain done. Royal Road 2.0.1 and Scribble Hub 2.0.1 are built, signed and
published to the store index. Webnovel and WuxiaWorld (with login) are next. Cloudflare blocks curl recon on Royal Road and Scribble
Hub; use headless Chrome `--dump-dom` instead.

## Phase 1. Toolchain for text (with app phase 1)

- [x] `:extensions-lib` stub module replaces the JitPack `com.github.keiyoushi:extensions-lib` dependency. It mirrors `dokusho/source-api` exactly: `NovelSource`, `ChapterContent`, `HttpNovelSource`, plus everything Mihon already had.
- [x] `gradle/build-logic` `Constant.kt`: `VALID_LIB_VERSIONS = listOf("2.0")`; `GenerateManifestTask` emits feature `dokusho.extension` and `dokusho.*` meta-data keys.
- [x] `core/DokushoSource.kt`: `abstract suspend fun getChapterContent(chapter: SChapter): ChapterContent`; `getPageList` defaults to empty.
- [x] `ext-bootstrap.py` generates a novel template (popular, latest, search, `fetchMangaUpdate`, `getChapterContent`); verified by generating and building a throwaway module.
- [x] `src/en/royalroad` builds (APK `dokusho-en.royalroad-v2.0.1.apk`); selectors checked against live pages (listing cards, `window.chapters` JSON with `isUnlocked`, chapter body, hidden anti-copy class). Install and in-app reading verified once the reader lands.
- [x] Release builds work in CI: the provided-classpath configuration pins the stub's release variant
- [x] APKs named `dokusho-<lang>.<name>-v<version>.apk`
- [x] Docs (README, `CLAUDE.md`, `.claude/rules/extension.md`) describe the local stub, the `ContentWarning` import and the scaffolder
- [ ] Done when: the APK loads in the app. Built and listed in the store; loading to be confirmed on app v0.1.1.

## Phase 4. Sources

| Batch | Site | Status | Notes |
|-------|------|--------|-------|
| 1 | royalroad.com | ✅ published 2.0.1 | Listing `.fiction-list-item`; chapters from `window.chapters` JSON with `isUnlocked`; body `.chapter-inner.chapter-content`; hidden anti-copy paragraph removed via its `display:none` class |
| 1 | scribblehub.com | ✅ built 2.0.1 | Cards `.search_main_box`; search through Series Finder (`/series-finder/?sf=1&sh=`), the `?s=` search is a 404; full chapter list from `admin-ajax.php` `wi_getreleases_pagination` with `pagenum=-1`, falling back to `?toc=N` pages; body `#chp_raw`, author notes dropped; tables flatten to text for now |
| 3 | webnovel.com | ⬜ planned | Official home of LOTM and Shadow Slave. WebView login, cookies reused, locked chapters shown locked, never fetched |
| 3 | wuxiaworld.com | ⬜ planned | Official, API-based, subscription chapters locked unless the account has them |

Not building (decision 2026-10-20): shadowslave.space, freewebnovel.com, novellive.com,
readnovelfull.com, novelhi.com, noveltrust.com, wuxiaworld.eu and similar sites that republish
paid chapters without permission. New sites go through `/ext-recon` first.

Per source: popular, latest, search · details + full chapter list (follow pagination) ·
`getChapterContent` returns sanitized HTML (strip ads, scripts, watermark spans, "read at"
footers) · `configureClient { rateLimit(...) }` · `getMangaByUrl` for pasted links ·
`baseUrl { mirrors }` for domain-hopping sites.

## Publishing

- [x] Signing keystore `~/.dokusho/signing/dokusho-extensions.jks` (PKCS12, one password: `KEY_PASSWORD` equals `KEY_STORE_PASSWORD`); secrets `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `BOT_PAT`; variable `SIGNING_KEY_FINGERPRINT`
- [x] `build_push.yml` writes `index.pb`, `index.json`, `index.html` to the `repo` branch and uploads APKs and JARs to Releases; `repo.json` and the index carry the real fingerprint
- [x] The app lists the published extension from `index.pb` (Royal Road seen on v0.1.0)
- [x] CI matrix and cache job work with an empty or partial `src/` and `lib-multisrc/`
- [ ] Replace `BOT_PAT` (currently the `gh` login token) with a fine-grained token scoped to `dokusho-extensions`, Contents read and write

## Rules of the road

- Bump `versionCode` on every user-visible change. Never change a source `id`; pass `id =` explicitly on renames.
- No paywall or login bypass of any kind. Sources with accounts use the app WebView session.
- No chapter text, cookies or tokens in logs.
