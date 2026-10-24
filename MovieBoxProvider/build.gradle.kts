// use an integer for version numbers
version = 39

android {
    namespace = "com.MovieBox"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "MovieBox - Movies, TV Series, Anime & Live Sports with Customizable Sections"
    authors = listOf("nehal")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Live"
    )
    requiresResources = true
}
