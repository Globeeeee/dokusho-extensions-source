import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Scribble Hub"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://www.scribblehub.com"
    }
    deeplink {
        path("/series/..*")
    }
}
