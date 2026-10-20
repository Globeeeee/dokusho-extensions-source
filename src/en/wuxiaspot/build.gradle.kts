import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Wuxia Spot"
    theme = "readwn"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://www.wuxiaspot.com"
    }

    deeplink {
        path("/novel/..*")
    }
}
