import app.dokusho.gradle.api.ContentWarning

plugins {
    alias(dok.plugins.extension)
}

dokusho {
    name = "Shadow Slave (shadowslave.space)"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "2.0"
    source {
        lang = "en"
        baseUrl = "https://shadowslave.space"
    }
    deeplink {
        path("/chapters/..*")
    }
}
