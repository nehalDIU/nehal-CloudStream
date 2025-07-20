// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "MOVIEBOX_SECRET_KEY_DEFAULT", "\"YWJjZGVmZ2hpams=\"")
        buildConfigField("String", "MOVIEBOX_SECRET_KEY_ALT", "\"bG1ub3BxcnN0dXZ3eHl6=\"")
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them

    description = "MovieBox Movies and Series Provider"
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

    iconUrl = "https://h5-static.aoneroom.com/oneroomStatic/public/_nuxt/web-logo.apJjVir2.svg" // Replace with your icon URL
}
