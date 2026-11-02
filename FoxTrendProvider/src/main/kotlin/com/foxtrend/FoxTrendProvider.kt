package com.foxtrend

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * FoxTrend / StreamEast live sports, reached through the always-updated link hub
 * at [HUB] (foxlink.buzz).
 *
 * Layout of the network:
 *   - foxlink.buzz           the hub; lists the current FoxTrend landing domain
 *   - foxtrend.<tld>         the schedule: one page per sport (/F1, /NBA, ...), plus /search
 *   - .../game/<slug>        a team game's own page, linking to its event page
 *   - foxiy.<tld>/event/<slug>  the player page: an <iframe> per server
 *   - <embed host>/embed/<id>   the embed; builds the real stream URL in JS + WASM
 *
 * The embed hides its HLS behind an obfuscated WASM step, so the stream URL can't be
 * rebuilt from Kotlin. Instead [loadLinks] loads each embed in a headless WebView
 * ([WebViewResolver]), which runs the JS/WASM and lets us intercept the `.m3u8` it
 * ends up requesting. The schedule host rotates, so it is resolved from the hub at
 * runtime and cached for the session, falling back to [DEFAULT_BASE].
 */
class FoxTrendProvider : MainAPI() {
    override var mainUrl = DEFAULT_BASE
    override var name = "FoxTrend"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    // F1 first, since that is what the provider is here for; the rest share the same layout.
    override val mainPage = mainPageOf(
        "F1" to "🏎 F1 & Motor Racing",
        "247" to "📺 24/7 Channels",
        "Soccer" to "⚽ Soccer",
        "NBA" to "🏀 NBA",
        "NFL" to "🏈 NFL",
        "MLB" to "⚾ MLB",
        "NHL" to "🏒 NHL",
        "UFC" to "🥋 UFC",
        "Boxing" to "🥊 Boxing",
        "Tennis" to "🎾 Tennis",
        "Cricket" to "🏏 Cricket",
        "Rugby" to "🏉 Rugby",
        "Golf" to "⛳ Golf",
        "Darts" to "🎯 Darts",
        "AFL" to "🏉 AFL",
        "Other" to "🎱 More Sports",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The sport pages are single-page listings of today's and tomorrow's events.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
        val base = base()
        val items = app.get("$base/${request.data}", referer = "$HUB").document.toSearchResults(base)
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = base()
        val q = URLEncoder.encode(query, "UTF-8")
        return app.get("$base/search?q=$q", referer = "$base/").document.toSearchResults(base)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun Element.toSearchResults(base: String): List<SearchResponse> =
        select("a.tf-ev").mapNotNull { it.toSearchResult(base) }.distinctBy { it.url }

    private fun Element.toSearchResult(base: String): SearchResponse? {
        val href = attr("href").ifBlank { return null }
        val url = if (href.startsWith("http")) href else base.trimEnd('/') + "/" + href.trimStart('/')

        // Team games carry the matchup in the title attr; channels carry their name there.
        val title = attr("title").ifBlank {
            val sides = select(".tf-evnm").map { it.text().trim() }.filter { it.isNotBlank() }
            when {
                sides.size >= 2 -> "${sides.first()} vs ${sides.last()}"
                sides.size == 1 -> sides.first()
                else -> return null
            }
        }
        val live = selectFirst(".tf-livetag") != null
        // The middle cell holds the start time, "24/7", or (when live) the score/clock.
        val mid = selectFirst(".tf-evmid")?.text()?.trim().orEmpty()
            .replace("Live", "").trim()
        val name = buildString {
            if (live) append("🔴 ")
            append(title)
            if (mid.isNotBlank() && mid != title) append("  •  ").append(mid)
        }
        // Team logos are PNGs; a lone channel uses an inline SVG sport icon we skip.
        val poster = select("img").map { it.absUrl("src").ifBlank { it.attr("src") } }
            .firstOrNull { it.startsWith("http") && !it.endsWith(".svg") }

        return newLiveSearchResponse(name, url, TvType.Live) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val eventUrl = resolveEventUrl(url)
        val doc = app.get(eventUrl, referer = "${base()}/").document

        val title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: eventUrl.substringAfterLast("/event/").substringBefore("?")
                .trim('/').replace('-', ' ').trim().ifBlank { "FoxTrend" }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }

        // dataUrl is the event page; loadLinks re-reads it to collect every server's embed.
        return newLiveStreamLoadResponse(title, eventUrl, eventUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /** A `/game/<slug>` page links to its `/event/<slug>` player page; an event URL is already one. */
    private suspend fun resolveEventUrl(url: String): String {
        if ("/event/" in url) return url
        return runCatching {
            app.get(url, referer = "${base()}/").document
                .selectFirst("a[href*=/event/]")?.absUrl("href")?.ifBlank { null }
        }.getOrNull() ?: url
    }

    /**
     * The event page has one `<iframe id=streamIframe>` for the active server and an
     * `a.sr` tab per other server (each a page with its own iframe). We gather every
     * distinct embed URL, then let a headless WebView run each one and report the
     * `.m3u8` it fetches.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(data) ?: return false
        val doc = app.get(data, referer = "${base()}/").document

        val servers = linkedMapOf<String, String>() // embedUrl -> label
        fun add(label: String, src: String?) {
            val embed = src?.let { absUrl(it, origin) }?.takeIf { it.startsWith("http") } ?: return
            if (!servers.containsKey(embed)) servers[embed] = label.ifBlank { "Server ${servers.size + 1}" }
        }

        val activeLabel = doc.selectFirst("a.sr.sr-on")?.cleanLabel() ?: "Main"
        add(activeLabel, doc.selectFirst("iframe#streamIframe, iframe[src]")?.attr("src"))

        for (tab in doc.select("a.sr")) {
            if (tab.hasClass("sr-on")) continue
            if (servers.size >= MAX_SERVERS) break
            val tabUrl = tab.absUrl("href").ifBlank { continue }
            val src = runCatching {
                app.get(tabUrl, referer = data).document
                    .selectFirst("iframe#streamIframe, iframe[src]")?.attr("src")
            }.getOrNull()
            add(tab.cleanLabel(), src)
        }

        log("servers for $data: ${servers.values}")
        var found = false
        for ((embed, label) in servers.entries.take(MAX_SERVERS)) {
            val m3u8 = resolveM3u8(embed, referer = data)
            if (m3u8 == null) {
                log("$label ($embed): no m3u8")
                continue
            }
            val embedOrigin = originOf(embed) ?: origin
            callback(
                newExtractorLink("FoxTrend", "FoxTrend — $label", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$embedOrigin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            log("$label: $m3u8")
            found = true
        }
        return found
    }

    /** Load [embedUrl] in a headless WebView and return the first `.m3u8` it requests. */
    private suspend fun resolveM3u8(embedUrl: String, referer: String): String? = runCatching {
        app.get(
            embedUrl,
            referer = referer,
            interceptor = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                timeout = WEBVIEW_TIMEOUT_MS,
            ),
        ).url.takeIf { ".m3u8" in it }
    }.onFailure { log("webview $embedUrl: $it") }.getOrNull()

    /**
     * The schedule host rotates; the hub always lists the current FoxTrend landing,
     * whose own links reveal the schedule host. Resolved once per session.
     */
    private suspend fun base(): String {
        baseCache?.let { return it }
        val resolved = runCatching {
            val hub = app.get(HUB).document
            val landing = hub.select("a.card").firstOrNull {
                it.text().contains("foxtrend", true) || "foxtrend" in it.attr("href")
            }?.absUrl("href")?.ifBlank { null } ?: "https://foxtrend.app/"

            val landingDoc = app.get(landing).document
            landingDoc.select("a[href*=/game/], a[href*=/F1], a[href*=/NBA]")
                .map { it.absUrl("href") }
                .firstOrNull { "foxtrend" in it }
                ?.let { originOf(it) }
        }.getOrNull()
        return (resolved ?: DEFAULT_BASE).also { baseCache = it }
    }

    private fun Element.cleanLabel(): String =
        text().replace("PLAYING", "", ignoreCase = true).replace(Regex("\\s+"), " ").trim()

    private fun originOf(url: String): String? = Regex("""^https?://[^/]+""").find(url)?.value

    private fun absUrl(src: String, origin: String): String = when {
        src.startsWith("http") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> origin + src
        else -> "$origin/$src"
    }

    private fun log(message: String) = Log.d("FoxTrend", message)

    companion object {
        private const val HUB = "https://foxlink.buzz/"
        private const val DEFAULT_BASE = "https://foxtrend.fyi"
        private const val MAX_SERVERS = 3
        private const val WEBVIEW_TIMEOUT_MS = 25_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

        @Volatile
        private var baseCache: String? = null
    }
}
