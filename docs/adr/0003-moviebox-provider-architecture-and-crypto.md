# MovieBox Provider Architecture & Cryptographic Signature Handshake

We decided to implement the MovieBox CloudStream provider (`MovieBoxProvider`) as a standalone plugin module targeting the multi-host Backend-for-Frontend (BFF) API pool (`api3.aoneroom.com` through `api7.aoneroom.com`), utilizing an automated request normalization and canonical HMAC-SHA256 signature protocol (`x-tr-signature` and `x-client-token`) with guest token harvesting and custom section ordering UI.

## Context & Trade-Offs

MovieBox exposes high-quality movies, multi-language series, anime, and real-time live sports streams without public REST documentation. The upstream mobile backend enforces strict anti-scraping protections:
1. **Request Canonicalization**: Headers, timestamps, sorted query parameters, and MD5-hashed payload segments are required to generate the HMAC-SHA256 `x-tr-signature`.
2. **Dynamic Client Tokens**: Generated per-request via MD5 hashes of reversed timestamps.
3. **Multi-Host Resilience**: Individual edge servers experience regional throttling; maintaining an active host pool with automatic fallback ensures continuous availability.
4. **Decoy & CDN Policy Filtering**: Upstream returns honeypot/decoy stream links and requires CloudFront/Edge-Cache signed cookies (`urlprefix`, `CloudFront-Policy`) to authenticate video chunk playback in ExoPlayer.
5. **Clean Zero-Bloat Recreation**: Third-party donation modals and external ad tracking present in historical binaries are stripped, while the native Android resource-based Section Manager UI is preserved.
