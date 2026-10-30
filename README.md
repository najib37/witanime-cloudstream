# WitAnime for CloudStream

A [CloudStream](https://github.com/recloudstream/cloudstream) provider for [witanime.site](https://witanime.site) (Arabic-subtitled anime).

## Install

In CloudStream: **Settings → Extensions → Add repository**, then paste:

```
https://raw.githubusercontent.com/najib37/witanime-cloudstream/main/repo.json
```

## Build locally

```sh
./gradlew WitAnimeProvider:make            # WitAnimeProvider/build/WitAnimeProvider.cs3
./gradlew WitAnimeProvider:deployWithAdb   # push to a connected device
```

Local plugins need CloudStream to have All Files Access:
`adb shell appops set --uid com.lagradost.cloudstream3 MANAGE_EXTERNAL_STORAGE allow`

## Notes

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
