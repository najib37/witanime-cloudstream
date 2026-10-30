package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WitAnimeProvider : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/new-episodes" to "أحدث الحلقات",
        "$mainUrl/seasonal" to "أنميات الموسم",
        "$mainUrl/browse" to "كل الأنميات",
        "$mainUrl/movies" to "الأفلام",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse>
        val hasNext: Boolean

        when {
            // Alpine pager endpoint: {"html": "...", "page": 1, "hasOlder": true}
            request.data.endsWith("/new-episodes") -> {
                // 404s unless it looks like the page's own XHR
                val json = JSONObject(
                    app.get(
                        "${request.data}?page=$page",
                        headers = mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
                        referer = "$mainUrl/",
                    ).text
                )
                items = Jsoup.parse(json.optString("html")).toSearchResults()
                hasNext = json.optBoolean("hasOlder")
            }
            // Single page listing, no pagination
            request.data.endsWith("/seasonal") -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
                items = app.get(request.data).document.toSearchResults()
                hasNext = false
            }
            // Paginated as /browse, /browse/page/2, ...
            else -> {
                val url = if (page == 1) request.data else "${request.data}/page/$page"
                val doc = app.get(url).document
                items = doc.toSearchResults()
                hasNext = doc.selectFirst("a[href$=/page/${page + 1}]") != null
            }
        }

        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        return app.get("$mainUrl/search?q=$q").document.toSearchResults()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.toSearchResults(): List<SearchResponse> =
        select("a.group:has(h3)").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val title = selectFirst("h3")?.text()?.trim()?.ifBlank { null } ?: return null
        val poster = selectFirst("img")?.attr("src")

        // Episode cards link to /watch/{slug}/{n} or /watch/movie/{slug}; point them at the show page.
        val url = when {
            "/watch/movie/" in href -> href.replace("/watch/movie/", "/movie/")
            "/watch/" in href -> href.substringBeforeLast("/").replace("/watch/", "/anime/")
            else -> href
        }
        val type = select("div").map { it.ownText().trim() }.firstNotNullOfOrNull { typeFromBadge(it) }
            ?: if ("/movie/" in url) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, url, type) {
            posterUrl = poster
        }
    }

    private fun typeFromBadge(text: String): TvType? = when (text.uppercase()) {
        "TV", "ONA", "SPECIAL" -> TvType.Anime
        "OVA" -> TvType.OVA
        "MOVIE" -> TvType.AnimeMovie
        else -> null
    }

    override suspend fun load(url: String): LoadResponse {
        val showUrl = when {
            "/watch/movie/" in url -> url.replace("/watch/movie/", "/movie/")
            "/watch/" in url -> url.substringBeforeLast("/").replace("/watch/", "/anime/")
            else -> url
        }
        val doc = app.get(showUrl).document

        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = doc.selectFirst("img[src*=/posters/]")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val banner = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { "/banners/" in it }
        val description = doc.selectFirst("p.leading-relaxed")?.text()
        val tags = doc.select("span.rounded-full").map { it.text().trim() }.filter { it.isNotBlank() }
        val info = doc.infoGrid()
        val year = info["السنة"]?.toIntOrNull()
        val duration = info["المدة"]?.filter { it.isDigit() }?.toIntOrNull()
        val badges = doc.select("span.rounded-md").map { it.text().trim() }
        val status = when {
            badges.any { it == "مستمر" } -> ShowStatus.Ongoing
            badges.any { it == "مكتمل" || it == "منتهي" } -> ShowStatus.Completed
            else -> null
        }
        val trailer = Regex("""youtube(?:-nocookie)?\.com/embed/([\w-]{11})""")
            .find(doc.html())?.groupValues?.get(1)
            ?.let { "https://www.youtube.com/watch?v=$it" }
        val recommendations = doc.select("a[data-preview]").mapNotNull { it.toSearchResult() }
            .filter { it.url != showUrl }
            .distinctBy { it.url }

        if ("/movie/" in showUrl) {
            val watchUrl = showUrl.replace("/movie/", "/watch/movie/")
            return newMovieLoadResponse(title, showUrl, TvType.AnimeMovie, watchUrl) {
                posterUrl = poster
                backgroundPosterUrl = banner
                plot = description
                this.tags = tags
                this.year = year
                this.duration = duration
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }

        val slug = showUrl.trimEnd('/').substringAfterLast("/")
        val episodes = doc.select("a[href*=/watch/$slug/]")
            .map { it.attr("href") }
            .distinct()
            .map { href ->
                newEpisode(href) {
                    episode = href.substringAfterLast("/").toIntOrNull()
                }
            }
            .sortedBy { it.episode }

        val type = badges.firstNotNullOfOrNull { typeFromBadge(it) } ?: TvType.Anime
        return newAnimeLoadResponse(title, showUrl, type) {
            posterUrl = poster
            backgroundPosterUrl = banner
            plot = description
            this.tags = tags
            this.year = year
            this.duration = duration
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
            addTrailer(trailer)
        }
    }

    // The details grid is a list of <div><span>label</span><p>value</p></div>
    private fun Document.infoGrid(): Map<String, String> =
        select("div > span.text-neutral-400 + p").associate {
            it.previousElementSibling()!!.text().trim() to it.text().trim()
        }

    private data class Source(
        val token: String,
        val label: String,
        val quality: String,
        val version: String,
        val lang: String,
    )

    /**
     * The watch page holds a `sourcesUrl` that returns a manifest of opaque server tokens.
     * Each token is turned into an embed URL by POSTing it to /watch/stream-source/{token}
     * and then reading the redirect from /watch/stream-gate/{token} (requested without a
     * Referer, matching the page's no-referrer iframe). Every step needs the same session.
     *
     * The site allows ~20 requests per window per IP, so servers are resolved one at a
     * time, best known hosts first, capped at [MAX_SERVERS].
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cookies = mutableMapOf<String, String>()
        val page = app.get(data)
        cookies.putAll(page.cookies)
        val html = page.text

        val csrf = Regex("""name="csrf-token" content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return false
        val sourcesUrl = Regex("""sourcesUrl:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return false
        val headers = mapOf(
            "Accept" to "application/json",
            "X-CSRF-TOKEN" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
        )

        val manifestRes = app.post(fixUrl(sourcesUrl), headers = headers, cookies = cookies, referer = data)
        cookies.putAll(manifestRes.cookies)
        // Empty groups come back as [] rather than {}, so optJSONObject keeps that from throwing.
        val players = JSONObject(manifestRes.text).optJSONObject("players") ?: return false

        val sources = mutableListOf<Source>()
        for (quality in players.keys()) {
            val list = players.optJSONArray(quality) ?: continue
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                sources += Source(
                    token = s.optString("token"),
                    label = s.optString("label").lowercase(),
                    quality = quality,
                    version = s.optString("version", "sub"),
                    lang = s.optString("lang", "jp"),
                )
            }
        }

        // One entry per host/version/lang, keeping its best quality, best hosts first.
        val picked = sources
            .filter { it.label !in UNSUPPORTED_HOSTS && it.token.isNotBlank() }
            .sortedBy { QUALITY_ORDER.indexOf(it.quality).let { i -> if (i < 0) QUALITY_ORDER.size else i } }
            .distinctBy { Triple(it.label, it.version, it.lang) }
            .sortedBy { HOST_PRIORITY.indexOf(it.label).let { i -> if (i < 0) HOST_PRIORITY.size else i } }
            .take(MAX_SERVERS)

        var found = false
        for (source in picked) {
            val resolve = throttled {
                app.post(
                    "$mainUrl/watch/stream-source/${source.token}",
                    headers = headers, cookies = cookies, referer = data
                )
            }
            if (!resolve.isSuccessful) continue
            cookies.putAll(resolve.cookies)

            val gate = throttled {
                app.get(
                    "$mainUrl/watch/stream-gate/${source.token}",
                    cookies = cookies, allowRedirects = false
                )
            }
            if (gate.code !in 200..399) continue
            cookies.putAll(gate.cookies)
            val embed = gate.headers["location"]
                ?: gate.document.selectFirst("iframe[src]")?.attr("src")
                ?: continue

            val quality = qualityOf(source.quality)
            found = loadExtractor(embed, "$mainUrl/", subtitleCallback) { link ->
                if (link.quality == Qualities.Unknown.value) link.quality = quality
                callback(link)
            } || found
        }
        return found
    }

    // Besides the per-window limit, the site rejects bursts with 429, so space requests out
    // and give each one a single retry.
    private suspend fun throttled(request: suspend () -> NiceResponse): NiceResponse {
        delay(REQUEST_GAP_MS)
        val res = request()
        if (res.code != 429) return res
        delay(RETRY_GAP_MS)
        return request()
    }

    private fun qualityOf(bucket: String): Int = when (bucket.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FHD" -> Qualities.P1080.value
        "HD" -> Qualities.P720.value
        "SD" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    companion object {
        private const val MAX_SERVERS = 5
        private const val REQUEST_GAP_MS = 2500L
        private const val RETRY_GAP_MS = 6000L
        private val QUALITY_ORDER = listOf("4K", "FHD", "HD", "SD")
        private val HOST_PRIORITY = listOf("ok", "mp4upload", "videa", "hgcloud")
        // No CloudStream extractor exists for these (yonaplay is itself a server picker)
        private val UNSUPPORTED_HOSTS = setOf("mega", "workupload", "yonaplay", "videas")
    }
}
