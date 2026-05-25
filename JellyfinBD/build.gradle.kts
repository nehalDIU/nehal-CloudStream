// use an integer for version numbers
version = 1

cloudstream {
    description = "JellyfinBD Provider"
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
    iconUrl = "https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/png/jellyfin-icon-transparent.png"
}
