---
name: sonarmap-map-provider
description: Use when implementing or changing map display, geolocation pins, routes, polylines, camera behavior, or map provider integration in SonarMap.
---

## Rules

- Keep all map-specific code behind a `MapProvider` abstraction.
- The domain layer must not depend directly on Google Maps or Yandex MapKit types.
- Use Google Maps Compose for the MVP Google Maps implementation.
- Treat Yandex MapKit as a separate adapter, not as a hard dependency of core features.
- For routes, use Google Routes API; never add legacy Google Directions API to new integrations.
- Represent routes as domain polyline/segment models before converting them to provider-specific objects.
- For audio routing, expose nearby route segments and their geometry to the audio layer; do not let the audio engine query map SDK objects directly.

## Definition of done

- Map implementation can be replaced without changing domain models.
- Marker and route state survives configuration changes through the appropriate ViewModel/repository layer.
- No provider-specific types leak outside the map adapter.
