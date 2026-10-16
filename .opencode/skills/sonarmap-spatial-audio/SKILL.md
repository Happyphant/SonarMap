---
name: sonarmap-spatial-audio
description: Use for audio playback, stereo/spatial positioning, FMOD or Resonance Audio integration, virtual sound sources, volume, and audio engine lifecycle.
---

## Rules

- Keep all engine-specific code behind an `AudioEngine` interface.
- Do not let Compose, ViewModel, or map code import FMOD/Resonance types directly.
- Use headphones-first spatial model: azimuth from user heading and relative source bearing; distance controls gain.
- Smooth heading, position, distance, and gain; apply hysteresis before switching active virtual sources.
- For a route, use multiple nearby virtual sources rather than one global source.
- Initialize and release the audio engine on a defined lifecycle owner; never leak native audio resources.
- Do not perform audio engine calls on the main thread if the API is blocking or native-heavy.
- MVP must use locally bundled audio assets; do not add streaming.

## Definition of done

- Audio engine can be swapped without changing domain/UI code.
- No native handles are leaked.
- Source direction and loudness respond smoothly to movement and device orientation.
