import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "NovelLive"
    theme = "readnovelfull"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://novellive.com"
    }

    deeplink {
        host("novellive.com")
        host("novellive.app")
        path("/book/..*")
    }
}
