# Voice rooms

CoreDSC 3.0.1-alpha can create temporary Discord voice rooms and move linked Discord members based on Minecraft proximity.

In 3.0.1-alpha, this module manages Discord rooms and member movement only. Audio bridging to Simple Voice Chat is not included, so both audio options must stay disabled.

## Requirements

- Voice States gateway intent
- Manage Channels and Move Members permissions
- a configured Discord category and lobby channel
- linked player accounts

## Limits

The module bounds active rooms, grouping distances, update frequency and cleanup behavior. The opt-out permission is `coredsc.voice.optout`.

Use this module for room management, not for audio bridging.
