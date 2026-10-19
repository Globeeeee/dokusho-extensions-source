# Dokusho extensions source

Source code of the web novel extensions for [Dokusho](https://github.com/Globeeeee/dokusho),
an Android novel reader built on Mihon. Each extension teaches the app how to list, search and
read one site. Built with the [Keiyoushi](https://github.com/keiyoushi/extensions-source)
extension toolchain.

## Status

No extensions yet. The toolchain is in place; the first sources (RoyalRoad, ScribbleHub) land
with app phase 1. Site list and order: [docs/PLAN.md](docs/PLAN.md).

## The three repos

| Repo | Role |
|------|------|
| [dokusho](https://github.com/Globeeeee/dokusho) | the app |
| [dokusho-extensions-source](https://github.com/Globeeeee/dokusho-extensions-source) | extension source code (this repo) |
| [dokusho-extensions](https://github.com/Globeeeee/dokusho-extensions) | the published index, built from here by CI |

Add this URL in the app under Settings → Browse → Extension repos:

```
https://github.com/Globeeeee/dokusho-extensions/raw/repo/index.pb
```

## Layout

```text
src/<lang>/<name>/     one extension per module (build.gradle.kts, icons, one Kotlin class)
lib-multisrc/<theme>/  shared themes for sites that run the same software
lib/                   shared helpers (user agents, crypto, JS unpacking)
core/                  DokushoSource base class, network and parsing helpers
compiler/              KSP processor that generates each extension's entry point and manifest
gradle/build-logic/    convention plugins and the dokusho {} DSL
docs/upstream/         Keiyoushi's original contributing guide, kept as reference
```

## Build one extension

Requires JDK 21 and the Android SDK.

```bash
./gradlew :src:en:<name>:assembleDebug
adb install -r src/en/<name>/build/outputs/apk/debug/*.apk
```

## Writing an extension

An extension is a Kotlin class extending `DokushoSource` that implements popular, latest, search,
details with chapter list, and `getChapterContent`, which returns the chapter as clean HTML.
Rules that every source follows:

- Rate limit every client. Leave Cloudflare handling to the app.
- Return sanitized chapter HTML: paragraphs and headings, no ads, scripts or watermark text.
- Sites with accounts use the login the user did in the app. Locked chapters stay locked.
- Bump `versionCode` on every change. Never change a source `id`.

## Publishing

Pushes to `main` build changed modules, sign them, upload APKs to the index repo's Releases
and rewrite its `index.pb`. Needs the signing secrets described in `.claude/rules/publish.md`.

## Requests

Open an issue with the site URL and one novel page on it.

## Disclaimer

Dokusho and its extensions are not affiliated with any site. Extensions only read pages the
user could open in a browser.

## License

Apache License 2.0, see [LICENSE](LICENSE).
