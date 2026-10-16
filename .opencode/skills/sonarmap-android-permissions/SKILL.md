---
name: sonarmap-android-permissions
description: Use when adding or changing Android permissions, foreground services, location access, notifications, audio focus, or runtime permission UX.
---

## Rules

- Declare only permissions required by the implemented MVP feature.
- Request runtime permissions with a clear user-facing rationale.
- Handle denied, permanently denied, and settings-disabled cases without crashing.
- For background/continuous location, justify the foreground service type and show an appropriate notification.
- Check both manifest declarations and runtime requests together.
- Do not request broad permissions preemptively; request them at the point of feature use.

## Checklist

- Manifest permission present only if used.
- Runtime request implemented.
- Denial path implemented.
- Foreground service notification and type correct.
- Location/audio resources stopped when permission is revoked or feature is disabled.
