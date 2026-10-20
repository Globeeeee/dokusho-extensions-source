import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "LightNovelHeaven"
    theme = "madara"
    versionCode = 3
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://lightnovelheaven.com"
    }

    deeplink {
        path("/series/..*")
    }
}
