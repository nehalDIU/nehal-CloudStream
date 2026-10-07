# Dashboard Monorepo Placement, Phased Rollout, and Storage Retention

To ensure maintainability, low friction, and zero database bloat, the analytics monitoring web application will reside directly in a `dashboard/` directory within the primary repository, with telemetry rolled out iteratively across flagship providers and historical events governed by a 30-day automated retention policy.

## Status
Accepted

## Decisions
1. **Monorepo Structure**: The Next.js dashboard and API reside in `dashboard/` inside `nehal-CloudStream`, allowing synchronized deployments, unified Git tracking, and single-repository governance.
2. **Dual-Table Schema**: Telemetry utilizes `active_sessions` for lightweight presence queries (indexed on `last_active`) and `telemetry_events` for audit history and trending statistics.
3. **Phased Rollout**: Telemetry is first deployed to flagship providers (`MovieBoxProviderIN`, `VegaMovies`, `CastleTvProvider`, `FTPBD`) to validate end-to-end latency and data fidelity before broader repo deployment.
4. **Data Pruning**: Raw telemetry events older than 30 days are automatically pruned to maintain database performance and remain permanently within free-tier resource boundaries.
