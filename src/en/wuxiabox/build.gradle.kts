import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Wuxia Box"
    theme = "readwn"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://www.wuxiabox.com"
    }

    deeplink {
        path("/novel/..*")
    }
}
