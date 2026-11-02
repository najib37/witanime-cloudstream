// Use an integer for version numbers
version = 1

cloudstream {
    description = "FoxTrend / StreamEast sports (via foxlink.buzz) — live F1, football, NBA, NFL and more"
    authors = listOf("najib")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    // Live sports streams
    tvTypes = listOf("Live")

    language = "en"

    iconUrl = "https://www.google.com/s2/favicons?domain=foxtrend.app&sz=%size%"
}

android {
    namespace = "com.foxtrend"
}
