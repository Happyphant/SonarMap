---
name: sonarmap-location
description: Use for GPS/location tracking, route proximity, foreground location service, smoothing, battery optimization, or distance-to-audio-source logic.
---

## Rules

- Use FusedLocationProviderClient for location updates.
- Request only the minimum necessary location accuracy and update interval.
- Smooth raw GPS positions before using them for audio gain or spatial positioning.
- Apply hysteresis to volume and source selection to prevent audible flickering near route boundaries.
- Use a Foreground Service only when continuous location/audio playback is required.
- Stop location updates and release audio resources when the user leaves the map/audio screen or disables playback.
- Handle permission denial, location disabled, and no-fix states explicitly.

## Route audio model

- For a route, create several virtual audio sources along the closest polyline segments.
- Select sources by projected distance from the user to each segment.
- Compute gain from distance, then smooth it over time.
- Do not create one audio source per polyline vertex.

## Definition of done

- No location updates run when not needed.
- Audio gain changes are smooth and bounded.
- Service lifecycle, permissions, and battery impact are considered.
