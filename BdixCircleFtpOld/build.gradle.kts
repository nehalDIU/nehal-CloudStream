// use an integer for version numbers
version = 2

android {
    namespace = "com.nehal.circleftpold"
}

cloudstream {
    description = "Circle FTP Old server (main.circleftp.net). Only works on BDIX in Bangladesh."
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

    iconUrl = "http://main.circleftp.net/wp-content/uploads/2019/01/Circle-logo-768x168-1.png"
}
