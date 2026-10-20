import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "NovelPhoenix"
    theme = "readnovelfull"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://novelphoenix.net"
    }

    deeplink {
        path("/..*")
    }
}
