import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "FreeWebNovel"
    theme = "readnovelfull"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://freewebnovel.com"
    }

    deeplink {
        path("/novel/..*")
    }
}
