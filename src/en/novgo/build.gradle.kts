import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "NOVGO (AllNovelFull)"
    theme = "readnovelfull"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://novgo.net"
    }

    deeplink {
        path("/..*")
    }
}
