# Dokusho extensions source (Kotlin, Keiyoushi toolchain)

The developer is **Qasim**. Open each reply with his name once, no more.
Scope and site list live in `docs/PLAN.md`. The app lives in `../dokusho/`.

## How to use these rules

1. Read the prompt, pick **one mode** from the table.
2. Apply **Always-on** below on every code change.
3. Read **only** the packs that mode lists, from `.claude/rules/`.
4. Non-trivial work: open with `Mode: SOURCE - ...`.

Priority: PRIVACY > TOOLCHAIN > SOURCE > BUGFIX > QUICK.

| Mode | Prompt signals | Packs to read |
|------|----------------|---------------|
| QUICK | selector tweak, one string, versionCode bump | none |
| EXPLAIN | how/what/why | none, read the code |
| BUGFIX | site changed, empty chapter list, blank chapter, Cloudflare loop | `extension.md` §Debugging |
| SOURCE | new site, add source, scaffold extension, multisrc theme | `extension.md`, `docs/PLAN.md` §Sources, skill `/ext-new` after `/ext-recon` |
| TOOLCHAIN | build-logic, `extensions-lib`, `DokushoSource`, `compiler`, manifest keys, lib version | `extension.md` §Toolchain, `../dokusho/.claude/rules/novel.md` §Source contract |
| PRIVACY | login, cookies, Webnovel, WuxiaWorld, locked chapters | mode packs + `extension.md` §Accounts |
| PUBLISH | release, index, signing, workflow, `repo` branch | `publish.md` |
| DOCS | README, rules, plan | none |

## Always-on (every code change)

- Commits: author is Qasim only. Never add `Co-Authored-By`, "Generated with" or any AI attribution trailer to commits or PRs.

**Ponytail ladder**: YAGNI → a theme in `lib-multisrc/` or a helper in `core/` and `lib/` →
OkHttp, jsoup, kotlinx.serialization already on the classpath → one line → minimum new code.
`docs/upstream/KEIYOUSHI_CONTRIBUTING.md` is the reference for the toolchain; grep it, never read it whole.

Smallest correct change. One source per module. Bump `versionCode` on every user-visible change.
Never change a source's `id`; on rename pass `id =` explicitly. No `// Note:` narration.

**Never cut:** paywall and login boundaries · `rateLimit` on every client · sanitising of
chapter HTML · stable ids · anything asked for.

| Rule | Do |
|------|-----|
| Module shape | `src/<lang>/<name>/build.gradle.kts` + `res/mipmap-*` + one `@Source abstract class X : DokushoSource()`. No manifest, it is generated. |
| Content | `getChapterContent` returns sanitized HTML: paragraphs, headings, emphasis, images with absolute URLs. Strip scripts, ads, watermark spans, "read at" footers. |
| Network | `configureClient { rateLimit(n) }`. Leave Cloudflare to the app interceptor. Core already sets `Referer: $baseUrl/`; override `configureHeaders` only for a different value. |
| Accounts | Login only through the app WebView; cookies come from the shared jar. A locked chapter returns `ChapterContent` with a locked flag, never a fetched copy from elsewhere. |
| Logging | No `println`, no `Log.*`. Extensions do not log. |
| Secrets | Never in source, commits, chat, logs or tool output. Local copies only in `~/.dokusho/signing/`; CI uses GitHub secrets; repos hold `.env.example` with names only. Pass values from files (`--body "$(cat f)"`), never as literals. Never read `.env`, keystores or `~/.dokusho` into context. Policy: `docs/SECRETS.md` in the app repo. |
| Tests | A source change is verified by `assembleDebug` + install in the app + opening a chapter. Say which site page you tested against. |

## Map

| Area | Path |
|------|------|
| Sources | `src/<lang>/<name>/` (empty today) |
| Themes | `lib-multisrc/<theme>/` (empty today) |
| Shared libs | `lib/{i18n,randomua,cryptoaes,synchrony,unpacker,lzstring}` |
| Base class, helpers | `core/src/main/kotlin/dokusho/{source/DokushoSource.kt,network,utils}` |
| Codegen | `compiler/` (KSP, `@Source` → `dokusho.source.Generated`) |
| Build plugins | `gradle/build-logic/src/main/kotlin/` (`ExtensionPlugin`, `GenerateManifestTask`, `internal/Constant.kt`) |
| Versions | `gradle/libs.versions.toml` (okhttp, jsoup, kotlinx.serialization) · `gradle/dok.versions.toml` (SDKs) |
| Stub API | `extensions-lib/` (mirror of `dokusho/source-api`, lib 2.0) |
| Scaffolder | `ext-bootstrap.py` (novel template, run `/ext-recon` first) |
| Publish | `.github/workflows/build_push.yml` · `.github/scripts/publish-repo.py` · `index.proto` |

## Verify

`/ext-ship-check` (scoped). Per module:
`./gradlew :src:<lang>:<name>:assembleDebug :src:<lang>:<name>:lintRelease spotlessCheck`.
Then `adb install -r` the APK from `src/<lang>/<name>/build/outputs/apk/debug/` and test in the app.

## Economy

Delegate searches to `scout`. Fetch a site page once with `curl -sL | head -c 20000` into the
scratchpad and grep it; do not paste whole pages into context. `context7` for jsoup or OkHttp API.
