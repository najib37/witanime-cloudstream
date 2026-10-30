package com.witanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractors for the hosts WitAnime links to that CloudStream can't play on its own,
 * either because it has no extractor for them or because the built-in one is broken.
 */
object Hosts {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    private val UA = mapOf("User-Agent" to USER_AGENT)
    private val JSON_HEADERS = UA + mapOf(
        "Accept" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
    )

    /** Hosts handled here; anything else goes to CloudStream's [loadExtractor]. */
    suspend fun extract(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = when {
        "ok.ru/" in url -> okRu(url, callback)
        "videas.fr/" in url -> videas(url, callback)
        "4shared.com/" in url -> fourShared(url, referer, callback)
        "yonaplay." in url -> yonaplay(url, referer, subtitleCallback, callback)
        "dotplay." in url -> dotplay(url, null, callback)
        else -> {
            loadExtractor(url, referer, subtitleCallback, callback)
            Unit
        }
    }

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

    /**
     * CloudStream's built-in ok.ru extractor expects backslash-escaped JSON that the embed no
     * longer has, so it finds nothing. The embed's data-options attribute holds a
     * flashvars.metadata object (or JSON string) whose "videos" lists one mp4 per quality.
     */
    private suspend fun okRu(url: String, callback: (ExtractorLink) -> Unit) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val options = app.get(embedUrl, headers = UA).document
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
                    headers = UA
                }
            )
        }
    }

    /** videas.fr: the embed API hands out a playback token that playback/info trades for an HLS master. */
    private suspend fun videas(url: String, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/embed/media/([0-9a-f-]{36})""").find(url)?.groupValues?.get(1) ?: return
        val base = "https://app.videas.fr"
        val cookies = app.get(url, headers = UA).cookies.toMutableMap()
        val embed = app.get("$base/api/visitor/embed/$id/", headers = JSON_HEADERS, cookies = cookies, referer = url)
        cookies.putAll(embed.cookies)
        val embedJson = JSONObject(embed.text)

        val body = JSONObject()
            .put("asset_id", id)
            .put("playback_token", embedJson.optString("playback_token"))
            .put("session_id", embedJson.optString("session_id"))
        val info = app.post(
            "$base/api/visitor/playback/info/",
            headers = JSON_HEADERS + mapOf("Origin" to base),
            cookies = cookies, referer = url,
            requestBody = body.toString().toRequestBody("application/json".toMediaType()),
        )
        val hls = JSONObject(info.text).optJSONObject("hls_url")?.optString("url")?.ifBlank { null } ?: return
        val master = base + hls
        val links = M3u8Helper.generateM3u8("Videas", master, "$base/")
        // The master shows up next to its own variants as a second "1080p"
        links.filter { it.url != master || links.size == 1 }.forEach(callback)
    }

    /** 4shared's embed page has the full video as a plain <source>. */
    private suspend fun fourShared(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val src = app.get(url, headers = UA, referer = referer).document
            .selectFirst("video source[src], source[src]")?.attr("src") ?: return
        callback(
            newExtractorLink("4shared", "4shared", src, ExtractorLinkType.VIDEO) {
                this.referer = "https://www.4shared.com/"
            }
        )
    }

    private val YONAPLAY_QUALITIES = mapOf(
        "4k" to Qualities.P2160.value,
        "fhd" to Qualities.P1080.value,
        "hd" to Qualities.P720.value,
        "sd" to Qualities.P480.value,
    )

    /**
     * yonaplay is its own server picker: init-session gives a code and key, sources.php lists
     * servers per quality, and api.php returns each server's embed URL AES-GCM encrypted with
     * SHA-256(key) (12-byte IV, 16-byte tag, then ciphertext).
     */
    private suspend fun yonaplay(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = Regex("""^https?://[^/]+""").find(url)?.value ?: return
        val page = app.get(url, headers = UA, referer = referer)
        val cookies = page.cookies.toMutableMap()
        val apiPath = Regex("""_E\s*=\s*\{\s*a\s*:\s*"([^"]+)"""").find(page.text)?.groupValues?.get(1) ?: "/api"
        val api = if (apiPath.startsWith("http")) apiPath else origin + apiPath
        val headers = JSON_HEADERS + mapOf("Origin" to origin)

        suspend fun post(endpoint: String, body: JSONObject): JSONObject {
            val res = app.post(
                "$api/$endpoint", headers = headers, cookies = cookies, referer = url,
                requestBody = body.toString().toRequestBody("application/json".toMediaType()),
            )
            cookies.putAll(res.cookies)
            return JSONObject(res.text)
        }

        val session = post("init-session.php", JSONObject())
        val code = session.optString("c").ifBlank { return }
        val key = session.optString("k").ifBlank { return }
        val qualities = post("sources.php", JSONObject().put("code", code)).optJSONObject("qualities") ?: return

        for (q in qualities.keys()) {
            val servers = qualities.optJSONObject(q)?.optJSONArray("servers") ?: continue
            for (i in 0 until servers.length()) {
                val serverToken = servers.optJSONObject(i)?.optString("token")?.ifBlank { null } ?: continue
                val resolved = post("api.php", JSONObject().put("code", code).put("token", serverToken).put("key", key))
                val embed = resolved.optString("d").ifBlank { null }?.let { decryptYonaplay(it, key) } ?: continue
                val quality = YONAPLAY_QUALITIES[q.lowercase()]
                if ("dotplay." in embed) dotplay(embed, quality, callback)
                else loadExtractor(embed, "$origin/", subtitleCallback) { link ->
                    if (quality != null && link.quality == Qualities.Unknown.value) link.quality = quality
                    callback(link)
                }
            }
        }
    }

    private fun decryptYonaplay(data: String, key: String): String? = runCatching {
        val raw = base64DecodeArray(data)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()), "AES"),
            GCMParameterSpec(128, raw.copyOfRange(0, 12)),
        )
        // Java expects ciphertext followed by the tag
        String(cipher.doFinal(raw.copyOfRange(28, raw.size) + raw.copyOfRange(12, 28)))
    }.getOrNull()?.takeIf { it.startsWith("http") }

    /** dotplay's api.php returns base64("<mp4 url>|..."). */
    private suspend fun dotplay(url: String, quality: Int?, callback: (ExtractorLink) -> Unit) {
        val origin = Regex("""^https?://[^/]+""").find(url)?.value ?: return
        val code = url.substringAfter("/embed/").substringBefore("?").trimEnd('/').ifBlank { return }
        val page = app.get(url, headers = UA)
        val res = app.get(
            "$origin/api.php?code=$code",
            headers = UA + mapOf("Accept" to "application/json"),
            cookies = page.cookies, referer = url,
        )
        val encoded = JSONObject(res.text).optString("video_url").ifBlank { return }
        val videoUrl = String(base64DecodeArray(encoded)).substringBefore("|").ifBlank { return }
        callback(
            newExtractorLink("Dotplay", "Dotplay", videoUrl, ExtractorLinkType.VIDEO) {
                referer = "$origin/"
                this.quality = quality ?: Qualities.Unknown.value
            }
        )
    }
}
