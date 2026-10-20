import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "FanFiction.Net"
    theme = "fanfiction"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "2.0"

    source {
        lang = "en"
        baseUrl = "https://www.fanfiction.net"
    }

    deeplink {
        path("/s/..*")
    }
}
