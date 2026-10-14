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
