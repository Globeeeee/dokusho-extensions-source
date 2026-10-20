import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Syosetu"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "2.0"
    source {
        lang = "ja"
        baseUrl = "https://ncode.syosetu.com"
    }
    deeplink {
        path("/n..*")
    }
}
