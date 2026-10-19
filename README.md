# Dokusho extensions source

Source code of the web novel extensions for [Dokusho](https://github.com/Globeeeee/dokusho).
Built with the [Keiyoushi](https://github.com/keiyoushi/extensions-source) extension toolchain.

Published index: [dokusho-extensions](https://github.com/Globeeeee/dokusho-extensions). Add
this in the app under Settings → Browse → Extension repos:

```
https://github.com/Globeeeee/dokusho-extensions/raw/repo/index.pb
```

## Layout

```text
src/<lang>/<name>/     one extension per module
lib-multisrc/<theme>/  shared site themes
lib/                   shared helpers
core/                  KeiSource base class, network and parsing helpers
compiler/              KSP processor that generates the source entry point and manifest
gradle/build-logic/    convention plugins and the keiyoushi {} DSL
```

## Build one extension

```bash
./gradlew :src:en:<name>:assembleDebug
adb install -r src/en/<name>/build/outputs/apk/debug/*.apk
```

Requires JDK 21 and the Android SDK.

## Plan and rules

`docs/PLAN.md` for the site list and phases. `CLAUDE.md` and `.claude/` for agent rules.

## Disclaimer

Dokusho and its extensions are not affiliated with any content provider. Extensions only
read pages the user could open in a browser; sites with accounts use the user's own login.

## License

Apache License 2.0, see [LICENSE](LICENSE).
