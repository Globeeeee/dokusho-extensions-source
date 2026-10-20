import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Wattpad"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://www.wattpad.com"
    }
    deeplink {
        path("/story/..*")
    }
}
