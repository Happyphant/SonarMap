---
name: sonarmap-android-compose
description: Use for any SonarMap Android UI, Jetpack Compose, ViewModel, navigation, state, or Kotlin app-layer change.
metadata:
  opencode/slash: "true"
---

## Rules

- Use Kotlin and Jetpack Compose; do not introduce XML Views for new UI.
- Keep UI stateless where possible; expose state from ViewModels as `StateFlow`.
- Use `collectAsStateWithLifecycle()` for lifecycle-safe Compose collection.
- Do not pass Android `Context` into composables beyond what is required.
- Keep navigation, business logic, map access, and audio control separated.
- Prefer immutable UI models over exposing Room entities directly to Compose.
- Use Coroutines for async work; never block the main thread.
- For lists of map markers or route segments, use stable keys and avoid recreating collections on every recomposition.

## Definition of done

- Code compiles conceptually with current Kotlin/Compose APIs.
- No memory-sensitive resources are held by composables.
- State changes are testable and do not depend on Activity lifecycle.
