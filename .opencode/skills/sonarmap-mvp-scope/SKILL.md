---
name: sonarmap-mvp-scope
description: Use when planning SonarMap features, estimating scope, or deciding whether to add accounts, social features, payments, streaming, or non-MVP infrastructure.
metadata:
  opencode/slash: "true"
---

## MVP boundary

Included:

- Map with user-placed music pins.
- Route creation and route-bound spatial audio.
- Local audio assets.
- Location-aware playback.
- Foreground service for continuous use.
- Room persistence for pins/routes.
- MapProvider and AudioEngine abstractions.

Not included unless explicitly requested:

- User accounts.
- Social features.
- Cloud sync.
- Music streaming.
- Payments/subscriptions.
- Analytics beyond basic crash reporting.
- Legacy Google Directions API.

## Rule

If a requested change expands scope, first state the MVP trade-off, then implement only what was explicitly requested.
