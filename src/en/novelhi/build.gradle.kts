import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "NovelHi"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://novelhi.com"
    }
    deeplink {
        path("/s/..*")
        path("/novel/..*")
    }
}
