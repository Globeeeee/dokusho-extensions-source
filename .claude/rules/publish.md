# Publish pack

Read for PUBLISH work.

## Pipeline (`.github/workflows/build_push.yml`)

1. Prepare: `find-base-sha.py` finds the last green commit, `generate-build-matrices.py`
   lists changed modules in chunks of 80.
2. Build chunks: decode `SIGNING_KEY` into `signingkey.jks`, `./gradlew <module>:assembleRelease`
   per module. Outputs: signed APK, shrunk signed JAR, `build/dokusho-source-info.json`.
3. Publish: checkout `Globeeeee/dokusho-extensions` branch `repo` with `BOT_PAT`, run
   `publish-repo.py`, which uploads APKs and JARs to GitHub Releases of the index repo and
   rewrites `index.json`, `index.pb` (gzipped protobuf, schema `index.proto`), `index.html`.

Secrets to set on this repo: `SIGNING_KEY` (base64 of the keystore), `ALIAS`,
`KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `BOT_PAT` (classic token, `repo` scope, write to the
index repo). Repository variable (not secret): `SIGNING_KEY_FINGERPRINT`, the SHA-256 of the
signing cert in lowercase hex; `publish-repo.py` refuses to run without it.

Until a source module exists the matrix is empty, so the build and publish jobs are skipped
and only "Populate Gradle cache" runs (`:core` and the libs). Create the keystore locally once with `keytool -genkeypair -keyalg RSA -keysize 4096
-validity 10000`, base64 it into the secret, then delete the local file.

Guards in the workflows read `github.repository == 'Globeeeee/dokusho-extensions-source'`.

## Index repo (`dokusho-extensions`, branch `repo`)

| File | Who writes it |
|------|---------------|
| `index.pb`, `index.json`, `index.html` | `publish-repo.py` |
| `repo.json` | hand-maintained once: `index_v2` URL and `signingKeyFingerprint` = SHA-256 of the signing cert, lowercase hex, no colons |
| `index.min.json` | legacy stub, leave as `[]` |
| `icon/` | fallback only; real icons come from jsDelivr `cdn.jsdelivr.net/gh/Globeeeee/dokusho-extensions-source@main/src/<lang>/<name>/res/mipmap-xhdpi/ic_launcher.png` |

Users add `https://github.com/Globeeeee/dokusho-extensions/raw/repo/index.pb`.
The app only installs extensions whose APK signature matches the index `signingKey`.

Fingerprint of a keystore cert:
`keytool -list -v -keystore signingkey.jks -alias <alias> | grep SHA256`, strip colons, lowercase.

## Local release build

`./gradlew :src:<lang>:<name>:assembleRelease` signs with the debug key when `signingkey.jks`
is missing. Those APKs install for testing but will not match the published index; never
upload them.

## Checklist before merging a source PR

- `versionCode` bumped, `baseVersionCode` bumped if a theme changed
- `id` kept on renames
- `contentWarning` set
- built, installed, chapter opened, which page tested named in the PR
- no `println`, no secrets, no paywall shortcuts
