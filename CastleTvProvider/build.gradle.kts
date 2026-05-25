// use an integer for version numbers
version = 15

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
        val speedlinkUrl = (project.findProperty("SPEEDLINK_URL") as String?)
            ?: System.getenv("SPEEDLINK_URL")
            ?: ""
        val castleSuffix = (project.findProperty("CASTLE_SUFFIX") as String?)
            ?: System.getenv("CASTLE_SUFFIX")
            ?: ""

        val escapedSmartlinkUrl = smartlinkUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val escapedSpeedlinkUrl = speedlinkUrl
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val escapedCastleSuffix = castleSuffix
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        buildConfigField("String", "SMARTLINK_URL", "\"${escapedSmartlinkUrl}\"")
        buildConfigField("String", "SPEEDLINK_URL", "\"${escapedSpeedlinkUrl}\"")
        buildConfigField("String", "CASTLE_SUFFIX", "\"${escapedCastleSuffix}\"")
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

