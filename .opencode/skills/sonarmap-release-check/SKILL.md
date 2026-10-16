---
name: sonarmap-release-check
description: Use before committing, opening a PR, or finishing a SonarMap task to review Android build, permissions, lifecycle, map/audio abstractions, and MVP scope.
metadata:
  opencode/slash: "true"
---

## Review checklist

- Kotlin/Compose code follows project conventions.
- No direct map SDK or audio SDK dependency outside its adapter.
- Location updates and audio resources are released correctly.
- Runtime permissions and foreground-service behavior are handled.
- GPS and compass inputs are smoothed; gain/source switching uses hysteresis.
- No legacy Google Directions API usage.
- No accidental addition of accounts, streaming, social, or payment features.
- Room migrations and schema changes are safe.
- Potential battery, threading, and lifecycle issues are called out.

## Output

Provide:
1. Blocking issues.
2. Recommended fixes.
3. Optional improvements.
4. A concise commit/PR description.
