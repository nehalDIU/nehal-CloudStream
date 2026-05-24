// use an integer for version numbers
version = 1

cloudstream {
    description = "ServerFTPBD provider"
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

    iconUrl = "https://server3.ftpbd.net/favicon.ico"
}
