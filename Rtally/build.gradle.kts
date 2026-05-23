// use an integer for version numbers
version = 20

android {
    buildFeatures {
        buildConfig = true
    }
}

android {
    namespace = "com.cncverse"
}

android {
    defaultConfig {
        val smartlinkUrl = (project.findProperty("SMARTLINK_URL") as String?)
            ?: System.getenv("SMARTLINK_URL")
            ?: ""
        val moneTag = (project.findProperty("MONE_TAG") as String?)
            ?: System.getenv("MONE_TAG")
            ?: ""

        val escapedSmartlinkUrl = smartlinkUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val escapedMoneTag = moneTag
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        buildConfigField("String", "SMARTLINK_URL", "\"${escapedSmartlinkUrl}\"")
        buildConfigField("String", "MONE_TAG", "\"${escapedMoneTag}\"")
    }
}

cloudstream {
    description = "Movie and TV Series provider"
    authors = listOf("Redowan, NivinCNC")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama"
    )
    language = "ta"

    iconUrl = "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Rtally/icon.png"
}
