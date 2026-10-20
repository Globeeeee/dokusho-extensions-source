import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Novel Fire"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://novelfire.net"
    }
    deeplink {
        path("/book/..*")
    }
}
