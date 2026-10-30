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
- Working servers: ok.ru (all qualities, 144p–1080p, parsed by the plugin since CloudStream's ok.ru extractor no longer matches the embed) and mp4upload (each quality the site has, from both stream and download servers, de-duplicated).
- Skipped: mega, workupload, videas, 4shared (no extractor), yonaplay (a server picker), hgcloud (player built in JS), videa (extractor returns nothing), mediafire (.zip archives), gofile (API rate-limits guests).
- The sources endpoint rejects a full Chrome User-Agent sent without `sec-ch-ua` client hints, so link requests send both.
- Link resolution logs to logcat under the `WitAnime` tag: `adb logcat -s WitAnime`.
