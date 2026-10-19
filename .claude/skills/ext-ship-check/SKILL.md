---
name: ext-ship-check
description: Scoped gate for extension modules - spotless, assembleDebug, lintRelease for the touched module, theme, lib or core, plus the versionCode and id checklist. Use when finishing SOURCE, TOOLCHAIN or BUGFIX work, or when Qasim says ship-check, verify, ready to merge.
---

# Ship check

## Do

1. `git status --porcelain` plus files edited this session. Map each to a module:
   `src/<lang>/<name>` → `:src:<lang>:<name>`, `lib-multisrc/<t>` → `:lib-multisrc:<t>`,
   `lib/<l>` → `:lib:<l>`, `core/` → `:core`, `compiler/` → `:compiler`, `gradle/build-logic` → everything.
2. Run only the matching rows:

| Touched | Command |
|---------|---------|
| Any Kotlin | `./gradlew spotlessCheck` (then `spotlessApply` and re-check if needed) |
| A source module | `./gradlew :src:<lang>:<name>:assembleDebug :src:<lang>:<name>:lintRelease` |
| A theme | the theme's `lintRelease` plus `assembleDebug` of every child |
| `core/`, `compiler/`, `lib/*` | their `lintRelease` plus `assembleDebug` of one source that uses them |
| `gradle/build-logic` or `extensions-lib` | `./gradlew :core:assembleRelease` and one source module `assembleDebug` |
| Always | `grep -rn "println(" --include=*.kt src lib lib-multisrc core` clean |

3. Checklist on the diff: `versionCode` bumped for every changed source · `baseVersionCode`
   bumped if theme code changed · no `id` changed · `contentWarning` present.
4. Install the APK and open one chapter when a source changed. Name the page tested.
5. Report: what ran · pass or fail · checklist · leftover risk (site may change, Cloudflare).

## Do not

- Build every module for a one-source change
- Skip the device install for a selector change; analyze cannot see a blank chapter
- Upload or commit `signingkey.jks` or locally signed release APKs
