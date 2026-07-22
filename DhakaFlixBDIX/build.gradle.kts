// use an integer for version numbers
version = 1

cloudstream {
    description = "DhakaFlix (BDIX) provider covering 172.16.50.12, 172.16.50.14, and 172.16.50.7"
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
        "TvSeries",
        "Anime"
    )
    language = "bn"

    iconUrl = "https://salamonline.com.bd/wp-content/uploads/2026/01/salamonline-Custom.png"
}
