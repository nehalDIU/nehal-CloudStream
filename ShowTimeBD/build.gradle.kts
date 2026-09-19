// use an integer for version numbers
version = 2

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "ShowTime BD provider"
    authors = listOf("Nehal")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "bn"

    iconUrl = "http://10.100.100.10/kachajal/images/show-time.png"
}
