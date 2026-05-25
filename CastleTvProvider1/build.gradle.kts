// use an integer for version numbers
version = 13

android {
    namespace = "com.nehal.castletvprovider"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val castleSuffix = (project.findProperty("CASTLE_SUFFIX") as String?)
            ?: System.getenv("CASTLE_SUFFIX")
            ?: "default_suffix"
        buildConfigField("String", "CASTLE_SUFFIX", "\"${castleSuffix}\"")
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them

    description = "Castle TV Movies and Series Provider"
    authors = listOf("Nehal")

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

    iconUrl = "https://raw.githubusercontent.com/nehalDIU/nehal-CloudStream/master/CastleTvProvider/icon.png"
}

