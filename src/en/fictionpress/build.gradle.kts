import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "FictionPress"
    theme = "fanfiction"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://www.fictionpress.com"
    }

    deeplink {
        path("/s/..*")
    }
}
