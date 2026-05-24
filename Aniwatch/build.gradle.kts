// use an integer for version numbers
version = 1

cloudstream {
    description = "Anime from aniwatch.co.at"
    authors = listOf("Nehal")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Anime")
    language = "en"

    iconUrl = "https://aniwatch.co.at/favicon.ico"
    isCrossPlatform = true
}
