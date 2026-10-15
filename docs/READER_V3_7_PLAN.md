# Flare Reader v3.7 — scoped implementation and verification plan

Baseline: `reader-v3` at `9dfe320cb362e15514445b1401d6f3c1cc6b8f05` (v3.6.1). This separate `reader-v3.7-work` branch protects the known-working Android build.

## Confirmed
- User: v3.6.1 loading and relative timestamps work; in-app Twitter quote search works, but search heading shows `quoted_tweet_id:<id>` rather than source tweet.
- User: Twitter `Reposts bekijken` launches external Twitter app and lands on wrong timeline.
- Code: `CommonStatusComponent.kt` maps Twitter click to `https://<host>/i/status/<id>/retweets` and invokes `LocalUriHandler.openUri`; quote click uses `DeeplinkRoute.Search` with `quoted_tweet_id:<id>`.
- Twitter icon tap must still retweet; count tap must open engagement viewer.
- Existing `ActionMenu.Item` cache schema must **never** change as part of this release.

## Features
1. Investigate existing Twitter API client for actual retweeter account listing. Implement a native in-app screen or existing profile-list presentation only with a supported endpoint and authenticated account. No raw external URL fallback masquerading as implementation.
2. Bluesky: get reposting users and quote posts via Bluesky public/authenticated endpoints using existing post URI and account context. Use same actions and in-app pattern.
3. Replace raw quote-search heading with compact source-tweet preview if existing detail data can be reused without persisted schema changes.
4. Show regular-retweet and quote counts only when actually known independently. Do not fabricate counts from total.
5. Screen upstream work since fork base (2026-09-18, SHA `91b8f86...`). Candidates include safe crash fixes for bad links, Android MediaStore, shrinking emoji lists, media duplicates. Review each code patch before cherry-picking. Do not bulk merge.

## Hard constraints
- Do not modify timeline/paging: `TimelineRemoteMediator`, `MixedRemoteMediator`, home presenters, read-anchor, cache sort, account merge.
- No serialized model or nav-route incompatibility. If a new route is essential, explicitly test upgrading with old on-device state.
- Build green is insufficient: install over v3.6.1 without clearing application data and verify startup, timeline, position, timestamps, normal and quote retweets on Twitter and Bluesky.

## Release checklist
- Identify verified API paths and rate-limit/error states
- Implement UI and data loading with isolated tests
- Run compatibility/regression suite and build signed Android APK
- Compare production diff against stable branch; reject edits to protected systems
- Hand APK to user; user conducts Android smoke test before promoting branch

## API verification (2026-10-15)

- **Bluesky repost users:** Official AT Protocol method `app.bsky.feed.getRepostedBy`, required parameter `uri` (AT-URI), optional `cid`, `limit` (1–100), `cursor`. Result has `repostedBy` profile views and optional next cursor. Use `PostEvent.Bluesky.Reblog.uri` and `cid`, not the display post ID.
- **Bluesky quote posts:** Official method `app.bsky.feed.getQuotes`, same subject parameters, result `posts` and optional cursor. Reuse existing Bluesky post mapping; do not treat quote count as total minus repost count.
- Official Bluesky endpoint schemas: https://github.com/bluesky-social/atproto/blob/main/lexicons/app/bsky/feed/getQuotes.json and https://github.com/bluesky-social/atproto/blob/main/lexicons/app/bsky/feed/getRepostedBy.json.
- Existing `BlueskyService` delegates to `BlueskyApi` through `XrpcBlueskyApi`; verify exact generated Kotlin function signatures before calling them. Implement paging in a **new engagement screen**, not in timeline mediator.
- **Twitter:** The fork contains generated `TweetRetweetersResponse` models but its `TweetApi` does not currently declare a retweeters operation. No working authenticated endpoint has been verified. Do not claim an in-app Twitter retweeter list until endpoint, parsing, and account-specific authentication have been tested.
- **CI:** A development-branch build was previously canceled by newer pushes. Workflow now keeps in-flight runs. Latest complete green run: 38782541863 at commit `38a2b41`; newer commits require validation.

