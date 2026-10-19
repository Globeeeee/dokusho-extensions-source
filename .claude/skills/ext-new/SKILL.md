---
name: ext-new
description: Scaffold and implement a new Dokusho novel extension from a recon note - module, DSL, DokushoSource class with listing, details, chapters and getChapterContent, icons, build and install. Use when Qasim says add source, new extension, support <site>, scaffold extension.
---

# New extension

Rules: `CLAUDE.md` always-on, `.claude/rules/extension.md`. Needs `docs/recon/<domain>.md`
from `/ext-recon`; if missing, run recon first.

## Do

1. Decide shape from the recon: standalone module, child of an existing theme, or new theme
   (only when a second domain shares the markup).
2. Scaffold: `python3 ext-bootstrap.py --help` then run it for `src/<lang>/<name>`; fix what
   the template gets wrong rather than hand-writing the module.
3. `build.gradle.kts`: `name`, `versionCode = 1`, `contentWarning`, `libVersion = "2.0"`,
   `source { lang; baseUrl }`, `deeplink` paths if the site has stable URLs.
4. Class: `@Source abstract class <Name> : DokushoSource()` implementing, in this order:
   `configureClient` (rateLimit) → `configureHeaders` → popular → latest → search →
   `fetchMangaUpdate` (details + full chapter list, follow pagination, `chapter_number`,
   `date_upload` via `tryParseDate`) → `getChapterContent` (sanitize per the pack) →
   `getMangaUrl` / `getChapterUrl` → `getMangaByUrl` if URLs are stable.
5. Icons: 5 mipmap PNGs. A plain rounded square with the site initial is fine for now.
6. `./gradlew :src:<lang>:<name>:assembleDebug spotlessApply`, install, test in the app:
   popular loads · search finds a known title · book shows full chapter count · first and
   last chapter open · content has no ads or junk.
7. Add the site row to `docs/PLAN.md` §Sources if missing. Then `/ext-ship-check`.

Report: module path · shape · what was tested against which pages · known gaps.

## Do not

- Copy a Keiyoushi manga extension wholesale; only the pattern
- Implement `getPageList` with images for a novel site
- Fetch locked or paid chapters by any route
- Change a source id, or reuse a module name that already shipped
- Add a lib dependency the site does not need
