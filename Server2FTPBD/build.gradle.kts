// use an integer for version numbers
version = 1

cloudstream {
    description = "Server2FTPBD provider"
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

    iconUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAA3klEQVR4AWMYRuCtjIrA72MnHL71TXIAsUnVnADE74EG/Aca8B/IBuF+iEGENStANfxHMgCG3wNxACEDGpAN+Llj9/8/V679//fx4//vc+bDXYPPgP1IBoA1fk7O+P8pNBrM/lJY9v9rfQtIrgGo3AGI5wNxP5Sej24Asq3/f6xaC3IRyBCQ1/YDNYAMeQ/E54EYxF8PMmA+HgPA/PdWju+BBihAXdCAhBNABjigeQHkfLA3QOBrbdN9oJwByLuEwgFkG9jJfx8/ARsEBA2EoxJhSAHUoP3QNKDAMDIAAL5x40updmr+AAAAAElFTkSuQmCC"
}
