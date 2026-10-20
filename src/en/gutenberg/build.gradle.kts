import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Project Gutenberg"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://www.gutenberg.org"
    }
    deeplink {
        path("/ebooks/..*")
    }
}
