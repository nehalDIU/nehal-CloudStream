// use an integer for version numbers
version = 24

android {
    namespace = "com.nehal"
}

cloudstream {
    description = "Only works in Bangladesh. Works even in internet Shutdown"
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
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "OVA",
        "Cartoon",
        "AsianDrama",
        "Others",
        "Documentary",
    )
    language = "bn"

    iconUrl = "https://github.com/nehalDIU/nehal-CloudStream/blob/master/BdixICCFtp/BdixICCFtp.png"
}
