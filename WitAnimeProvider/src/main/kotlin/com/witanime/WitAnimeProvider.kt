package com.witanime

import android.util.Log
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
        val tags = doc.select("div.flex-wrap.gap-2 > span.rounded-full")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
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
        val kind: String, // "stream" or "download", which picks the /watch/{kind}-source and -gate endpoints
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
     * Download servers work the same way through /watch/download-source and -gate.
     *
     * The sources endpoints allow 20 requests per window per IP, so servers are resolved
     * one at a time, best hosts first, capped at [MAX_RESOLVES].
     *
     * The sources endpoint 404s a full-version Chrome User-Agent that comes without matching
     * client hints, so every request here sends [BROWSER_HEADERS].
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cookies = mutableMapOf<String, String>()
        val page = app.get(data, headers = BROWSER_HEADERS)
        cookies.putAll(page.cookies)
        val html = page.text

        val csrf = Regex("""name="csrf-token" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val sourcesUrl = Regex("""sourcesUrl:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
        log("watch $data -> ${page.code}, ${html.length} chars, csrf=${csrf != null}, sourcesUrl=$sourcesUrl, cookies=${cookies.keys}")
        if (csrf == null || sourcesUrl == null) return false
        val headers = BROWSER_HEADERS + mapOf(
            "Accept" to "application/json",
            "X-CSRF-TOKEN" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
        )

        val manifestRes = app.post(fixUrl(sourcesUrl), headers = headers, cookies = cookies, referer = data)
        cookies.putAll(manifestRes.cookies)
        log("sources -> ${manifestRes.code}: ${manifestRes.text.take(200)}")
        val manifest = runCatching { JSONObject(manifestRes.text) }.getOrNull() ?: return false
        val sources = manifest.sourcesIn("players", "stream") + manifest.sourcesIn("downloads", "download")

        val streams = sources
            .filter { it.kind == "stream" && it.label !in UNSUPPORTED_HOSTS }
            .sortedBy { rank(QUALITY_ORDER, it.quality) }
            // One ok.ru embed already lists every quality, so its other tokens add nothing.
            .distinctBy { if (it.label == "ok") Triple(it.label, it.version, it.lang) else it }
            .sortedBy { rank(HOST_PRIORITY, it.label) }
        // A download server with the same host and quality as a stream server is the same file
        val streamKeys = streams.map { it.label to it.quality }.toSet()
        val downloads = sources
            .filter { it.kind == "download" && it.label in DOWNLOAD_HOSTS && (it.label to it.quality) !in streamKeys }
            .sortedBy { rank(QUALITY_ORDER, it.quality) }
        val picked = (streams + downloads).take(MAX_RESOLVES)
        log("picked ${picked.map { "${it.kind[0]}:${it.label}/${it.quality}" }} of ${sources.size}")

        val seenEmbeds = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()
        var found = false
        for (source in picked) {
            val resolve = throttled(REQUEST_GAP_MS) {
                app.post(
                    "$mainUrl/watch/${source.kind}-source/${source.token}",
                    headers = headers, cookies = cookies, referer = data
                )
            }
            if (!resolve.isSuccessful) {
                log("${source.label}/${source.quality}: source -> ${resolve.code}")
                // Still limited after the retry: every later request would be too
                if (resolve.code == 429) break
                continue
            }
            cookies.putAll(resolve.cookies)

            // The gate endpoint has its own, separate limit
            val gate = throttled(GATE_GAP_MS) {
                app.get(
                    "$mainUrl/watch/${source.kind}-gate/${source.token}",
                    headers = BROWSER_HEADERS, cookies = cookies, allowRedirects = false
                )
            }
            cookies.putAll(gate.cookies)
            val embed = gate.headers["location"]
                ?: gate.document.selectFirst("iframe[src]")?.attr("src")
            log("${source.label}/${source.quality}: gate -> ${gate.code} $embed")
            if (embed == null) continue
            // Download servers often point at the same mp4upload file as a stream server
            val embedKey = MP4UPLOAD_ID.find(embed)?.groupValues?.get(1) ?: embed
            if (!seenEmbeds.add(embedKey)) continue

            val quality = qualityOf(source.quality)
            var emitted = 0
            val emit: (ExtractorLink) -> Unit = { link ->
                if (seenUrls.add(link.url)) {
                    if (link.quality == Qualities.Unknown.value) link.quality = quality
                    emitted++
                    callback(link)
                }
            }
            runCatching {
                if ("ok.ru/" in embed) extractOkRu(embed, emit)
                else loadExtractor(embed, "$mainUrl/", subtitleCallback, emit)
            }.onFailure { log("${source.label}: ${it.message}") }
            log("${source.label}/${source.quality}: $emitted link(s)")
            found = found || emitted > 0
        }
        return found
    }

    private fun JSONObject.sourcesIn(group: String, kind: String): List<Source> {
        // Empty groups come back as [] rather than {}, so optJSONObject keeps that from throwing.
        val byQuality = optJSONObject(group) ?: return emptyList()
        return byQuality.keys().asSequence().flatMap { quality ->
            val list = byQuality.optJSONArray(quality) ?: return@flatMap emptySequence()
            (0 until list.length()).asSequence().mapNotNull { i ->
                val s = list.optJSONObject(i) ?: return@mapNotNull null
                Source(
                    kind = kind,
                    token = s.optString("token").ifBlank { return@mapNotNull null },
                    label = s.optString("label").lowercase(),
                    quality = quality,
                    version = s.optString("version", "sub"),
                    lang = s.optString("lang", "jp"),
                )
            }
        }.toList()
    }

    /**
     * CloudStream's built-in ok.ru extractor expects backslash-escaped JSON that the embed no
     * longer has, so it finds nothing. The embed's data-options attribute holds a
     * flashvars.metadata object (or JSON string) whose "videos" lists one mp4 per quality.
     */
    private suspend fun extractOkRu(url: String, callback: (ExtractorLink) -> Unit) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val options = app.get(embedUrl, headers = BROWSER_HEADERS).document
            .selectFirst("[data-options]")?.attr("data-options") ?: return
        val flashvars = JSONObject(options).optJSONObject("flashvars") ?: return
        val metadata = flashvars.optJSONObject("metadata")
            ?: flashvars.optString("metadata").takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            ?: return
        val videos = metadata.optJSONArray("videos") ?: return
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val videoUrl = video.optString("url").ifBlank { continue }
                .let { if (it.startsWith("//")) "https:$it" else it }
            callback(
                newExtractorLink("Ok.ru", "Ok.ru", videoUrl, ExtractorLinkType.VIDEO) {
                    referer = "https://ok.ru/"
                    quality = OK_RU_QUALITIES[video.optString("name")] ?: Qualities.Unknown.value
                    headers = mapOf("User-Agent" to BROWSER_HEADERS.getValue("User-Agent"))
                }
            )
        }
    }

    private fun rank(order: List<String>, value: String) = order.indexOf(value).let { if (it < 0) order.size else it }

    private fun log(message: String) = Log.d("WitAnime", message)

    // Besides the per-window limit, the site rejects bursts with 429, so space requests out
    // and give each one a single retry.
    private suspend fun throttled(gapMs: Long, request: suspend () -> NiceResponse): NiceResponse {
        delay(gapMs)
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
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "sec-ch-ua" to "\"Chromium\";v=\"149\", \"Google Chrome\";v=\"149\", \"Not.A/Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
        )
        // 1 manifest request + this many source requests stays under the 20-per-window limit
        private const val MAX_RESOLVES = 12
        private const val REQUEST_GAP_MS = 2500L
        private const val GATE_GAP_MS = 1000L
        private const val RETRY_GAP_MS = 6000L
        private val QUALITY_ORDER = listOf("4K", "FHD", "HD", "SD")
        private val HOST_PRIORITY = listOf("ok", "mp4upload")
        // No working extractor for these: mega, workupload, videas and 4shared have none,
        // yonaplay is itself a server picker, hgcloud only builds its player in JS, and
        // CloudStream's videa extractor returns nothing for these embeds.
        private val UNSUPPORTED_HOSTS = setOf("mega", "workupload", "yonaplay", "videas", "4shared", "hgcloud", "videa")
        // Download servers worth resolving: mediafire serves .zip archives and gofile's API
        // rate-limits guest accounts.
        private val DOWNLOAD_HOSTS = setOf("mp4upload")
        private val MP4UPLOAD_ID = Regex("""mp4upload\.com/(?:embed-)?([a-z0-9]+)""")
        private val OK_RU_QUALITIES = mapOf(
            "mobile" to Qualities.P144.value,
            "lowest" to Qualities.P240.value,
            "low" to Qualities.P360.value,
            "sd" to Qualities.P480.value,
            "hd" to Qualities.P720.value,
            "full" to Qualities.P1080.value,
            "quad" to Qualities.P1440.value,
            "ultra" to Qualities.P2160.value,
        )
    }
}
