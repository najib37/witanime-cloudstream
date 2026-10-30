// Use an integer for version numbers
version = 2

cloudstream {
    description = "WitAnime - أنمي مترجم أون لاين"
    authors = listOf("najib")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    language = "ar"

    iconUrl = "https://www.google.com/s2/favicons?domain=witanime.site&sz=%size%"
}

android {
    namespace = "com.witanime"
}
