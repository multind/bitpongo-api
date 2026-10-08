# Help, articles and App release metadata

This module independently replaces the source H5 content reader and help search. It does not connect to the old tradebot account service or Solr. Existing authentication is unchanged; these API routes require the user's current bearer session.

## Content

Flyway V20 creates `content_article` and `help_search_history`. It seeds two help topics and one article in English, Simplified Chinese and Traditional Chinese. The seeds describe current Bitpongo behavior; they are not an import of the source project's article database.

- `GET /api/content/articles?kind=HELP&locale=en-us&q=notification&page=0`: published content search, 20 results per page and `hasMore`.
- `GET /api/content/articles/{slug}?locale=zh-tw`: localized article detail; missing, unpublished and future-dated records return 404.
- `GET /api/content/search-history`: the current user's most recent 20 queries.
- `POST /api/content/search-history` with `{"query":"notifications"}`: trim and deduplicate, retain at most 20 entries.
- `DELETE /api/content/search-history/{id}`: owned entry only.
- `DELETE /api/content/search-history`: clear only the current user's history.

Supported locales are `en-us`, `zh-cn` and `zh-tw` (case-insensitive). Other locales are rejected rather than silently mixing languages. Kind is `HELP` or `ARTICLE`. Search treats `%` and `_` literally and caps query length at 100. Responses use camelCase and UTC timestamps. Account deletion clears search history in the same transaction.

Publish content through a reviewed database migration or the deployment team's database workflow. No public content-write endpoint is exposed. Maintain one record per `(slug,locale)`, an actual UTC publication time and `published=true`. Body content is plain text, not HTML. Imported legacy HTML must be converted to text first; do not paste scripts or assume markup will render. Original article-data migration and CMS editing are separate work.

## Native App update check

`GET /api/content/app-version?platform=ios&version=1.0.1&build=15`

Configure only versions already available to the intended users in the stores, not unreleased TestFlight/internal builds:

The supplied `compose.yml` does not forward `BITPONGO_*` release metadata automatically. Add it explicitly to the API service's `environment` in a local deployment override; `.env` alone does not inject it. Docker/1Panel deployments must set it directly on the API container. Market-provider overrides similarly need explicit container environment entries when not listed in `compose.yml`.

```dotenv
BITPONGO_IOS_LATEST_VERSION=
BITPONGO_IOS_LATEST_BUILD=
BITPONGO_IOS_STORE_URL=https://apps.apple.com/app/id6473227759
BITPONGO_ANDROID_LATEST_VERSION=
BITPONGO_ANDROID_LATEST_BUILD=
BITPONGO_ANDROID_STORE_URL=https://play.google.com/store/apps/details?id=io.imastermind.bitpongo
BITPONGO_APP_RELEASE_NOTES=
```

Versions are numeric `major.minor.patch`; builds are positive integers. Empty/invalid deployment metadata returns `available=false`, not “up to date”. Version comparison is numeric; a newer build of the same version is recommended only when the caller supplied its installed build. A higher installed version is never downgraded. URLs are restricted to the current app's HTTPS Apple/Google store entries. Release notes are plain text, at most 2,000 characters.

The About page obtains the actual App version from the existing native bridge, never from the H5 bundle version. The currently defined native context may report only a version without its build number; in that case only version differences can be detected. Browsers show an App-only hint. This does not change native bridge contracts or automatically install updates. Store availability can vary by region; a link does not guarantee availability there.

No new provider credentials, mounts or authentication settings are needed for this module. Set the environment variables on the API container and recreate/restart it through the normal deployment workflow. Production deployment and physical-device store navigation have not been performed by this migration.
