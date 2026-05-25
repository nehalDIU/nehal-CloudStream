// use an integer for version numbers
version = 13

android {
    namespace = "com.cncverse"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val castleSuffix = (project.findProperty("CASTLE_SUFFIX") as String?)
            ?: System.getenv("CASTLE_SUFFIX")
            ?: "default_suffix"
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

        buildConfigField("String", "CASTLE_SUFFIX", "\"${castleSuffix}\"")
        buildConfigField("String", "SMARTLINK_URL", "\"${escapedSmartlinkUrl}\"")
        buildConfigField("String", "MONE_TAG", "\"${escapedMoneTag}\"")
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them

    description = "Castle TV Movies and Series Provider"
    authors = listOf("CNCVerse")

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
        "TvSeries"
    )

    iconUrl = "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/CastleTvProvider/icon.png"
}

