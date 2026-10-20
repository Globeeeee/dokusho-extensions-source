import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Sufficient Velocity"
    theme = "xenforo"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://forums.sufficientvelocity.com"
    }

    deeplink {
        path("/threads/..*")
    }
}
