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

- The site rate-limits aggressively, so links are resolved one server at a time and can take ~20 s to appear.
- Servers without a CloudStream extractor (mega, workupload, yonaplay, videas, 4shared) are skipped.
- The sources endpoint rejects a full Chrome User-Agent sent without `sec-ch-ua` client hints, so link requests send both.
- Link resolution logs to logcat under the `WitAnime` tag: `adb logcat -s WitAnime`.
