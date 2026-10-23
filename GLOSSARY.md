# CloudStream Analytics & Monitoring System

Real-time telemetry and monitoring platform for CloudStream repository providers, tracking active live users, scraper health, streaming traffic, and content popularity.

## Language

**Telemetry Event**:
An asynchronous, non-blocking data payload dispatched from an Android provider instance to record a lifecycle action.
_Avoid_: Tracking ping, log payload, beacon

**Heartbeat**:
A periodic lightweight ping sent at regular intervals while a provider is active to maintain live presence status.
_Avoid_: Health check, keep-alive, poller

**Live Session**:
An active user state where a Heartbeat or Telemetry Event was received within the defined freshness window (e.g., last 2 minutes).
_Avoid_: Online user, active connection, connected client

**Anonymous Device ID**:
A pseudo-random UUID generated on the client and stored in the app's local key-value store to group sessions without collecting personal data.
_Avoid_: User ID, MAC address, account identifier

**Provider Health Metric**:
Aggregated statistics measuring request success rates, playback resolution failures, and scraper DOM exceptions across individual providers.
_Avoid_: Error log, scraper status, uptime check

**Freshness Window**:
The sliding time window (2 minutes) within which a device's heartbeat or activity qualifies the session as actively live.
_Avoid_: Timeout duration, activity threshold

**Event Retention Policy**:
The automated pruning mechanism that purges raw historical telemetry events older than 30 days while preserving aggregated metrics.
_Avoid_: Log rotation, cleanup cron, TTL

## MovieBox Streaming Domain

**BFF Endpoint**:
A Backend-for-Frontend mobile aggregation endpoint under `/wefeed-mobile-bff` providing tailored payloads for subject metadata, play-info, and seasonal episode hierarchies.
_Avoid_: REST endpoint, web scraper, API route

**Canonical Signature**:
An HMAC-SHA256 signature calculated over normalized HTTP method, headers, request body MD5, and sorted query parameters using embedded base64 secret keys.
_Avoid_: API hash, auth token, request checksum

**Decoy Stream**:
A dummy or rate-limiting media URL returned by upstream CDN servers that does not contain playable media and must be rejected before player dispatch.
_Avoid_: Fake link, broken link, dead link

**Section Manifest**:
The user-configurable ordered collection of homepage catalog rows and ranking categories persisted in CloudStream preferences.
_Avoid_: Home list, category config, feed array

**Edge-Cache Policy**:
A signed cookie pair (`urlprefix` and `CloudFront-Policy`) required by ExoPlayer to authenticate playback against protected CDN manifest fragments.
_Avoid_: Stream header, player auth, session cookie
