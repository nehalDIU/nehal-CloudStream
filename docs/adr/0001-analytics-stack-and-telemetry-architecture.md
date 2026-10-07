# Telemetry Architecture and Stack for Live Analytics Monitoring

To monitor live users, streaming activity, content trends, and scraper errors across CloudStream repository providers without degrading user playback performance or running costly server infrastructure, we will use a Next.js web application deployed to Vercel, paired with Supabase (PostgreSQL + Realtime WebSockets) for event ingestion and live session subscriptions.

## Status
Accepted

## Considered Options
- **Next.js + Supabase on Vercel**: Zero-maintenance serverless setup, native WebSocket subscriptions for live dashboard feeds, built-in auth, and generous free tier.
- **Cloudflare Workers + D1/KV**: Fast global edge ingestion, but requires custom polling/WebSockets implementation for the dashboard UI.
- **Self-Hosted VPS (Node/Go + WebSockets)**: High operational maintenance and hosting costs.

## Key Decisions
1. **Client Isolation**: Client providers dispatch telemetry using non-blocking, fire-and-forget `ioSafe` background coroutines with a strict 3-second timeout and silent error suppression. Provider streaming never depends on or waits for analytics responses.
2. **Presence Window**: Live user status is calculated using a 2-minute freshness threshold on periodic 60-second heartbeats and lifecycle action events (`search`, `view`, `play`, `error`).
3. **Privacy**: Devices are identified only via locally generated, anonymous random UUIDs with no personally identifiable data collected.
