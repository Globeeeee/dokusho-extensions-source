import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "WuxiaWorld"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://www.wuxiaworld.com"
    }
    deeplink {
        path("/novel/..*")
    }
}
