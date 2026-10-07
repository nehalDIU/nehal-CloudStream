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
