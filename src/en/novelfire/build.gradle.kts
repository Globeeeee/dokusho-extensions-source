import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Novel Fire"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://novelphoenix.com"
    }
    deeplink {
        host("novelphoenix.com")
        host("novelfire.net")
        path("/novel/..*")
        path("/book/..*")
    }
}
