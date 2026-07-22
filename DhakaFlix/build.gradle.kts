// use an integer for version numbers
version = 1

cloudstream {
    description = "DhakaFlix (BDIX) provider"
    authors = listOf("Nehal")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "bn"

    iconUrl = "https://salamonline.com.bd/wp-content/uploads/2026/01/salamonline-Custom.png"
}
