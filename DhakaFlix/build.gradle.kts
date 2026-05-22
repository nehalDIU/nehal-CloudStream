// use an integer for version numbers
version = 1

cloudstream {
    description = "DhakaFlix provider"
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

    iconUrl = "http://172.16.50.12/favicon.ico"
}
