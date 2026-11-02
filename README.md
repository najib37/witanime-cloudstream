# CloudStream providers

[CloudStream](https://github.com/recloudstream/cloudstream) providers in this repo:

- **WitAnime** — [witanime.site](https://witanime.site), Arabic-subtitled anime.
- **FoxTrend** — live sports (F1, football, NBA, NFL, UFC and more) via the
  [foxlink.buzz](https://foxlink.buzz) FoxTrend / StreamEast link hub.

## Install

In CloudStream: **Settings → Extensions → Add repository**, then paste:

```
https://raw.githubusercontent.com/najib37/witanime-cloudstream/main/repo.json
```

## Build locally

```sh
./gradlew WitAnimeProvider:make            # WitAnimeProvider/build/WitAnimeProvider.cs3
./gradlew FoxTrendProvider:make            # FoxTrendProvider/build/FoxTrendProvider.cs3
./gradlew make makePluginsJson             # everything, like CI
./gradlew WitAnimeProvider:deployWithAdb   # push one plugin to a connected device
```

Local plugins need CloudStream to have All Files Access:
`adb shell appops set --uid com.lagradost.cloudstream3 MANAGE_EXTERNAL_STORAGE allow`

## WitAnime

- The site rate-limits aggressively (20 source requests per window per IP), so servers are resolved one at a time and links can take ~20 s to appear.
- Servers (extractors in `Hosts.kt`):
  - ok.ru: every quality, 144p–1080p (CloudStream's own ok.ru extractor no longer matches the embed)
  - mp4upload: each quality the site has, from stream and download servers, de-duplicated
  - videas: HLS, 360p–1080p
  - 4shared: the full mp4 from the embed
  - yonaplay: its own server picker (AES-GCM encrypted embeds) leading to Dotplay mp4s
- Skipped: mega (end-to-end encrypted), hgcloud (player built in obfuscated JS), workupload (no extractor), videa (signed links got 403 in the player), mediafire (.zip archives), gofile (API rate-limits guests).
- The sources endpoint rejects a full Chrome User-Agent sent without `sec-ch-ua` client hints, so link requests send both.
- Link resolution logs to logcat under the `WitAnime` tag: `adb logcat -s WitAnime`.

## FoxTrend

Live sports reached through `foxlink.buzz`, the hub that always points at the current
working FoxTrend domain. The main page lists one row per sport (F1 first), each an event
list for today and tomorrow; there is also search.

- **Domain rotation.** FoxTrend's streaming domains get blocked and change often. The
  provider reads `foxlink.buzz` at runtime to find the current schedule host (falling back
  to `foxtrend.fyi`) and caches it for the session, so a rotated domain fixes itself.
- **How a stream is found.** An event page (`foxiy.*/event/<slug>`) holds one `<iframe>`
  per server. Each embed builds its real HLS URL in JavaScript **and a WASM step**, so the
  URL can't be rebuilt from Kotlin. Instead the provider loads each embed in a headless
  WebView (`WebViewResolver`), which runs the page and lets it intercept the `.m3u8` the
  embed ends up requesting. Up to 3 servers are tried per event.
- Because each server means one WebView load, links can take a few seconds to appear, and
  an event only resolves while it is actually live.
- Link resolution logs to logcat under the `FoxTrend` tag: `adb logcat -s FoxTrend`.
